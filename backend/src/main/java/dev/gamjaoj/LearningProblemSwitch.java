package dev.gamjaoj;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
class LearningProblemSwitch {
 private final JdbcClient jdbc;private final Submissions submissions;private final DiagnosticPlans plans;private final TrainingSessions training;
 LearningProblemSwitch(JdbcClient jdbc,Submissions submissions,DiagnosticPlans plans,TrainingSessions training){this.jdbc=jdbc;this.submissions=submissions;this.plans=plans;this.training=training;}
 @Transactional
 public DiagnosticPlans.Plan switchProblem(String username,UUID key,UUID target,String version,UUID expectedActive,String note) {
  UUID owner=submissions.owner(username,true);
  var request=JudgeJson.JSON.createObjectNode().put("planId",target.toString()).put("problemVersion",version).put("note",note);
  if(expectedActive==null)request.putNull("activeSessionId");else request.put("activeSessionId",expectedActive.toString());
  String canonical=JudgeJson.canonical(request);
  var prior=jdbc.sql("SELECT user_id,request_json,result_plan_id FROM learning_problem_switch WHERE id=?").param(key).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class)}).optional();
  if(prior.isPresent()){var p=prior.get();if(!owner.equals(p[0])||!canonical.equals(p[1]))throw new AccountException(409,"같은 전환 요청의 내용이 달라요.");return plans.view(username,owner,(UUID)p[2]);}
  var selected=plans.view(username,owner,target);
  if(!List.of("READY","TRAINING_ENDED","AC_WITH_HELP","SELF_REPORTED_UNASSISTED_AC").contains(selected.status()))throw new AccountException(409,"현재 이용할 수 있는 훈련 문제를 선택해 주세요.");
  if(submissions.problems(username).stream().noneMatch(p->version.equals(p.version())&&!p.problemHeld()&&p.submissionsEnabled()))throw new AccountException(409,"선택한 문제를 지금은 풀 수 없어요.");
  UUID active=jdbc.sql("SELECT id FROM training_session WHERE user_id=? AND status='ACTIVE'").param(owner).query(UUID.class).optional().orElse(null);
  if(!Objects.equals(active,expectedActive))throw new AccountException(409,"진행 중인 훈련이 바뀌었어요. 최신 화면에서 다시 선택해 주세요.");
  if(active!=null)training.end(username,active,note);
  if(!"READY".equals(selected.status())){
   var options=plans.options(username,selected.evaluationId(),selected.observationIndex(),selected.sourceKind());
   if(!options.corrections().isEmpty())throw new AccountException(409,"정정 의견을 확인한 뒤 수동 설정으로 다음 회차를 준비해 주세요.");
   selected=plans.nextRound(username,target,options.reviewHash());
  }
  var started=plans.start(username,selected.id(),version);
  if(!"ACTIVE".equals(started.status()))throw new AccountException(409,"선택한 회차가 변경됐어요. 최신 화면에서 다시 선택해 주세요.");
  jdbc.sql("INSERT INTO learning_problem_switch(id,user_id,request_json,result_plan_id) VALUES (?,?,?,?)").param(key).param(owner).param(canonical).param(started.id()).update();
  return started;
 }
}
