package dev.gamjaoj;
import dev.gamjaoj.infrastructure.export.ExportHttp;
import dev.gamjaoj.infrastructure.export.ExportRemote;
import dev.gamjaoj.config.ExportSettings;
import dev.gamjaoj.infrastructure.export.ExportVault;
import dev.gamjaoj.service.export.GitHubSolutionLayout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExportRemoteTest {
 ExportSettings settings=new ExportSettings(new MockEnvironment().withProperty("PUBLIC_BASE_URL","https://example.test").withProperty("EXPORT_GITHUB_CLIENT_ID","client").withProperty("EXPORT_GITHUB_CLIENT_SECRET","secret").withProperty("EXPORT_NOTION_CLIENT_ID","notion").withProperty("EXPORT_NOTION_CLIENT_SECRET","secret"));
 ExportHttp http=mock(ExportHttp.class);ExportRemote remote=new ExportRemote(settings,http);
 ObjectNode payload(){return ExportRemote.obj().put("username","learner").put("problemVersion","sum-v1").put("title","두 수의 합").put("language","JAVA").put("filename","Main.java").put("source","public class Main {}").put("problemUrl","https://example.test/?problem=sum-v1#practice").put("finishedAt","2026-10-01T00:00:00Z");}
 @Test void newRatingSnapshotControlsNewFoldersAndBothExportLabels(){
  var p=payload().put("difficulty","EASY");
  var target=ExportRemote.obj().put("layout","problem-v1").put("prefix","GamjaOJ");
  String folder=GitHubSolutionLayout.folder(target,p);
  p.set("thinking",ExportRemote.obj().put("layer",5).put("name","뒤집어보기").put("insight",4).put("implementation",2).put("edgeCases",3).put("source","CURATED_ESTIMATE").put("rationale","질문의 방향을 바꿔 생각해야 해요."));
  assertThat(GitHubSolutionLayout.readme(p)).contains("5겹 · 뒤집어보기","발상 4/5","구현 2/5","경계 3/5","검토 추정");
  assertThat(ExportRemote.info("identity",p)).contains("5겹 · 뒤집어보기","발상 4/5");
  assertThat(folder).isEqualTo("GamjaOJ/Easy/[Easy] 두 수의 합 - sum-v1");
  assertThat(GitHubSolutionLayout.folder(target,p)).isEqualTo("GamjaOJ/5겹/[5겹 · 뒤집어보기] 두 수의 합 - sum-v1");
  ((ObjectNode)p.path("thinking")).put("layer",1).put("name","그대로");
  assertThat(GitHubSolutionLayout.folder(target,p)).isEqualTo("GamjaOJ/1겹/[1겹 · 그대로] 두 수의 합 - sum-v1");
  assertThat(GitHubSolutionLayout.readme(p)).contains("1겹 · 그대로").doesNotContain("[Easy]");
  p.putNull("thinking");assertThat(GitHubSolutionLayout.rating(p)).isEqualTo("겹 미배정");
  assertThat(GitHubSolutionLayout.folder(target,p)).isEqualTo("GamjaOJ/겹 미배정/[겹 미배정] 두 수의 합 - sum-v1");
  p.remove("thinking");assertThat(GitHubSolutionLayout.rating(p)).isEqualTo("Easy");
  assertThat(GitHubSolutionLayout.folder(target,p)).isEqualTo(folder);
 }
 @Test void githubUsesPinnedRepositoryBranchAndShaAndReplayDoesNotCommitAgain(){
  var files=new HashMap<String,JsonNode>();var writes=new ArrayList<JsonNode>();
  when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(c->{
   String method=c.getArgument(0),url=c.getArgument(1);assertThat(url).startsWith("https://api.github.com/");assertThat(c.getArgument(2,Map.class).get("Authorization")).isEqualTo("Bearer token");
   if(url.endsWith("/repos/owner/repo"))return ExportRemote.obj().put("id",123);
   String path=url.split("\\?ref=")[0];if(method.equals("GET")){if(!files.containsKey(path))throw new ExportRemote.Failure("TARGET_NOT_FOUND",false);return files.get(path);}
   var body=c.getArgument(3,JsonNode.class);assertThat(method).isEqualTo("PUT");assertThat(body.path("branch").asText()).isEqualTo("main");
   if(files.containsKey(path))assertThat(body.path("sha").asText()).isEqualTo("current-sha");
   writes.add(body.deepCopy());files.put(path,ExportRemote.obj().put("type","file").put("sha","current-sha").put("encoding","base64").put("content",body.path("content").asText()));return ExportRemote.obj();
  });
  var target=ExportRemote.obj().put("repo","owner/repo").put("id","123").put("prefix","GamjaOJ").put("branch","main");
  var state=ExportRemote.obj();var url=remote.publish("GITHUB","token",target,payload(),state,s->{},()->{});assertThat(url).endsWith("GamjaOJ/learner/sum-v1/JAVA");assertThat(writes).hasSize(2);
  remote.publish("GITHUB","token",target,payload(),ExportRemote.obj(),s->{},()->{});assertThat(writes).hasSize(2);
  var updated=payload().put("source","updated code");updated.set("thinking",ExportRemote.obj().put("layer",1).put("name","그대로"));
  remote.publish("GITHUB","token",target,updated,state,s->{},()->{});assertThat(writes).hasSize(4); // Code and the changed rating metadata both update.
  remote.publish("GITHUB","token",target,updated,state,s->{},()->{});assertThat(writes).hasSize(4);
  assertThat(state.path("githubFolder").asText()).isEqualTo("GamjaOJ/learner/sum-v1/JAVA");
 }
 JsonNode children(JsonNode... nodes){var r=ExportRemote.obj().put("has_more",false);r.putArray("results").addAll(Arrays.asList(nodes));return r;}
 ObjectNode info(){return ExportRemote.block("paragraph",ExportRemote.info("GamjaOJ · learner · sum-v1 · JAVA",payload())).put("id","info-id");}
 @Test void notionCreatesOncePatchesOnlyManagedBlocksAndKeepsReflection(){
  var requests=new ArrayList<String>();var state=ExportRemote.obj();var checkpoints=new ArrayList<ObjectNode>();
  when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(c->{String m=c.getArgument(0),u=c.getArgument(1);requests.add(m+" "+u);
   if(m.equals("POST")){assertThat(state.path("creating").asBoolean()).isTrue();var b=c.getArgument(3,JsonNode.class);assertThat(b.path("children")).hasSize(4);return ExportRemote.obj().put("id","page-id");}
   if(m.equals("GET"))return children(info(),ExportRemote.code(payload()).put("id","code-id"),ExportRemote.block("paragraph","회고 원문").put("id","reflection-id"));
   assertThat(u.endsWith("/info-id")||u.endsWith("/code-id")).isTrue();return ExportRemote.obj();
  });
  var target=ExportRemote.obj().put("id","parent-id");
  remote.publish("NOTION","token",target,payload(),state,s->checkpoints.add(s.deepCopy()),()->{});
  remote.publish("NOTION","token",target,payload().put("source","new code"),state,s->{},()->{});
  assertThat(requests.stream().filter(r->r.startsWith("POST")).count()).isEqualTo(1);assertThat(requests).noneMatch(r->r.contains("reflection-id"));assertThat(checkpoints.getFirst().path("creating").asBoolean()).isTrue();
 }
 @Test void lostNotionCreateResponseReconcilesExistingPageWithoutCreatingDuplicate(){
  var state=ExportRemote.obj().put("creating",true);var title=payload().path("title").asText()+" · JAVA";
  when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(c->{String m=c.getArgument(0),u=c.getArgument(1);assertThat(m).isNotEqualTo("POST");
   if(u.contains("/parent/children")){var child=ExportRemote.obj().put("type","child_page").put("id","found-page");child.putObject("child_page").put("title",title);return children(child);}
   if(m.equals("GET"))return children(info(),ExportRemote.code(payload()).put("id","code-id"));return ExportRemote.obj();
  });
  assertThat(remote.publish("NOTION","token",ExportRemote.obj().put("id","parent"),payload(),state,s->{},()->{})).endsWith("foundpage");assertThat(state.path("pageId").asText()).isEqualTo("found-page");
 }
 @Test void unknownCreateIsNotBlindlyRetriedAndDefiniteRateLimitCanRetry(){
  when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenReturn(children());
  assertThatThrownBy(()->remote.publish("NOTION","token",ExportRemote.obj().put("id","parent"),payload(),ExportRemote.obj().put("creating",true),s->{},()->{})).isInstanceOf(ExportRemote.Failure.class).hasMessage("DELIVERY_UNCERTAIN");
  var state=ExportRemote.obj();when(http.request(eq("POST"),anyString(),anyMap(),any())).thenThrow(new ExportRemote.Failure("RATE_LIMITED",true));
  assertThatThrownBy(()->remote.publish("NOTION","token",ExportRemote.obj().put("id","parent"),payload(),state,s->{},()->{})).hasMessage("RATE_LIMITED");assertThat(state.path("creating").asBoolean()).isFalse();
 }
 @Test void authorizationUsesPkceAndTokenExchangeUsesFixedProviderEndpoint(){
  assertThat(remote.authorization("GITHUB","state","verifier")).contains("code_challenge="+ExportVault.challenge("verifier"),"state=state","code_challenge_method=S256");
  when(http.request(anyString(),anyString(),anyMap(),any())).thenAnswer(c->{assertThat(c.getArgument(1,String.class)).isEqualTo("https://github.com/login/oauth/access_token");assertThat(c.getArgument(3,JsonNode.class).path("code_verifier").asText()).isEqualTo("verifier");return ExportRemote.obj().put("access_token","token");});
  remote.exchange("GITHUB","code","verifier");
 }
}
