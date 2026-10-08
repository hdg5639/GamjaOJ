package dev.gamjaoj.learning.service;

import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.learning.dto.LearningProgressDtos.Category;
import dev.gamjaoj.learning.dto.LearningProgressDtos.Dashboard;
import dev.gamjaoj.learning.dto.LearningProgressDtos.Day;
import dev.gamjaoj.learning.dto.LearningProgressDtos.Reflection;
import dev.gamjaoj.learning.dto.LearningProgressDtos.ReflectionRequest;
import dev.gamjaoj.learning.dto.LearningProgressDtos.Suggestion;
import dev.gamjaoj.learning.repository.LearningProgressRepository;
import dev.gamjaoj.problem.domain.ProblemCategories;
import dev.gamjaoj.problem.domain.ProblemTitles;
import dev.gamjaoj.problem.domain.ThinkingProfile;
import dev.gamjaoj.problem.service.ThinkingDifficulty;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Personal observations, not an inferred skill score. Reads never enqueue model work. */
@org.springframework.stereotype.Service
public class LearningProgress {
  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private final LearningProgressRepository repository;
  private final Submissions submissions;

  public LearningProgress(LearningProgressRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  private record Candidate(
      String version,
      String title,
      String category,
      String difficulty,
      ThinkingProfile.Profile thinking) {}

  private record Attempt(String version, String category, boolean accepted) {}

  public Dashboard learning(String username) {
    return dashboard(username, LocalDate.now(ZONE));
  }

  public Dashboard dashboard(String username, LocalDate today) {
    UUID owner = submissions.owner(username, false);
    LocalDate start = today.minusDays(364);
    var since = start.atStartOfDay(ZONE).toOffsetDateTime();
    var until = today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
    var activity =
        repository.dashboardSubmission(
            owner,
            since,
            until,
            (r, n) ->
                Map.entry(
                    r.getObject(1, OffsetDateTime.class).atZoneSameInstant(ZONE).toLocalDate(),
                    r.getString(2)));
    Map<LocalDate, Set<String>> solvedByDay = new HashMap<>();
    for (var row : activity)
      solvedByDay.computeIfAbsent(row.getKey(), k -> new HashSet<>()).add(row.getValue());
    List<Day> days = new ArrayList<>();
    int active = 0, longest = 0, run = 0;
    for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
      int count = solvedByDay.getOrDefault(date, Set.of()).size();
      days.add(new Day(date, count));
      if (count > 0) {
        active++;
        run++;
        longest = Math.max(longest, run);
      } else run = 0;
    }
    // An unfinished today does not erase the streak completed through yesterday.
    LocalDate streakEnd = solvedByDay.containsKey(today) ? today : today.minusDays(1);
    int streak = 0;
    while (!streakEnd.isBefore(start) && solvedByDay.containsKey(streakEnd)) {
      streak++;
      streakEnd = streakEnd.minusDays(1);
    }
    var catalog =
        repository.dashboardProblemVersion(
            owner,
            (r, n) ->
                new Candidate(
                    r.getString(1),
                    ProblemTitles.display(JudgeJson.parse(r.getString(2))),
                    ProblemCategories.display(r.getString(3)),
                    r.getString(4),
                    ThinkingDifficulty.read(r)));
    var attempts =
        repository.dashboardSubmission2(
            owner,
            today.minusDays(89).atStartOfDay(ZONE).toOffsetDateTime(),
            until,
            (r, n) ->
                new Attempt(
                    r.getString(1),
                    ProblemCategories.display(r.getString(2)),
                    "AC".equals(r.getString(3))));
    Map<String, Set<String>> tried = new TreeMap<>(), accepted = new HashMap<>();
    Map<String, Integer> available = new HashMap<>();
    for (var p : catalog) {
      tried.computeIfAbsent(p.category(), k -> new HashSet<>());
      available.merge(p.category(), 1, Integer::sum);
    }
    for (var a : attempts) {
      tried.computeIfAbsent(a.category(), k -> new HashSet<>()).add(a.version());
      if (a.accepted())
        accepted.computeIfAbsent(a.category(), k -> new HashSet<>()).add(a.version());
    }
    List<Category> categories =
        tried.entrySet().stream()
            .map(
                e ->
                    new Category(
                        e.getKey(),
                        e.getValue().size(),
                        accepted.getOrDefault(e.getKey(), Set.of()).size(),
                        available.getOrDefault(e.getKey(), 0)))
            .sorted(
                Comparator.comparingInt(Category::attempted)
                    .reversed()
                    .thenComparing(Category::category))
            .toList();
    int total = categories.stream().mapToInt(Category::attempted).sum();
    String dominant =
        total >= 5
                && !categories.isEmpty()
                && categories.getFirst().attempted() * 100L >= total * 60L
            ? categories.getFirst().category()
            : null;
    Set<String> solved = new HashSet<>(repository.dashboardSubmission3(owner));
    List<Suggestion> explore = new ArrayList<>();
    Set<String> selectedCategories = new HashSet<>();
    var candidates =
        catalog.stream()
            .filter(p -> !solved.contains(p.version()))
            .sorted(
                Comparator.comparingInt(
                        (Candidate p) -> tried.getOrDefault(p.category(), Set.of()).size())
                    .thenComparingInt(p -> p.thinking() != null ? p.thinking().layer() : 10)
                    .thenComparing(Candidate::version))
            .toList();
    for (var p : candidates) {
      if (!selectedCategories.add(p.category())) continue;
      int count = tried.getOrDefault(p.category(), Set.of()).size();
      explore.add(
          new Suggestion(
              p.version(),
              p.title(),
              p.category(),
              p.difficulty(),
              count == 0
                  ? "최근 90일 동안 도전하지 않은 분야예요."
                  : "최근 90일에 이 분야의 " + count + "문제에 도전했어요. 다른 유형도 함께 연습해 봐요.",
              null,
              p.thinking()));
      if (explore.size() == 4) break;
    }
    var revisit =
        repository.dashboardProblemReflection(
            owner,
            owner,
            (r, n) ->
                new Suggestion(
                    r.getString(1),
                    ProblemTitles.display(JudgeJson.parse(r.getString(2))),
                    ProblemCategories.display(r.getString(3)),
                    r.getString(4),
                    "REVISIT".equals(r.getString(5)) ? "다시 풀어야 한다고 남긴 문제예요." : "조금 애매하다고 남긴 문제예요.",
                    r.getString(5),
                    ThinkingDifficulty.read(r)));
    return new Dashboard(
        start,
        today,
        ZONE.getId(),
        days,
        active,
        streak,
        longest,
        total,
        dominant,
        categories,
        explore,
        revisit);
  }

  public Reflection reflection(String username, String problemVersion) {
    return find(submissions.owner(username, false), problemVersion);
  }

  private Reflection find(UUID owner, String version) {
    UUID latest =
        repository
            .findSubmission(owner, version)
            .orElseThrow(() -> new AccountException(404, "정답을 맞힌 일반 문제의 기록을 선택해 주세요."));
    return repository
        .findProblemReflection(
            owner,
            version,
            (r, n) ->
                new Reflection(
                    version,
                    r.getObject(1, UUID.class),
                    latest,
                    r.getString(2),
                    r.getString(3),
                    r.getObject(4, OffsetDateTime.class)))
        .orElse(new Reflection(version, null, latest, null, "", null));
  }

  public @Transactional Reflection reflect(String username, ReflectionRequest request) {
    UUID owner = submissions.owner(username, true);
    String version =
        repository
            .reflectSubmission(owner, request.submissionId())
            .orElseThrow(() -> new AccountException(404, "본인이 정답을 맞힌 일반 문제 제출을 선택해 주세요."));
    if (request.confidence() == null) {
      repository.reflectProblemReflection(owner, version);
    } else {
      int changed =
          repository.reflectProblemReflection2(
              request.submissionId(), request.confidence(), request.note().strip(), owner, version);
      if (changed == 0)
        repository.reflectProblemReflection3(
            owner, version, request.submissionId(), request.confidence(), request.note().strip());
    }
    return find(owner, version);
  }
}
