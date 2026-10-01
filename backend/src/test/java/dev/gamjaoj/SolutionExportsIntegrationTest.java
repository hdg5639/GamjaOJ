package dev.gamjaoj;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:exports;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=",
 "EXPORTS_ENABLED=true","EXPORT_WORKER_ENABLED=false","PUBLIC_BASE_URL=https://example.test","EXPORT_TOKEN_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "EXPORT_GITHUB_CLIENT_ID=fixture-client","EXPORT_GITHUB_CLIENT_SECRET=fixture-secret","EXPORT_NOTION_CLIENT_ID=fixture-notion","EXPORT_NOTION_CLIENT_SECRET=fixture-secret",
 "HYBRID_RULE_ONBOARDING_WORKER_ENABLED=false","AI_API_ENABLED=false"})
@AutoConfigureMockMvc
class SolutionExportsIntegrationTest {
 @Autowired SolutionExports exports;@Autowired ExportVault vault;@Autowired JdbcClient jdbc;@Autowired Submissions submissions;@Autowired JudgeQueue queue;@Autowired MockMvc mvc;
 @MockitoBean ExportRemote remote;
 String name;UUID user;
 static final String SOURCE="public class Main { public static void main(String[] args) { System.out.println(3); } }";
 @BeforeEach void setup(){jdbc.sql("DELETE FROM app_user").update();name="ex"+UUID.randomUUID().toString().substring(0,8);user=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,'unused',?)").param(user).param(name).param(name).update();
  jdbc.sql("INSERT INTO execution_grant(user_id,source_sha256) VALUES (?,?)").param(user).param(JudgeJson.hash(SOURCE)).update();connect("GITHUB",true);
 }
 ObjectNode target(){return ExportRemote.obj().put("id","123").put("repo","owner/repo").put("branch","main").put("prefix","GamjaOJ").put("label","owner/repo");}
 void connect(String provider,boolean auto){jdbc.sql("INSERT INTO export_connection(id,user_id,provider,credentials,account_label,target_json,auto_enabled) VALUES (?,?,?,?,?,?,?)").param(UUID.randomUUID()).param(user).param(provider).param(vault.seal(user+":"+provider,ExportRemote.obj().put("access_token","test-token"))).param("fixture").param(target().toString()).param(auto).update();}
 UUID accepted(){var s=submissions.submit(name,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE));var a=queue.claim(UUID.randomUUID()).orElseThrow();var r=new SubmissionIntegrationTest().report(a);queue.complete(s.id(),a.token(),r);queue.complete(s.id(),a.token(),r);return s.id();}
 int count(){return jdbc.sql("SELECT count(*) FROM solution_export").query(Integer.class).single();}
 @Test void realJudgeCompletionAtomicallyEnqueuesOnceAndExternalIoHoldsNoTransaction(){
  UUID submission=accepted();assertThat(count()).isEqualTo(1);verifyNoInteractions(remote);
  when(remote.publish(anyString(),anyString(),any(),any(),any(),any(),any())).thenAnswer(call->{
   assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
   assertThat(call.getArgument(1,String.class)).isEqualTo("test-token");assertThat(call.getArgument(3,ObjectNode.class).path("source").asText()).isEqualTo(SOURCE);
   ((Runnable)call.getArgument(6)).run();return "https://github.com/owner/repo";
  });
  assertThat(exports.runOne()).isTrue();assertThat(exports.deliveries(name,submission).getFirst().status()).isEqualTo("SUCCEEDED");assertThat(exports.runOne()).isFalse();
  assertThat(exports.request(name,"GITHUB",submission)).isEqualTo(exports.deliveries(name,submission).getFirst().id());assertThat(count()).isEqualTo(1);
 }
 @Test void wrongOwnerUnacceptedCustomAndDiagnosticOnlyAreExcluded(){
  UUID first=accepted();UUID stranger=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,'stranger','unused','stranger')").param(stranger).update();
  assertThatThrownBy(()->exports.request("stranger","GITHUB",first)).isInstanceOf(AccountException.class);
  var queued=submissions.submit(name,UUID.randomUUID(),new SubmissionController.Request("sum-v1",SOURCE));assertThatThrownBy(()->exports.request(name,"GITHUB",queued.id())).isInstanceOf(AccountException.class);
  jdbc.sql("UPDATE submission SET run_input='custom',run_package='{}',run_package_sha256='0000000000000000000000000000000000000000000000000000000000000000' WHERE id=?").param(first).update();assertThatThrownBy(()->exports.request(name,"GITHUB",first)).isInstanceOf(AccountException.class);
  jdbc.sql("UPDATE submission SET run_input=NULL,run_package=NULL,run_package_sha256=NULL,example_check=true WHERE id=?").param(first).update();assertThatThrownBy(()->exports.request(name,"GITHUB",first)).isInstanceOf(AccountException.class);
  jdbc.sql("UPDATE submission SET example_check=false WHERE id=?").param(first).update();jdbc.sql("UPDATE problem_version SET diagnostic_only=true WHERE id='sum-v1'").update();
  try{assertThatThrownBy(()->exports.request(name,"GITHUB",first)).isInstanceOf(AccountException.class);}finally{jdbc.sql("UPDATE problem_version SET diagnostic_only=false WHERE id='sum-v1'").update();}
 }
 @Test void newerAcDuringDeliveryRunsAgainAndOlderSubmissionCannotReplaceIt(){
  UUID old=accepted();var work=exports.claim();UUID latest=accepted();assertThat(count()).isEqualTo(1);exports.finish(work,"https://github.com/owner/repo",null);
  assertThat(exports.deliveries(name,latest).getFirst().status()).isEqualTo("QUEUED");exports.request(name,"GITHUB",old);assertThat(exports.deliveries(name,latest)).hasSize(1);
  var next=exports.claim();assertThat(next.revision()).isEqualTo(1);assertThat(next.payload().path("createdAt").asText()).isNotEqualTo(work.payload().path("createdAt").asText());
 }
 @Test void abandonedLeaseIsRecoveredAndStaleWorkerCannotAffectNewConnection(){
  accepted();var old=exports.claim();jdbc.sql("UPDATE solution_export SET lease_until=?").param(SolutionExports.now().minusMinutes(2)).update();var fresh=exports.claim();
  assertThat(fresh.lease()).isNotEqualTo(old.lease());assertThatThrownBy(()->exports.fence(old)).isInstanceOf(ExportRemote.Failure.class);
  exports.disconnect(name,"GITHUB");connect("GITHUB",false);exports.finish(fresh,null,new ExportRemote.Failure("RECONNECT_REQUIRED",false));
  assertThat(exports.connection(user,"GITHUB").status()).isEqualTo("CONNECTED");assertThat(count()).isZero();
 }
 @Test void failuresBackOffAndStopAfterSixAttempts(){accepted();for(int i=1;i<=6;i++){var w=exports.claim();assertThat(w.attempts()).isEqualTo(i);exports.finish(w,null,new ExportRemote.Failure("NETWORK_ERROR",true));
  var d=exports.deliveries(name,null).getFirst();assertThat(d.status()).isEqualTo(i<6?"RETRY":"FAILED");assertThat(exports.claim()).isNull();jdbc.sql("UPDATE solution_export SET next_at=?").param(SolutionExports.now().minusSeconds(1)).update();}
  var id=exports.deliveries(name,null).getFirst().id();exports.retry(name,id);assertThat(exports.claim().attempts()).isEqualTo(1);
 }
 @Test void targetChangeCancelsPendingAndLabelChangeKeepsIdentity(){accepted();var work=exports.claim();var renamed=target().put("label","renamed");assertThat(SolutionExports.targetHash(renamed)).isEqualTo(SolutionExports.targetHash(target()));
  when(remote.target(anyString(),anyString(),anyString(),any(),any())).thenReturn(target().put("prefix","different"));exports.save(name,"GITHUB","owner/repo","main","different",true);
  assertThatThrownBy(()->exports.fence(work)).isInstanceOf(ExportRemote.Failure.class);assertThat(exports.deliveries(name,null).getFirst().status()).isEqualTo("CANCELLED");
 }
 @Test void vaultBindsCiphertextToOwnerAndNeverExposesCredentialsOverHttp()throws Exception{
  var c=exports.connection(user,"GITHUB");assertThat(c.credentials()).doesNotContain("test-token");assertThatThrownBy(()->vault.open("wrong-owner",c.credentials())).isInstanceOf(ExportRemote.Failure.class);
  mvc.perform(get("/api/integrations")).andExpect(status().isUnauthorized());
  String body=mvc.perform(get("/api/integrations").with(user(name))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(body).doesNotContain("credentials","test-token","fixture-secret");
  mvc.perform(delete("/api/integrations/GITHUB").with(user(name))).andExpect(status().isForbidden());
  mvc.perform(delete("/api/integrations/GITHUB").with(user(name)).with(csrf())).andExpect(status().isNoContent());
 }
 @Test void oauthIsSingleUseOwnerBoundAndDisconnectDuringExchangeCannotResurrect(){
  when(remote.authorization(anyString(),anyString(),anyString())).thenAnswer(c->c.getArgument(1));when(remote.exchange(anyString(),anyString(),anyString())).thenReturn(ExportRemote.obj().put("access_token","new-token"));when(remote.account(anyString(),any())).thenReturn("new-account");
  String state=exports.start(name,"GITHUB");assertThatThrownBy(()->exports.callback(name,"GITHUB","wrong","code")).isInstanceOf(AccountException.class);
  exports.callback(name,"GITHUB",state,"code");assertThatThrownBy(()->exports.callback(name,"GITHUB",state,"code")).isInstanceOf(AccountException.class);verify(remote,times(1)).exchange(anyString(),anyString(),anyString());
  String next=exports.start(name,"GITHUB");when(remote.exchange(anyString(),anyString(),anyString())).thenAnswer(c->{exports.disconnect(name,"GITHUB");return ExportRemote.obj().put("access_token","lost-token");});
  assertThatThrownBy(()->exports.callback(name,"GITHUB",next,"code")).isInstanceOf(AccountException.class);assertThatThrownBy(()->exports.connection(user,"GITHUB")).isInstanceOf(AccountException.class);
 }
 @Test void disabledAutoRequiresManualAndUserDeletionCascades(){jdbc.sql("UPDATE export_connection SET auto_enabled=false").update();UUID id=accepted();assertThat(count()).isZero();exports.request(name,"GITHUB",id);assertThat(count()).isEqualTo(1);jdbc.sql("DELETE FROM app_user WHERE id=?").param(user).update();assertThat(count()).isZero();assertThat(jdbc.sql("SELECT count(*) FROM export_connection").query(Integer.class).single()).isZero();}
 @Test void reconnectSameAccountPreservesPageMappingAndRefreshPreservesAccountIdentity(){
  var token=ExportRemote.obj().put("access_token","old").put("provider_account_id","remote-123");
  jdbc.sql("UPDATE export_connection SET credentials=?").param(vault.seal(user+":GITHUB",token)).update();UUID submission=accepted();
  var work=exports.claim();exports.checkpoint(work,ExportRemote.obj().put("pageId","existing-page"));exports.finish(work,"https://github.com/owner/repo",null);
  when(remote.authorization(anyString(),anyString(),anyString())).thenAnswer(c->c.getArgument(1));
  when(remote.exchange(anyString(),anyString(),anyString())).thenReturn(ExportRemote.obj().put("access_token","new").put("provider_account_id","remote-123"));when(remote.account(anyString(),any())).thenReturn("same-account");
  String state=exports.start(name,"GITHUB");exports.callback(name,"GITHUB",state,"code");assertThat(count()).isEqualTo(1);assertThat(exports.connection(user,"GITHUB").target()).isEqualTo(target());
  assertThat(jdbc.sql("SELECT remote_json FROM solution_export").query(String.class).single()).contains("existing-page");
  assertThat(SolutionExports.normalize(ExportRemote.obj().put("access_token","refresh"),token).path("provider_account_id").asText()).isEqualTo("remote-123");
 }

 @Test void cancelledOldTargetCanBeSelectedAgainAndManuallyResumed(){
  UUID id=accepted();when(remote.target(anyString(),anyString(),anyString(),any(),any())).thenReturn(target().put("prefix","other"),target());
  exports.save(name,"GITHUB","owner/repo","main","other",true);assertThat(exports.deliveries(name,null).getFirst().status()).isEqualTo("CANCELLED");
  exports.save(name,"GITHUB","owner/repo","main","GamjaOJ",true);exports.request(name,"GITHUB",id);assertThat(exports.claim()).isNotNull();
 }
 @Test void expiredAccessTokenRefreshIsPersistedOutsideTransaction(){
  var token=ExportRemote.obj().put("access_token","expired").put("refresh_token","refresh-once").put("provider_account_id","123").put("expires_at",SolutionExports.now().minusMinutes(1).toString());
  jdbc.sql("UPDATE export_connection SET credentials=?").param(vault.seal(user+":GITHUB",token)).update();
  when(remote.refresh("GITHUB","refresh-once")).thenAnswer(c->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return ExportRemote.obj().put("access_token","fresh").put("refresh_token","rotated").put("expires_in",28800);});
  assertThat(exports.access(exports.connection(user,"GITHUB"))).isEqualTo("fresh");assertThat(exports.access(exports.connection(user,"GITHUB"))).isEqualTo("fresh");verify(remote,times(1)).refresh("GITHUB","refresh-once");
  assertThat(vault.open(user+":GITHUB",exports.connection(user,"GITHUB").credentials()).path("refresh_token").asText()).isEqualTo("rotated");
 }

}
