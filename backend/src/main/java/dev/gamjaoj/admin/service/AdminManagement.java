package dev.gamjaoj.admin.service;

import dev.gamjaoj.admin.config.ControlSettings;
import dev.gamjaoj.admin.dto.AdminDtos.*;
import dev.gamjaoj.admin.repository.AdminManagementRepository;
import dev.gamjaoj.ai.service.AiTasks;
import dev.gamjaoj.export.service.SolutionExports;
import dev.gamjaoj.generation.service.HybridGeneration;
import dev.gamjaoj.generation.service.HybridRuleOnboarding;
import dev.gamjaoj.learning.service.TrainingSessions;
import dev.gamjaoj.problem.domain.ProblemCategories;
import dev.gamjaoj.problem.service.ProblemReview;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminManagement {
  private final AdminManagementRepository repository;
  private final ControlSettings settings;
  private final AdminAudit audit;
  private final dev.gamjaoj.learning.service.TrainingCourses courses;
  private final dev.gamjaoj.learning.service.LearningCurricula curricula;
  private final TrainingSessions training;
  private final AiTasks ai;
  private final SolutionExports exports;
  private final HybridGeneration hybrid;
  private final HybridRuleOnboarding rules;
  private final ProblemReview review;

  public AdminManagement(
      AdminManagementRepository repository,
      ControlSettings settings,
      AdminAudit audit,
      TrainingSessions training,
      AiTasks ai,
      SolutionExports exports,
      HybridGeneration hybrid,
      HybridRuleOnboarding rules,
      ProblemReview review,
      dev.gamjaoj.learning.service.TrainingCourses courses,
      dev.gamjaoj.learning.service.LearningCurricula curricula) {
    this.courses = courses;
    this.curricula = curricula;
    this.repository = repository;
    this.settings = settings;
    this.audit = audit;
    this.training = training;
    this.ai = ai;
    this.exports = exports;
    this.hybrid = hybrid;
    this.rules = rules;
    this.review = review;
  }

  public Page list(String kind, String query, int page, int size) {
    if (page < 0 || page > 100000 || !Set.of(10, 25, 50).contains(size) || query.length() > 100)
      throw new AccountException(400, "조회 범위를 확인해 주세요.");
    Page result =
        switch (kind) {
          case "members" -> repository.members(query, page, size);
          case "problems" -> repository.problems(query, page, size);
          case "training" -> repository.training(query, page, size);
          case "plans" -> repository.plans(query, page, size);
          case "jobs" -> repository.jobs(query, page, size);
          case "audit" -> repository.audit(query, page, size);
          default -> throw new AccountException(404, "관리 목록을 찾을 수 없어요.");
        };
    for (var row : result.items()) {
      if (kind.equals("members"))
        row.put("bootstrap", settings.bootstrap(row.get("username").toString()));
      if (kind.equals("problems")) {
        row.put(
            "languages",
            dev.gamjaoj.judge.domain.LanguageProfiles.options(
                (String) row.get("time_limits_json")));
        var resources = new LinkedHashMap<String, Resources>();
        for (String language : List.of("JAVA", "CPP", "PYTHON")) {
          var profile =
              dev.gamjaoj.judge.domain.LanguageProfiles.profile(
                  language, (String) row.get("time_limits_json"));
          resources.put(
              language,
              new Resources(
                  profile.path("testWallSeconds").asDouble(),
                  profile
                      .path(profile.has("testCpuSeconds") ? "testCpuSeconds" : "testWallSeconds")
                      .asDouble(),
                  profile.path("memoryMb").asInt()));
        }
        row.put("resources", resources);
        Object raw = row.remove("package_json");
        try {
          var data = JudgeJson.JSON.readTree(raw.toString());
          if (row.get("catalog_title") == null)
            row.put("catalog_title", data.path("title").asText(row.get("id").toString()));
        } catch (Exception ignored) {
          row.putIfAbsent("catalog_title", row.get("id"));
        }
      }
    }
    return result;
  }

  public Map<String, Object> setting() {
    return repository.setting();
  }

  public Object budget() {
    return ai.budget();
  }

  public Map<String, Object> availability() {
    return Map.of(
        "banks",
        repository.banks(),
        "courses",
        repository.courses(),
        "definitions",
        courses.managedDefinitions());
  }

  @Transactional
  public void course(String actor, Course w, boolean create) {
    var versions = w.stages().stream().flatMap(stage -> stage.versions().stream()).toList();
    if (versions.size() > 120
        || new HashSet<>(versions).size() != versions.size()
        || versions.stream().anyMatch(v -> repository.courseProblem(v) != 1))
      throw new AccountException(400, "중복 없는 공개·검증 완료 문제를 최대 120개 선택해 주세요.");
    var before = repository.course(w.id());
    if (create && before.isPresent()) throw new AccountException(409, "같은 코스 ID가 있어요.");
    if (!create && before.isEmpty()) throw new AccountException(404, "코스를 찾을 수 없어요.");
    int businessRevision =
        courses.managedDefinitions().stream()
                .filter(c -> c.id().equals(w.id()))
                .mapToInt(dev.gamjaoj.learning.service.TrainingCourses.Course::revision)
                .findFirst()
                .orElse(0)
            + 1;
    var value =
        new dev.gamjaoj.learning.service.TrainingCourses.Course(
            w.id(),
            businessRevision,
            w.title().strip(),
            w.kind().strip(),
            w.summary().strip(),
            w.prerequisite().strip(),
            w.notice().strip(),
            w.stages().stream()
                .map(
                    s ->
                        new dev.gamjaoj.learning.service.TrainingCourses.Stage(
                            s.title().strip(), s.goal().strip(), s.versions()))
                .toList());
    String json = JudgeJson.JSON.valueToTree(value).toString();
    if (create) repository.createCourse(w.id(), json);
    else if (repository.course(w.id(), w.revision(), json) != 1) throw conflict();
    audit.record(
        actor,
        "COURSE_SAVE",
        w.id(),
        w.reason(),
        Map.of("new", create),
        Map.of("title", w.title(), "problems", versions.size(), "revision", businessRevision));
  }

  @Transactional
  public void endPlan(String actor, UUID id, String reason) {
    String owner =
        repository.planOwner(id).orElseThrow(() -> new AccountException(404, "맞춤 계획을 찾을 수 없어요."));
    curricula.end(owner, id, "관리자 마무리: " + reason);
    audit.record(
        actor, "PLAN_END", id.toString(), reason, Map.of("owner", owner), Map.of("ended", true));
  }

  @Transactional
  public void access(String actor, String target, Access body) {
    Map<String, Object> before =
        repository.member(target).orElseThrow(() -> new AccountException(404, "회원을 찾을 수 없어요."));
    if ((settings.bootstrap(target) || actor.equals(target))
        && (body.blocked() || !body.role().equals("ADMIN")))
      throw new AccountException(409, "운영 복구 계정과 본인 권한은 차단·해제할 수 없어요.");
    if (repository.access(target, body) != 1) throw conflict();
    repository.revoke(target);
    audit.record(
        actor,
        "MEMBER_ACCESS",
        target,
        body.reason(),
        before,
        Map.of("blocked", body.blocked(), "role", body.role()));
  }

  @Transactional
  public void revoke(String actor, String target, String reason) {
    var before =
        repository.member(target).orElseThrow(() -> new AccountException(404, "회원을 찾을 수 없어요."));
    repository.revoke(target);
    audit.record(actor, "SESSION_REVOKE", target, reason, before, Map.of("sessionsRevoked", true));
  }

  @Transactional
  public Map<String, Object> setting(String actor, Setting body) {
    var before = repository.setting();
    if (repository.setting(body) != 1) throw conflict();
    var after = repository.setting();
    audit.record(actor, "SITE_MAINTENANCE", "site", body.reason(), before, after);
    return after;
  }

  @Transactional
  public void problem(String actor, String id, Problem w) {
    if (w.tags().stream().anyMatch(t -> t.contains(",")))
      throw new AccountException(400, "태그 안에 쉼표를 넣을 수 없어요.");
    var before =
        repository.problem(id).orElseThrow(() -> new AccountException(404, "문제를 찾을 수 없어요."));
    if (Boolean.TRUE.equals(before.get("review_hold")) && w.shared())
      throw new AccountException(409, "검토 보류 문제를 새로 공개할 수 없어요.");
    if (repository.problem(
            id,
            w,
            ProblemCategories.forSave(w.category()),
            String.join(",", w.tags().stream().map(String::strip).distinct().toList()))
        != 1) throw conflict();
    if (w.thinking() != null) repository.thinking(id, w.thinking());
    var after = new LinkedHashMap<String, Object>(repository.problem(id).orElseThrow());
    if (w.thinking() != null) after.put("thinking", w.thinking());
    audit.record(actor, "PROBLEM_METADATA", id, w.reason(), before, after);
  }

  @Transactional
  public void limits(String actor, String id, Limits w) {
    if (!w.languages().keySet().equals(Set.of("JAVA", "CPP", "PYTHON"))
        || w.languages().values().stream().anyMatch(Objects::isNull))
      throw new AccountException(400, "세 언어의 제한을 모두 입력해 주세요.");
    var before =
        repository.problem(id).orElseThrow(() -> new AccountException(404, "문제를 찾을 수 없어요."));
    if (Boolean.TRUE.equals(before.get("diagnostic_only")))
      throw new AccountException(409, "진단 문제 제한은 세트 검증·보정 절차에서 변경해 주세요.");
    var json = JudgeJson.JSON.createObjectNode();
    var cpu = json.putObject("cpu");
    var memory = json.putObject("memory");
    for (var entry : w.languages().entrySet()) {
      var r = entry.getValue();
      if (r.cpuSeconds() > r.wallSeconds())
        throw new AccountException(400, "CPU 제한은 전체 실행 제한보다 클 수 없어요.");
      json.put(entry.getKey(), r.wallSeconds());
      cpu.put(entry.getKey(), r.cpuSeconds());
      memory.put(entry.getKey(), r.memoryMb());
    }
    json.put(
        "analysis",
        "OPERATOR_OVERRIDE: " + w.reason().strip() + ". 기존 측정치를 새 측정으로 주장하지 않습니다. 신규 제출에 적용됩니다.");
    try {
      dev.gamjaoj.problem.domain.ProblemTimeLimits.validate(json);
    } catch (dev.gamjaoj.shared.domain.ArtifactValidation.Invalid error) {
      throw new AccountException(400, "언어별 제한 범위와 러너 최대 메모리를 확인해 주세요.");
    }
    if (repository.limits(id, w.revision(), json.toString()) != 1) throw conflict();
    audit.record(actor, "PROBLEM_LIMITS", id, w.reason(), before, Map.of("limits", json));
  }

  @Transactional
  public void hold(String actor, String id, String reason) {
    var after = review.holdAsAdministrator(id, reason);
    audit.record(actor, "PROBLEM_HOLD", id, reason, Map.of(), Map.of("held", after.held()));
  }

  @Transactional
  public void availability(String actor, String kind, String id, Availability w) {
    if (!Set.of("bank", "course").contains(kind)) throw new AccountException(400, "대상을 확인해 주세요.");
    if (repository.availability(kind, id, w) != 1) throw conflict();
    audit.record(
        actor,
        "AVAILABILITY",
        kind + ":" + id,
        w.reason(),
        Map.of("revision", w.revision()),
        Map.of("enabled", w.enabled(), "revision", w.revision() + 1));
  }

  @Transactional
  public void endTraining(String actor, UUID id, String reason) {
    String owner =
        repository.trainingOwner(id).orElseThrow(() -> new AccountException(404, "훈련을 찾을 수 없어요."));
    training.end(owner, id, "관리자 마무리: " + reason);
    audit.record(
        actor,
        "TRAINING_END",
        id.toString(),
        reason,
        Map.of("owner", owner),
        Map.of("ended", true));
  }

  @Transactional
  public void job(String actor, String kind, UUID id, String action, String reason) {
    var before =
        repository.job(kind, id).orElseThrow(() -> new AccountException(404, "작업을 찾을 수 없어요."));
    String owner = before.get("username").toString();
    String status = before.get("status").toString();
    if (action.equals("retry")
        && ((kind.equals("AI") && Set.of("FAILED", "UNKNOWN").contains(status))
            || (kind.equals("EXPORT") && Set.of("FAILED", "RETRY").contains(status)))) {
      if (kind.equals("AI")) ai.retry(owner, id);
      else if (kind.equals("EXPORT")) exports.retry(owner, id);
      else throw new AccountException(409, "이 작업은 자동 복구·원래 검증 절차를 이용해 주세요.");
    } else if (action.equals("cancel")
        && !Set.of("FAILED", "CANCELLED", "PUBLISHED", "DEADLINE_EXCEEDED", "READY")
            .contains(status)) {
      if (kind.equals("HYBRID")) hybrid.cancel(owner, id);
      else if (kind.equals("RULE")
          && Set.of("QUEUED", "AUTHORING", "AUTHORED", "ORACLE", "QUALIFYING").contains(status))
        rules.cancel(owner, id);
      else throw new AccountException(409, "실행 중인 채점·분석을 강제로 초기화할 수 없어요.");
    } else throw new AccountException(409, "현재 상태에서 할 수 없는 작업이에요.");
    audit.record(
        actor,
        "JOB_" + action.toUpperCase(Locale.ROOT),
        kind + ":" + id,
        reason,
        before,
        repository.job(kind, id).orElseThrow());
  }

  private AccountException conflict() {
    return new AccountException(409, "다른 관리자가 수정했거나 이용 가능한 대상이 아니에요. 새로고침해 주세요.");
  }
}
