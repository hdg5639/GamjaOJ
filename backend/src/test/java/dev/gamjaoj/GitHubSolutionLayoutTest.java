package dev.gamjaoj;
import dev.gamjaoj.infrastructure.export.ExportHttp;
import dev.gamjaoj.infrastructure.export.ExportRemote;
import dev.gamjaoj.config.ExportSettings;
import dev.gamjaoj.service.export.GitHubSolutionLayout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static dev.gamjaoj.infrastructure.export.ExportRemote.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GitHubSolutionLayoutTest {
    ObjectNode payload(){var p=new ExportRemoteTest().payload().put("difficulty","EASY").put("difficultySource","TEMPLATE_ESTIMATE").put("category","수열").put("createdAt","2026-10-01T00:01:02Z").put("maxWallMs",123).put("maxMemoryBytes",33554432);p.putArray("tags").add("구현").add("합계");return p;}
    ObjectNode target(){return obj().put("id","123").put("repo","owner/repo").put("branch","main").put("prefix","GamjaOJ").put("layout","problem-v1");}
    @Test void readableFoldersEncodeUnicodeAndBoundUnsafeNames(){
        String folder=GitHubSolutionLayout.folder(target(),payload());assertThat(folder).isEqualTo("GamjaOJ/Easy/[Easy] 두 수의 합 - sum-v1");
        assertThat(GitHubSolutionLayout.path(folder)).contains("%5BEasy%5D%20", "%EB").endsWith("%20-%20sum-v1").doesNotContain(" ");
        var unsafe=payload().put("title","../ A/B\\C:*?\"<>|\u0000\u202e");
        assertThat(GitHubSolutionLayout.folder(target(),unsafe)).isEqualTo("GamjaOJ/Easy/[Easy] ..／ A／B＼C：＊？＂＜＞｜ - sum-v1");
        String longName=GitHubSolutionLayout.folder(target(),payload().put("title","가😀".repeat(200))).substring("GamjaOJ/Easy/".length());
        assertThat(longName.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(240);
        assertThat(GitHubSolutionLayout.folder(target(),payload().put("title","\n\u202e..."))).endsWith("제목 없음 - sum-v1");
        var shown=payload().put("title","로운이의 암호문 삽입과 삭제").put("problemVersion","iamywl-v1-c9aa60b86f83f18c");
        shown.set("thinking",obj().put("layer",3).put("name","골라쓰기"));
        assertThat(GitHubSolutionLayout.folder(target(),shown)).isEqualTo("GamjaOJ/3겹/[3겹 · 골라쓰기] 로운이의 암호문 삽입과 삭제 - iamywl-v1-c9aa60b86f83f18c");
        assertThat(GitHubSolutionLayout.folder(target(),shown.put("problemVersion","v".repeat(80)).put("title","가😀".repeat(200))).substring("GamjaOJ/3겹/".length()).getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(240);
    }
    @Test void readmeContainsMeasuredPublicMetadataAndNoInventedMemoryOrPrivateProblemData(){
        var p=payload().put("statement","PRIVATE_STATEMENT").put("tests","PRIVATE_TESTS").put("editorial","PRIVATE_EDITORIAL");
        var text=GitHubSolutionLayout.readme(p);
        assertThat(text).contains("# [Easy] 두 수의 합 - sum-v1","Java | AC | 123 ms | 32.00 MiB","수열 · 구현 · 합계","2026년 10월 1일 09:01:02 KST","[Main.java](Main.java)","템플릿 추정");
        assertThat(text).doesNotContain("PRIVATE_","KB");
        p.remove("maxWallMs");assertThat(GitHubSolutionLayout.readme(p)).contains("미측정");
        assertThat(GitHubSolutionLayout.markdown("[x](y) <img> | **hi**")).contains("\\[x\\]","&lt;img&gt;","\\|","\\*\\*");
    }
    @Test void restartReusesSavedFolderAndRecoversPartialDeliveryWithoutDuplicateCommits(){
        for(String priorFolder:List.of("","GamjaOJ/Easy/sum-v1. 두 수의 합")) {
        ExportHttp http=mock(ExportHttp.class);var remote=new ExportRemote(new ExportSettings(new MockEnvironment()),http);
        var files=new HashMap<String,JsonNode>();var writes=new ArrayList<String>();var saved=obj();boolean[] fail={true};
        if(!priorFolder.isEmpty())saved.put("githubFolder",priorFolder);
        when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(call->{
            String method=call.getArgument(0),url=call.getArgument(1);if(url.endsWith("/repos/owner/repo"))return obj().put("id",123);
            assertThat(url).doesNotContain(" ");String path=url.split("\\?ref=")[0];
            if(method.equals("GET")){if(!files.containsKey(path))throw new Failure("TARGET_NOT_FOUND",false);return files.get(path);}
            assertThat(method).isEqualTo("PUT");var body=call.getArgument(3,JsonNode.class);assertThat(body.path("message").asText()).contains("Time: 123 ms, Memory: 32.00 MiB");
            if(path.endsWith("/Main.java")&&fail[0]){fail[0]=false;throw new Failure("NETWORK_ERROR",true);}
            if(files.containsKey(path))assertThat(body.path("sha").asText()).isEqualTo("sha");
            writes.add(path);files.put(path,obj().put("type","file").put("sha","sha").put("encoding","base64").put("content",body.path("content").asText()));return obj();
        });
        assertThatThrownBy(()->remote.publish("GITHUB","token",target(),payload(),saved.deepCopy(),state->saved.setAll(state),()->{})).hasMessage("NETWORK_ERROR");
        assertThat(saved.path("githubFolder").asText()).isEqualTo(priorFolder.isEmpty()?"GamjaOJ/Easy/[Easy] 두 수의 합 - sum-v1":priorFolder);
        var restarted=new ExportRemote(new ExportSettings(new MockEnvironment()),http);
        restarted.publish("GITHUB","token",target(),payload(),saved,state->{},()->{});assertThat(writes).hasSize(2);
        restarted.publish("GITHUB","token",target(),payload(),saved,state->{},()->{});assertThat(writes).hasSize(2);
        var changed=payload().put("title","변경된 제목").put("difficulty","HARD").put("source","updated code");
        changed.set("thinking",obj().put("layer",5).put("name","뒤집어보기"));
        restarted.publish("GITHUB","token",target(),changed,saved,state->{},()->{});
        assertThat(writes).hasSize(4).allMatch(path->path.contains(GitHubSolutionLayout.path(saved.path("githubFolder").asText())));
        }
    }
    @Test void freshLayerExportWritesReadmeAndCodeToLayerFolder(){
        ExportHttp http=mock(ExportHttp.class);var remote=new ExportRemote(new ExportSettings(new MockEnvironment()),http);
        var p=payload();p.set("thinking",obj().put("layer",1).put("name","그대로"));var writes=new ArrayList<String>();
        when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(call->{
            String method=call.getArgument(0),url=call.getArgument(1);
            if(url.endsWith("/repos/owner/repo"))return obj().put("id",123);
            if(method.equals("GET"))throw new Failure("TARGET_NOT_FOUND",false);
            assertThat(method).isEqualTo("PUT");writes.add(url);
            if(url.endsWith("/README.md")){
                var body=call.getArgument(3,JsonNode.class);
                assertThat(new String(Base64.getDecoder().decode(body.path("content").asText()),StandardCharsets.UTF_8)).contains("# [1겹 · 그대로]");
            }
            return obj();
        });
        var state=obj();remote.publish("GITHUB","token",target(),p,state,s->{},()->{});
        assertThat(state.path("githubFolder").asText()).isEqualTo("GamjaOJ/1겹/[1겹 · 그대로] 두 수의 합 - sum-v1");
        assertThat(writes).hasSize(2).allMatch(url->url.contains("/GamjaOJ/1%EA%B2%B9/")).noneMatch(url->url.contains("/Easy/"));
    }
    @Test void existingUnownedFolderAndStaleFenceNeverOverwriteFiles(){
        ExportHttp http=mock(ExportHttp.class);var remote=new ExportRemote(new ExportSettings(new MockEnvironment()),http);
        when(http.request(anyString(),anyString(),anyMap(),nullable(JsonNode.class))).thenAnswer(call->{
            String url=call.getArgument(1);if(url.endsWith("/repos/owner/repo"))return obj().put("id",123);
            return obj().put("type","file").put("encoding","base64").put("content",Base64.getEncoder().encodeToString("my README".getBytes(StandardCharsets.UTF_8)));
        });
        assertThatThrownBy(()->remote.publish("GITHUB","token",target(),payload(),obj(),state->{},()->{})).hasMessage("PATH_CONFLICT");
        verify(http,never()).request(eq("PUT"),anyString(),anyMap(),any());clearInvocations(http);
        assertThatThrownBy(()->remote.publish("GITHUB","token",target(),payload(),obj(),state->{},()->{throw new Failure("CONNECTION_CHANGED",false);})).hasMessage("CONNECTION_CHANGED");verifyNoInteractions(http);
    }
}
