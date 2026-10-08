package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.gamjaoj.judge.dto.RunDtos;
import dev.gamjaoj.judge.dto.SubmissionDtos;
import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.learning.service.PracticeFollowups;
import dev.gamjaoj.learning.service.TrainingSessions;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:followups;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "gamjaoj.invite-code=test",
      "gamjaoj.submissions-enabled=true",
      "AI_API_ENABLED=false",
      "AI_POLL_MS=3600000"
    })
@AutoConfigureMockMvc
class PracticeFollowupIntegrationTest {
  @Autowired JdbcClient jdbc;
  @Autowired PracticeFollowups followups;
  @Autowired Submissions submissions;
  @Autowired TrainingSessions training;
  @Autowired MockMvc mvc;
  UUID owner, analysis;
  String target;

  @BeforeEach
  void setup() {
    jdbc.sql("DELETE FROM practice_followup").update();
    jdbc.sql("DELETE FROM submission").update();
    jdbc.sql("DELETE FROM training_session").update();
    jdbc.sql("DELETE FROM generation_spec_draft").update();
    jdbc.sql("DELETE FROM generation_job").update();
    jdbc.sql("DELETE FROM problem_version WHERE owner_id IS NOT NULL").update();
    jdbc.sql("DELETE FROM app_user").update();
    jdbc.sql("UPDATE problem_version SET review_hold=false").update();
    owner = UUID.randomUUID();
    for (String name : new String[] {"alice", "bob"})
      jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)")
          .param(name.equals("alice") ? owner : UUID.randomUUID())
          .param(name)
          .param("unused")
          .param(name)
          .update();
    var first =
        submissions.submit(
            "alice", UUID.randomUUID(), new SubmissionDtos.Request("total-v1", "class Main {}"));
    finish(first.id(), "WA");
    analysis = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO"
                + " ai_task(id,user_id,submission_id,kind,cache_key,settings_json,input_json,status,result_json)"
                + " VALUES (?,?,?,'ANALYSIS',?,'{}','{}','COMPLETED',?)")
        .param(analysis)
        .param(owner)
        .param(first.id())
        .param(JudgeJson.hash(analysis.toString()))
        .param(AiIntegrationTest.feedback().toString())
        .update();
    target = candidate(owner, "overflow");
  }

  String candidate(UUID owner, String focus) {
    UUID id = UUID.randomUUID();
    String version = "generated-" + id + "-r0";
    jdbc.sql(
            "INSERT INTO generation_job(id,owner_id,template_id,status,model,effort,focus) VALUES"
                + " (?,?,'sequence-sum-v1','READY','fixture','low',?)")
        .param(id)
        .param(owner)
        .param(focus)
        .update();
    jdbc.sql(
            "INSERT INTO"
                + " problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id)"
                + " SELECT ?,package_json,package_sha256,runtime_image,runner_policy,true,? FROM"
                + " problem_version WHERE id='total-v1'")
        .param(version)
        .param(owner)
        .update();
    return version;
  }

  void finish(UUID submission, String verdict) {
    jdbc.sql(
            "UPDATE judge_job SET"
                + " status='FINISHED',verdict=?,result_json='{}',result_sha256=?,finished_at=CURRENT_TIMESTAMP"
                + " WHERE submission_id=?")
        .param(verdict)
        .param(JudgeJson.hash("{}"))
        .param(submission)
        .update();
  }

  @Test
  void confirmedGoalToNewSessionToExplicitUnassistedAcIsDurableAndIdempotent() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    assertThat(followups.confirm("alice", analysis, 0, "overflow").id()).isEqualTo(goal.id());
    assertThat(goal.candidates())
        .extracting(PracticeFollowups.Candidate::version)
        .containsExactly(target);
    assertThat(jdbc.sql("SELECT count(*) FROM ai_attempt").query(Integer.class).single()).isZero();
    var started = followups.start("alice", goal.id(), target);
    assertThat(started.sessionId()).isEqualTo(goal.id());
    assertThat(followups.start("alice", goal.id(), target).sessionId()).isEqualTo(goal.id());
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false))
        .isInstanceOf(AccountException.class);
    var submission =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(target, "class Main {}", started.sessionId()));
    training.end("alice", started.sessionId(), "경계값 재확인");
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("WAITING_JUDGE");
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false))
        .isInstanceOf(AccountException.class);
    finish(submission.id(), "AC");
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("AWAITING_REFLECTION");
    var reflected = followups.reflect("alice", goal.id(), false);
    assertThat(reflected.status()).isEqualTo("SELF_REPORTED_UNASSISTED_AC");
    assertThat(reflected.reviewedSubmissionId()).isEqualTo(submission.id());
    assertThat(followups.list("alice").getFirst().status()).isEqualTo(reflected.status());
    assertThat(followups.reflect("alice", goal.id(), false)).isEqualTo(reflected);
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), true))
        .isInstanceOf(AccountException.class);
    assertThat(submissions.detail("alice", submission.id()).verdict()).isEqualTo("AC");
  }

  @Test
  void candidatesArePrivateUnsolvedCompatibleAndRecheckedAtAdmission() throws Exception {
    String foreign = candidate(submissions.owner("bob", false), "overflow"),
        wrong = candidate(owner, "edge-cases");
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    assertThat(goal.candidates())
        .extracting(PracticeFollowups.Candidate::version)
        .doesNotContain(foreign, wrong, "total-v1");
    mvc.perform(get("/api/practice-followups/" + goal.id()).with(user("bob")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/practice-followups/" + goal.id() + "/start")
                .with(user("alice"))
                .contentType("application/json")
                .content("{\"problemVersion\":\"" + target + "\"}"))
        .andExpect(status().isForbidden());
    assertThatThrownBy(() -> followups.confirm("bob", analysis, 0, "overflow"))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> followups.start("bob", goal.id(), target))
        .isInstanceOf(AccountException.class);
    var solved =
        submissions.submit(
            "alice", UUID.randomUUID(), new SubmissionDtos.Request(target, "class Main {}"));
    finish(solved.id(), "AC");
    assertThat(followups.detail("alice", goal.id()).candidates()).isEmpty();
    assertThatThrownBy(() -> followups.start("alice", goal.id(), target))
        .isInstanceOf(AccountException.class);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id='total-v1'").update();
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("HELD");
    assertThatThrownBy(() -> followups.generate("alice", goal.id()))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void generationIsExplicitUsesOnlyConfirmedStepAndReusesTheSameJob() {
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(target).update();
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    assertThat(goal.candidates()).isEmpty();
    int count = jdbc.sql("SELECT count(*) FROM generation_job").query(Integer.class).single();
    var generated = followups.generate("alice", goal.id());
    assertThat(generated.generationStatus()).isEqualTo("QUEUED");
    assertThat(followups.generate("alice", goal.id()).generationStatus()).isEqualTo("QUEUED");
    assertThat(jdbc.sql("SELECT count(*) FROM generation_job").query(Integer.class).single())
        .isEqualTo(count + 1);
    String context =
        jdbc.sql("SELECT learning_context_json FROM generation_job WHERE id=?")
            .param(goal.id())
            .query(String.class)
            .single();
    assertThat(JudgeJson.parse(context).path("nextSteps").get(0).asText()).isEqualTo(goal.goal());
  }

  @Test
  void runSuccessDoesNotProveTheFinalGoalAndHeldTargetBlocksReflection() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    followups.start("alice", goal.id(), target);
    var run =
        submissions.run(
            "alice",
            UUID.randomUUID(),
            new RunDtos.Request(target, "class Main {}", "", goal.id()));
    finish(run.id(), "OK");
    training.end("alice", goal.id(), "");
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("NEEDS_PRACTICE");
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false))
        .isInstanceOf(AccountException.class);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id=?").param(target).update();
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("HELD");
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void finalWaAfterAcNeedsPracticeAndHelpRemainsExplicit() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    followups.start("alice", goal.id(), target);
    var first =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(target, "class Main {}", goal.id()));
    finish(first.id(), "AC");
    var last =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(target, "class Main { }", goal.id()));
    finish(last.id(), "WA");
    jdbc.sql("UPDATE submission SET created_at=DATEADD('SECOND',1,CURRENT_TIMESTAMP) WHERE id=?")
        .param(last.id())
        .update();
    training.end("alice", goal.id(), "");
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("NEEDS_PRACTICE");
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false))
        .isInstanceOf(AccountException.class);
    finish(last.id(), "AC");
    assertThat(followups.reflect("alice", goal.id(), true).status()).isEqualTo("AC_WITH_HELP");
  }

  @Test
  void unmappedRulesNeverBorrowMatchingTagsAndUseExistingIndependentDraftFlow() {
    jdbc.sql("UPDATE submission SET problem_version='sum-v1'").update();
    var goal = followups.confirm("alice", analysis, 0, "custom");
    assertThat(goal.candidates()).isEmpty();
    int tasks = jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single();
    assertThat(followups.generate("alice", goal.id()).generationStatus()).isEqualTo("QUEUED");
    assertThat(followups.generate("alice", goal.id()).generationStatus()).isEqualTo("QUEUED");
    assertThat(jdbc.sql("SELECT count(*) FROM ai_task").query(Integer.class).single())
        .isEqualTo(tasks);
    assertThat(
            jdbc.sql("SELECT request_text FROM generation_spec_draft WHERE id=?")
                .param(goal.id())
                .query(String.class)
                .single())
        .contains(goal.goal())
        .doesNotContain("class Main");
  }

  @Test
  void repeatPreservesFailedAttemptAndFencesAllStaleRoundMutations() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    followups.start("alice", goal.id(), target);
    assertThatThrownBy(() -> followups.repeat("alice", goal.id(), 1))
        .isInstanceOf(AccountException.class);
    var first =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(target, "class Main {}", goal.id()));
    training.end("alice", goal.id(), "첫 시도");
    assertThatThrownBy(() -> followups.repeat("alice", goal.id(), 1))
        .isInstanceOf(AccountException.class);
    finish(first.id(), "WA");
    var repeated = followups.repeat("alice", goal.id(), 1);
    assertThat(repeated.round()).isEqualTo(2);
    assertThat(repeated.status()).isEqualTo("READY_TO_PRACTICE");
    assertThat(repeated.attempts()).hasSize(1);
    assertThat(repeated.attempts().getFirst().sessionId()).isEqualTo(goal.id());
    assertThat(repeated.attempts().getFirst().status()).isEqualTo("NEEDS_PRACTICE");
    assertThat(repeated.candidates())
        .extracting(PracticeFollowups.Candidate::version)
        .contains(target);
    assertThat(followups.repeat("alice", goal.id(), 1)).isEqualTo(repeated);
    assertThatThrownBy(() -> followups.start("alice", goal.id(), target, 1))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> followups.generate("alice", goal.id(), 1))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> followups.reflect("alice", goal.id(), false, 1))
        .isInstanceOf(AccountException.class);
    var second = followups.start("alice", goal.id(), target, 2);
    assertThat(second.sessionId()).isNotEqualTo(goal.id());
    assertThat(followups.repeat("alice", goal.id(), 1).sessionId()).isEqualTo(second.sessionId());
    assertThatThrownBy(() -> followups.repeat("alice", goal.id(), 2))
        .isInstanceOf(AccountException.class);
    assertThat(training.detail("alice", goal.id()).session().note()).isEqualTo("첫 시도");
    assertThat(submissions.detail("alice", first.id()).verdict()).isEqualTo("WA");
    assertThatThrownBy(() -> followups.repeat("bob", goal.id(), 2))
        .isInstanceOf(AccountException.class);
  }

  @Test
  void assistedSuccessRemainsInHistoryAndNeedsDifferentUnsolvedProblem() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    followups.start("alice", goal.id(), target);
    var first =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(target, "class Main {}", goal.id()));
    finish(first.id(), "AC");
    training.end("alice", goal.id(), "");
    assertThatThrownBy(() -> followups.repeat("alice", goal.id(), 1))
        .isInstanceOf(AccountException.class);
    followups.reflect("alice", goal.id(), true);
    String next = candidate(owner, "overflow");
    var repeated = followups.repeat("alice", goal.id(), 1);
    assertThat(repeated.attempts().getFirst().usedHelp()).isTrue();
    assertThat(repeated.attempts().getFirst().reviewedSubmissionId()).isEqualTo(first.id());
    assertThat(repeated.candidates())
        .extracting(PracticeFollowups.Candidate::version)
        .containsExactly(next);
    var started = followups.start("alice", goal.id(), next, 2);
    var second =
        submissions.submit(
            "alice",
            UUID.randomUUID(),
            new SubmissionDtos.Request(next, "class Main {}", started.sessionId()));
    finish(second.id(), "AC");
    training.end("alice", started.sessionId(), "");
    var reflected = followups.reflect("alice", goal.id(), false, 2);
    assertThat(reflected.status()).isEqualTo("SELF_REPORTED_UNASSISTED_AC");
    assertThat(reflected.attempts().getFirst().status()).isEqualTo("AC_WITH_HELP");
    assertThat(reflected.reviewedSubmissionId()).isEqualTo(second.id());
    assertThat(followups.list("alice").getFirst()).isEqualTo(reflected);
  }

  @Test
  void repeatGenerationUsesDurableNewRoundIdAndHeldProblemCannotRepeat() {
    var goal = followups.confirm("alice", analysis, 0, "overflow");
    followups.start("alice", goal.id(), target);
    training.end("alice", goal.id(), "");
    followups.repeat("alice", goal.id(), 1);
    var generated = followups.generate("alice", goal.id(), 2);
    assertThat(generated.generationStatus()).isEqualTo("QUEUED");
    assertThat(followups.generate("alice", goal.id(), 2)).isEqualTo(generated);
    UUID job =
        jdbc.sql("SELECT round_id FROM practice_followup WHERE id=?")
            .param(goal.id())
            .query(UUID.class)
            .single();
    assertThat(job).isNotEqualTo(goal.id());
    assertThat(
            jdbc.sql("SELECT source_analysis_id FROM generation_job WHERE id=?")
                .param(job)
                .query(UUID.class)
                .single())
        .isEqualTo(analysis);
    jdbc.sql("UPDATE problem_version SET review_hold=true WHERE id='total-v1'").update();
    assertThat(followups.detail("alice", goal.id()).status()).isEqualTo("HELD");
    assertThatThrownBy(() -> followups.repeat("alice", goal.id(), 2))
        .isInstanceOf(AccountException.class);
    assertThatThrownBy(() -> followups.generate("alice", goal.id(), 2))
        .isInstanceOf(AccountException.class);
  }
}
