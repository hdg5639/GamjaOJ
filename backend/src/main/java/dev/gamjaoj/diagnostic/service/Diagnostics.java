package dev.gamjaoj.diagnostic.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.diagnostic.repository.DiagnosticsRepository;
import dev.gamjaoj.generation.service.CallablePrograms;
import dev.gamjaoj.judge.domain.LanguageProfiles;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Diagnostics {
  private final DiagnosticsRepository repository;

  public Diagnostics(DiagnosticsRepository repository) {
    this.repository = repository;
  }

  public record Item(
      UUID id,
      int position,
      String category,
      String difficulty,
      String problemVersion,
      String status,
      int attempts,
      int pending,
      boolean externallySeen,
      String skipReason) {}

  public record Example(String input, String output) {}

  public record Question(
      UUID itemId,
      String problemVersion,
      String title,
      String statement,
      String sampleInput,
      String sampleOutput,
      List<Example> examples,
      List<LanguageProfiles.Option> languages,
      JsonNode api) {}

  /**
   * The first test and the EX-prefixed tests right after it are public examples; every later test
   * stays hidden.
   */
  public static List<Example> examples(JsonNode tests) {
    var out = new java.util.ArrayList<Example>();
    for (int i = 0;
        i < tests.size() && (i == 0 || tests.get(i).path("id").asText().startsWith("EX"));
        i++)
      out.add(
          new Example(tests.get(i).path("input").asText(), tests.get(i).path("output").asText()));
    return out;
  }

  public record View(
      UUID id,
      String bankId,
      String status,
      List<Item> items,
      Question current,
      UUID sourceSessionId,
      java.time.OffsetDateTime createdAt,
      boolean repeatAttempt) {}

  public record Snapshot(String json, String hash, String image, String policy, String limits) {}

  public record Bank(
      String id, List<String> categories, int questionCount, String examType, int setCount) {
    public Bank(String id, List<String> categories, int questionCount) {
      this(id, categories, questionCount, null, 1);
    }
  }

  public static String examFamily(String bank) {
    return bank != null && bank.matches("exam-[ab]-set-0[1-4]-v2")
        ? bank.substring(0, 6) + "-v2"
        : bank;
  }

  public static boolean exam(String bank) {
    return bank != null && bank.matches("exam-[ab]-(v2|set-0[1-4]-v2)");
  }

  public static boolean completePair(List<String> roles) {
    return roles.size() == 2
        && (new java.util.HashSet<>(roles).equals(java.util.Set.of("EASY", "MEDIUM"))
            || new java.util.HashSet<>(roles).equals(java.util.Set.of("CORE", "APPLIED")));
  }

  public List<Bank> banks() {
    var available =
        repository
            .banksDiagnosticBank()
            .map(
                id -> {
                  var categories = repository.banksDiagnosticBankItem(id);
                  int count = repository.banksDiagnosticBankItem2(id);
                  int unavailable = repository.banksDiagnosticBankItem3(id);
                  return count > 0 && count == categories.size() * 2 && unavailable == 0
                      ? new Bank(id, categories, count)
                      : null;
                })
            .filter(java.util.Objects::nonNull)
            .toList();
    var grouped = new java.util.LinkedHashMap<String, Bank>();
    for (var bank : available) {
      String family = examFamily(bank.id());
      var previous = grouped.get(family);
      grouped.put(
          family,
          exam(bank.id())
              ? new Bank(
                  family,
                  bank.categories(),
                  bank.questionCount(),
                  family.substring(5, 6).toUpperCase(),
                  previous == null ? 1 : previous.setCount() + 1)
              : bank);
    }
    return List.copyOf(grouped.values());
  }

  private UUID owner(String name) {
    return repository
        .ownerAppUser(name)
        .orElseThrow(() -> new AccountException(401, "다시 로그인해 주세요."));
  }

  @Transactional
  public View start(String name, UUID id, String bank) {
    return start(name, id, bank, null);
  }

  @Transactional
  public View start(String name, UUID id, String bank, List<String> categories) {
    return startInternal(name, id, bank, categories, null);
  }

  @Transactional
  public View reassess(String name, UUID id, UUID source, String bank, List<String> categories) {
    if (source == null || categories == null)
      throw new AccountException(400, "재평가할 원래 진단과 분야를 선택해 주세요.");
    return startInternal(name, id, bank, categories, source);
  }

  private View startInternal(
      String name, UUID id, String bank, List<String> categories, UUID source) {
    if (categories != null
        && (categories.isEmpty()
            || categories.size() > 20
            || categories.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 80)
            || categories.stream().distinct().count() != categories.size()))
      throw new AccountException(400, "진단할 분야를 중복 없이 선택해 주세요.");
    String requested =
        categories == null
            ? null
            : JudgeJson.canonical(
                JudgeJson.JSON.valueToTree(categories.stream().sorted().toList()));
    UUID user = owner(name);
    if (repository.startInternalDiagnosticSession(id, user) > 0) {
      View saved = view(user, id);
      String previous =
          repository
              .startInternalDiagnosticSession2(id, (r, n) -> new String[] {r.getString(1)})[0];
      if (!(bank != null && bank.matches("exam-[ab]-v2")
              ? examFamily(saved.bankId()).equals(bank)
              : saved.bankId().equals(bank))
          || !java.util.Objects.equals(previous, requested)
          || !java.util.Objects.equals(saved.sourceSessionId(), source))
        throw new AccountException(409, "같은 요청 키의 진단 은행이나 선택 분야가 달라요.");
      return saved;
    }
    if (repository.startInternalDiagnosticSession3(id, user) > 0)
      throw new AccountException(409, "진행 중인 진단을 이어서 진행해 주세요.");
    if (exam(bank)) {
      if (categories != null) throw new AccountException(400, "A/B형 진단은 분야를 나누지 않고 전체 8문항으로 진행해요.");
      if (bank.matches("exam-[ab]-v2")) bank = allocateExam(user, bank);
    }
    if (repository.startInternalDiagnosticBank(bank) == 0)
      throw new AccountException(404, "검토가 완료된 진단 은행을 찾을 수 없어요.");
    var rows = bankItems(bank, categories);
    if (categories != null) {
      var available =
          rows.stream().map(BankItem::category).collect(java.util.stream.Collectors.toSet());
      if (!available.containsAll(categories))
        throw new AccountException(400, "선택한 분야가 진단 은행에 없어요.");
      rows = rows.stream().filter(row -> categories.contains(row.category())).toList();
    }
    if (rows.isEmpty()
        || rows.stream()
            .collect(java.util.stream.Collectors.groupingBy(BankItem::category))
            .values()
            .stream()
            .anyMatch(pair -> !completePair(pair.stream().map(BankItem::difficulty).toList())))
      throw new AccountException(409, "분야별 기본·응용 문항 쌍이 준비되지 않았어요.");
    String correspondence = source == null ? null : validateReassessment(user, source, rows);
    repository.startInternalDiagnosticSession4(
        id, user, bank, user, requested, source, correspondence);
    for (var row : rows) {
      String content = contentHash(row.json());
      if (repository.startInternalDiagnosticExposure(user, content) == 0)
        repository.startInternalDiagnosticExposure2(user, content);
    }
    for (var row : rows)
      repository.startInternalDiagnosticItem(
          UUID.randomUUID(),
          id,
          row.position(),
          row.category(),
          row.difficulty(),
          row.version(),
          row.json(),
          row.hash(),
          row.image(),
          row.policy(),
          row.rubric(),
          row.limits());
    return view(user, id);
  }

  private String allocateExam(UUID user, String family) {
    var candidates = repository.allocateExamDiagnosticBank(family.substring(0, 6) + "-set-%-v2");
    var best = new java.util.ArrayList<String>();
    int least = Integer.MAX_VALUE;
    long leastUsed = Long.MAX_VALUE;
    for (String candidate : candidates) {
      List<BankItem> rows;
      try {
        rows = bankItems(candidate, null);
      } catch (AccountException held) {
        continue;
      }
      if (rows.size() != 8) continue;
      int seen = 0;
      for (var row : rows)
        if (repository.allocateExamDiagnosticExposure(user, contentHash(row.json())) > 0) seen++;
      long used = repository.allocateExamDiagnosticSession(user, candidate);
      if (seen < least || seen == least && used < leastUsed) {
        least = seen;
        leastUsed = used;
        best.clear();
      }
      if (seen == least && used == leastUsed) best.add(candidate);
    }
    if (best.isEmpty()) throw new AccountException(409, "사용할 수 있는 진단 세트를 준비하고 있어요.");
    return best.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(best.size()));
  }

  private List<BankItem> bankItems(String bank, List<String> categories) {
    return repository
        .bankItemsDiagnosticBankItem(
            bank,
            (r, n) -> {
              if (categories != null && !categories.contains(r.getString("category"))) return null;
              if (!r.getBoolean("ready")
                  || r.getBoolean("review_hold")
                  || !r.getBoolean("diagnostic_only")
                  || r.getObject("owner_id") != null)
                throw new AccountException(409, "진단 문항을 사용할 수 없어요.");
              String json = r.getString("package_json"), hash = r.getString("package_sha256");
              if (!JudgeJson.hash(JudgeJson.canonical(JudgeJson.parse(json))).equals(hash))
                throw new AccountException(409, "진단 문항의 검증 정보를 확인해야 해요.");
              return new BankItem(
                  r.getInt("position"),
                  r.getString("category"),
                  r.getString("difficulty"),
                  r.getString("problem_version"),
                  json,
                  hash,
                  r.getString("runtime_image"),
                  r.getString("runner_policy"),
                  r.getString("rubric_json"),
                  r.getString("time_limits_json"));
            })
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @Transactional
  public List<Bank> reassessmentOptions(String name, UUID source) {
    UUID user = owner(name);
    var original = view(user, source);
    if (!original.status().equals("COMPLETED"))
      throw new AccountException(409, "완료한 진단에서 재평가를 선택해 주세요.");
    var result = new java.util.ArrayList<Bank>();
    for (String bank : repository.reassessmentOptionsDiagnosticBank()) {
      var available = new java.util.ArrayList<String>();
      for (String category : original.items().stream().map(Item::category).distinct().toList()) {
        try {
          var pair = bankItems(bank, List.of(category));
          if (pair.size() != 2 || pair.stream().map(BankItem::difficulty).distinct().count() != 2)
            continue;
          validateReassessment(user, source, pair);
          available.add(category);
        } catch (AccountException unavailable) {
          /* Ineligible content must not appear as a selectable pair. */
        }
      }
      if (!available.isEmpty()) result.add(new Bank(bank, available, available.size() * 2));
    }
    return result;
  }

  @Transactional
  public View reportExposure(String name, UUID session, UUID item) {
    UUID user = owner(name);
    var saved = view(user, session);
    var chosen =
        saved.items().stream()
            .filter(i -> i.id().equals(item))
            .findFirst()
            .orElseThrow(() -> new AccountException(404, "진단 문항을 찾을 수 없어요."));
    if (chosen.externallySeen()) return saved;
    if (saved.sourceSessionId() == null
        || chosen.pending() > 0
        || (!saved.status().equals("COMPLETED")
            && (!saved.status().equals("ACTIVE")
                || saved.current() == null
                || !saved.current().itemId().equals(item))))
      throw new AccountException(409, "현재 재평가 문항 또는 완료한 재평가에서 채점이 끝난 뒤 알려 주세요.");
    repository.reportExposureAiBudgetLock();
    repository.reportExposureDiagnosticItem(item);
    repository.reportExposureDiagnosticSession(session);
    return view(user, session);
  }

  // Version labels do not make an otherwise identical package independent evidence.
  public static String contentHash(String json) {
    var content = (com.fasterxml.jackson.databind.node.ObjectNode) JudgeJson.parse(json).deepCopy();
    content.remove("version");
    return JudgeJson.hash(JudgeJson.canonical(content));
  }

  private String validateReassessment(UUID user, UUID source, List<BankItem> targets) {
    var original = view(user, source);
    if (!original.status().equals("COMPLETED"))
      throw new AccountException(409, "원래 진단을 마친 뒤 재평가를 선택해 주세요.");
    // Legacy snapshots are also considered assigned, even if the statement was never opened.
    var seen =
        repository
            .validateReassessmentDiagnosticItem(user)
            .map(Diagnostics::contentHash)
            .collect(java.util.stream.Collectors.toSet());
    seen.addAll(repository.validateReassessmentDiagnosticExposure(user));
    var mapping = JudgeJson.JSON.createArrayNode();
    var selected = new java.util.HashSet<String>();
    for (var target : targets) {
      String content = contentHash(target.json());
      if (seen.contains(content) || !selected.add(content))
        throw new AccountException(409, "이미 배정된 문제 또는 중복 문제는 새 재평가에 사용할 수 없어요.");
      var prior =
          original.items().stream()
              .filter(
                  i ->
                      i.category().equals(target.category())
                          && i.difficulty().equals(target.difficulty()))
              .findFirst()
              .orElseThrow(() -> new AccountException(409, "원래 진단에 포함된 분야의 대응 문항을 선택해 주세요."));
      if (repository.validateReassessmentProblemVersion(prior.problemVersion()) != 1)
        throw new AccountException(409, "원래 진단 문항의 재검토가 끝난 뒤 진행해 주세요.");
      String sourceHash = repository.validateReassessmentDiagnosticItem2(prior.id());
      if (repository.validateReassessmentDiagnosticReassessmentPair(
              prior.problemVersion(), target.version(), sourceHash, target.hash())
          != 1) throw new AccountException(409, "검토된 A/B 대응 문항이 아직 준비되지 않았어요.");
      mapping
          .addObject()
          .put("sourceItemId", prior.id().toString())
          .put("sourceVersion", prior.problemVersion())
          .put("sourceHash", sourceHash)
          .put("targetVersion", target.version())
          .put("targetHash", target.hash())
          .put("category", target.category())
          .put("difficulty", target.difficulty());
    }
    return JudgeJson.canonical(mapping);
  }

  public record BankItem(
      int position,
      String category,
      String difficulty,
      String version,
      String json,
      String hash,
      String image,
      String policy,
      String rubric,
      String limits) {}

  @Transactional
  public View detail(String name, UUID id) {
    return view(owner(name), id);
  }

  @Transactional
  public List<View> history(String name) {
    UUID user = owner(name);
    return repository.historyDiagnosticSession(user).map(id -> view(user, id)).toList();
  }

  @Transactional
  public View state(String name, UUID id, String target) {
    UUID user = owner(name);
    View saved = view(user, id);
    if (!List.of("PAUSED", "ACTIVE").contains(target))
      throw new AccountException(400, "진단 상태를 확인해 주세요.");
    if (!saved.status().equals("COMPLETED")) repository.stateDiagnosticSession(target, id);
    return view(user, id);
  }

  /**
   * Ends an open session: every unfinished item is recorded as SKIPPED (unassessed, never weak).
   */
  @Transactional
  public View finish(String name, UUID session) {
    UUID user = owner(name);
    View saved = view(user, session);
    if (saved.status().equals("COMPLETED")) return saved; // Replay after completion is a no-op.
    if (saved.items().stream().anyMatch(i -> i.pending() > 0))
      throw new AccountException(409, "진행 중인 정식 채점이 끝난 뒤 진단을 끝내 주세요.");
    repository.finishDiagnosticItem(session);
    return view(user, session);
  }

  @Transactional
  public View skip(String name, UUID session, UUID item) {
    return skip(name, session, item, null);
  }

  @Transactional
  public View skip(String name, UUID session, UUID item, String reason) {
    if (reason != null && !List.of("NOT_SURE", "NO_TIME", "OTHER", "UNSPECIFIED").contains(reason))
      throw new AccountException(400, "건너뛰는 이유를 확인해 주세요.");
    UUID user = owner(name);
    View saved = view(user, session);
    var chosen =
        saved.items().stream()
            .filter(i -> i.id().equals(item))
            .findFirst()
            .orElseThrow(() -> new AccountException(404, "진단 문항을 찾을 수 없어요."));
    if (chosen.status().equals("SKIPPED")) {
      if (reason != null && !reason.equals(chosen.skipReason()))
        throw new AccountException(409, "이미 저장된 건너뛰기 이유와 달라요.");
      return saved; // Retrying skip never skips the next item or overwrites its reason.
    }
    if (!saved.status().equals("ACTIVE")
        || saved.current() == null
        || !saved.current().itemId().equals(item)
        || chosen.pending() > 0) throw new AccountException(409, "현재 문항과 진행 중인 채점을 확인해 주세요.");
    repository.skipDiagnosticItem(reason == null ? "UNSPECIFIED" : reason, item);
    return view(user, session);
  }

  // Caller owns app_user lock, shared with ordinary submission admission. No judge->owner lock
  // inversion.
  public Snapshot admit(UUID user, UUID item, String version, boolean run) {
    var session =
        repository
            .admitDiagnosticSession(item, user)
            .orElseThrow(() -> new AccountException(404, "진단 문항을 찾을 수 없어요."));
    View saved = view(user, session);
    if (!saved.status().equals("ACTIVE")
        || saved.current() == null
        || !saved.current().itemId().equals(item)
        || !saved.current().problemVersion().equals(version))
      throw new AccountException(409, "진행 중인 진단 문항을 확인해 주세요.");
    Item current =
        saved.items().stream().filter(i -> i.id().equals(item)).findFirst().orElseThrow();
    if (!run && (current.pending() > 0 || current.attempts() >= 5))
      throw new AccountException(409, "진행 중인 채점이 끝난 뒤 제출해 주세요.");
    return repository.admitDiagnosticItem(
        item,
        (r, n) ->
            new Snapshot(
                r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
  }

  private View view(UUID user, UUID id) {
    var session =
        repository
            .viewDiagnosticSession(
                id, user, (r, n) -> new String[] {r.getString(1), r.getString(2)})
            .orElseThrow(() -> new AccountException(404, "진단 기록을 찾을 수 없어요."));
    // Freeze admitted results during reconciliation/skip. Judge completion only locks
    // jobs/attempts,
    // never app_user, so this owner->job ordering does not introduce an inverse lock cycle.
    repository.viewJudgeJob(id);
    // Derived from distinct submissions, never Runner attempts. Persist outcomes for restart and
    // read-only history.
    repository.viewDiagnosticItem(id);
    repository.viewDiagnosticItem2(id);
    var items =
        repository.viewSubmission(
            id,
            (r, n) ->
                new Item(
                    r.getObject("id", UUID.class),
                    r.getInt("position"),
                    r.getString("category"),
                    r.getString("difficulty"),
                    r.getString("problem_version"),
                    r.getString("status"),
                    r.getInt("attempts"),
                    r.getInt("pending"),
                    r.getBoolean("externally_seen"),
                    r.getString("skip_reason")));
    var current = items.stream().filter(i -> i.status().equals("OPEN")).findFirst();
    String status = session[1];
    if (current.isEmpty()) {
      repository.viewDiagnosticSession2(id);
      status = "COMPLETED";
    }
    Question question =
        current
            .map(
                i -> {
                  JsonNode p = JudgeJson.parse(repository.viewDiagnosticItem3(i.id()));
                  return new Question(
                      i.id(),
                      i.problemVersion(),
                      p.path("title").asText(),
                      p.path("statement").asText(),
                      p.path("tests").path(0).path("input").asText(),
                      p.path("tests").path(0).path("output").asText(),
                      examples(p.path("tests")),
                      LanguageProfiles.options(
                          repository
                              .viewDiagnosticItem4(i.id(), (r, n) -> r.getString(1))
                              .orElse(null)),
                      p.has("api") ? CallablePrograms.publicBundle(p.path("api")) : null);
                })
            .orElse(null);
    UUID source =
        repository.viewDiagnosticSession3(id, (r, n) -> new UUID[] {r.getObject(1, UUID.class)})[0];
    var created = repository.viewDiagnosticSession4(id);
    boolean repeat =
        exam(session[0]) && repository.viewDiagnosticSession5(user, session[0], created) > 0;
    return new View(id, session[0], status, items, question, source, created, repeat);
  }
}
