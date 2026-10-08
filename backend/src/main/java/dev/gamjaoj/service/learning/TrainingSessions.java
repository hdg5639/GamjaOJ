package dev.gamjaoj.service.learning;

import dev.gamjaoj.dto.TrainingSessionDtos;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.learning.TrainingSessionsRepository;
import dev.gamjaoj.service.judge.Submissions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingSessions {
  private final TrainingSessionsRepository repository;
  private final Submissions submissions;

  public TrainingSessions(TrainingSessionsRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  public record View(
      UUID id,
      String problemVersion,
      String goal,
      String note,
      String status,
      OffsetDateTime startedAt,
      OffsetDateTime endedAt,
      int submissions,
      int accepted,
      int runs,
      int pending,
      boolean problemHeld) {}

  public record Detail(View session, List<TrainingSessionDtos.Entry> entries) {}

  @Transactional
  public View start(String username, UUID id, TrainingSessionDtos.Start request) {
    UUID user = submissions.owner(username, true);
    var previous = repository.startTrainingSession(id, user);
    if (previous.isPresent()) {
      View saved = find(user, id);
      if (!saved.problemVersion().equals(request.problemVersion())
          || !saved.goal().equals(request.goal()))
        throw new AccountException(409, "같은 요청 키로 다른 훈련을 시작할 수 없어요.");
      return saved;
    }
    if (repository.startTrainingSession2(id) > 0)
      throw new AccountException(409, "새 요청 키로 다시 시작해 주세요.");
    if (repository.startTrainingSession3(user) > 0)
      throw new AccountException(409, "진행 중인 훈련을 먼저 마쳐 주세요.");
    if (repository.startProblemVersion(request.problemVersion(), user) == 0)
      throw new AccountException(404, "훈련할 수 있는 문제 버전이 아니에요.");
    repository.startTrainingSession4(id, user, request.problemVersion(), request.goal(), user);
    return find(user, id);
  }

  @Transactional
  public View end(String username, UUID id, String note) {
    UUID user = submissions.owner(username, true);
    View saved = find(user, id);
    if (saved.status().equals("ENDED")) {
      if (!saved.note().equals(note))
        throw new AccountException(409, "이미 저장한 마무리 메모와 달라요. 기록을 다시 확인해 주세요.");
      return saved;
    }
    repository.endTrainingSession(note, id, user);
    return find(user, id);
  }

  public List<View> history(String username) {
    UUID user = submissions.owner(username, false);
    return repository.historyTrainingSession(user).map(id -> find(user, id)).toList();
  }

  public Detail detail(String username, UUID id) {
    UUID user = submissions.owner(username, false);
    View view = find(user, id);
    var entries = repository.detailSubmission(id, user);
    return new Detail(view, entries);
  }

  private View find(UUID user, UUID id) {
    return repository
        .findSubmission(
            id,
            user,
            (r, n) ->
                new View(
                    id,
                    r.getString("problem_version"),
                    r.getString("goal"),
                    r.getString("note"),
                    r.getString("status"),
                    r.getObject("started_at", OffsetDateTime.class),
                    r.getObject("ended_at", OffsetDateTime.class),
                    r.getInt("submissions"),
                    r.getInt("accepted"),
                    r.getInt("runs"),
                    r.getInt("pending"),
                    r.getBoolean("review_hold")))
        .orElseThrow(() -> new AccountException(404, "훈련 기록을 찾을 수 없어요."));
  }
}
