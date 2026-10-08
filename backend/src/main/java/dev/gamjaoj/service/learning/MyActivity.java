package dev.gamjaoj.service.learning;

import dev.gamjaoj.domain.GrowthLevels;
import dev.gamjaoj.domain.ProblemCategories;
import dev.gamjaoj.domain.ProblemTitles;
import dev.gamjaoj.dto.MyActivityDtos.Page;
import dev.gamjaoj.dto.MyActivityDtos.Problem;
import dev.gamjaoj.dto.MyActivityDtos.Summary;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.learning.MyActivityRepository;
import dev.gamjaoj.service.judge.Submissions;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;

@org.springframework.stereotype.Service
public class MyActivity {
  private final MyActivityRepository repository;
  private final Submissions submissions;

  public MyActivity(MyActivityRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  public Summary summary(String username) {
    var owner = submissions.owner(username, false);
    return repository.summarySubmission(
        owner, (r, n) -> new Summary(r.getLong(1), r.getLong(2), r.getLong(3)));
  }

  public GrowthLevels.Growth growth(String username) {
    var owner = submissions.owner(username, false);
    var solved =
        repository.growthProblemVersion(
            owner,
            owner,
            (r, n) ->
                new GrowthLevels.Solved(
                    r.getString(1),
                    ProblemCategories.display(r.getString(2)),
                    r.getObject(3, Integer.class),
                    r.getString(4)));
    return GrowthLevels.calculate(solved);
  }

  public Page problems(String username, int page) {
    if (page < 0 || page > 100000) throw new AccountException(400, "잘못된 페이지예요.");
    var owner = submissions.owner(username, false);
    long count = repository.problemsSubmission(owner);
    var items =
        repository.problemsSubmission2(
            owner,
            page * 20,
            (r, n) ->
                new Problem(
                    r.getString(1),
                    ProblemTitles.display(JudgeJson.parse(r.getString(2))),
                    r.getLong(5),
                    r.getLong(6),
                    r.getObject(7, OffsetDateTime.class),
                    r.getBoolean(3),
                    r.getBoolean(4),
                    ProblemCategories.display(r.getString(8)),
                    r.getBoolean(3) ? null : r.getString(9),
                    r.getBoolean(3) ? null : r.getString(10)));
    return new Page(items, count);
  }
}
