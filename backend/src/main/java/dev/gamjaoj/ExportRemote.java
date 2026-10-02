package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Only fixed provider origins are used; account tokens cannot be sent to client-supplied URLs. */
@Component
class ExportRemote {
    static final class Failure extends RuntimeException {
        final String code;final boolean retryable;
        Failure(String code,boolean retryable){super(code);this.code=code;this.retryable=retryable;}
    }
    record Target(String id,String label,String url,boolean privateTarget,String branch) {}
    final ExportSettings settings;final ExportHttp http;
    private final ThreadLocal<Runnable> deliveryFence=new ThreadLocal<>();
    ExportRemote(ExportSettings settings,ExportHttp http){this.settings=settings;this.http=http;}
    static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
    static ObjectNode obj(){return JudgeJson.JSON.createObjectNode();}
    String authorization(String provider,String state,String verifier){
        String query="client_id="+enc(settings.client(provider))+"&redirect_uri="+enc(settings.callback(provider))+"&state="+enc(state);
        return provider.equals("GITHUB")?"https://github.com/login/oauth/authorize?"+query+"&code_challenge_method=S256&code_challenge="+ExportVault.challenge(verifier)
                :"https://api.notion.com/v1/oauth/authorize?"+query+"&response_type=code&owner=user";
    }
    JsonNode exchange(String provider,String code,String verifier){
        var body=obj().put("grant_type","authorization_code").put("code",code).put("redirect_uri",settings.callback(provider));
        if(provider.equals("GITHUB"))body.put("code_verifier",verifier);
        return token(provider,body);
    }
    JsonNode refresh(String provider,String refresh){return token(provider,obj().put("grant_type","refresh_token").put("refresh_token",refresh));}
    JsonNode token(String provider,ObjectNode body){
        var headers=new HashMap<String,String>();headers.put("Accept","application/json");headers.put("Content-Type","application/json");
        if(provider.equals("GITHUB"))body.put("client_id",settings.client(provider)).put("client_secret",settings.secret(provider));
        else headers.put("Authorization","Basic "+Base64.getEncoder().encodeToString((settings.client(provider)+":"+settings.secret(provider)).getBytes(StandardCharsets.UTF_8)));
        var result=http.request("POST",provider.equals("GITHUB")?"https://github.com/login/oauth/access_token":"https://api.notion.com/v1/oauth/token",headers,body);
        if(result.path("access_token").asText().isBlank())throw new Failure("RECONNECT_REQUIRED",false);
        return result;
    }
    JsonNode api(String provider,String token,String method,String path,JsonNode body){
        if(deliveryFence.get()!=null)deliveryFence.get().run();
        return http.request(method,settings.api(provider)+path,Map.of("Authorization","Bearer "+token,"Content-Type","application/json",
                "Accept",provider.equals("GITHUB")?"application/vnd.github+json":"application/json",
                provider.equals("GITHUB")?"X-GitHub-Api-Version":"Notion-Version",provider.equals("GITHUB")?"2026-03-10":"2026-03-11"),body);
    }
    String account(String provider,JsonNode credentials){
        var identity=provider.equals("GITHUB")?api(provider,credentials.path("access_token").asText(),"GET","/user",null):credentials;
        String id=identity.path(provider.equals("GITHUB")?"id":"workspace_id").asText();
        if(id.isBlank())throw new Failure("RECONNECT_REQUIRED",false);
        ((ObjectNode)credentials).put("provider_account_id",id);
        return identity.path(provider.equals("GITHUB")?"login":"workspace_name").asText("Notion");
    }
    List<Target> targets(String provider,String token,String search){
        var targets=new ArrayList<Target>();
        if(provider.equals("GITHUB")) {
            var installations=api(provider,token,"GET","/user/installations?per_page=100",null).path("installations");
            for(var installation:installations){
                if(!installation.path("permissions").path("contents").asText().equals("write"))continue;
                for(int page=1;page<=10;page++){
                    var repos=api(provider,token,"GET","/user/installations/"+installation.path("id").asLong()+"/repositories?per_page=100&page="+page,null).path("repositories");
                    for(var repo:repos)if(!repo.path("archived").asBoolean()&&repo.path("permissions").path("push").asBoolean()
                        &&repo.path("full_name").asText().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
                        targets.add(new Target(repo.path("full_name").asText(),repo.path("full_name").asText(),repo.path("html_url").asText(),repo.path("private").asBoolean(),repo.path("default_branch").asText()));
                    if(repos.size()<100||targets.size()>=200)break;
                }
                if(targets.size()>=200)break;
            }
        } else {
            var body=obj().put("page_size",100).put("query",search);
            for(int page=0;page<10;page++) {
                var response=api(provider,token,"POST","/search",body);
                for(var item:response.path("results"))if(!item.path("archived").asBoolean()&&!item.path("in_trash").asBoolean()){
                    boolean table=item.path("object").asText().equals("data_source");
                    if(!table&&!item.path("object").asText().equals("page"))continue;
                    String title=table?plain(item.path("title")):"";
                    if(!table)for(var property:item.path("properties"))if(property.path("type").asText().equals("title"))title=plain(property.path("title"));
                    targets.add(new Target((table?"data_source:":"")+item.path("id").asText(),
                        (table?"표 · ":"페이지 · ")+(title.isBlank()?"제목 없음":title),item.path("url").asText(),true,null));
                }
                if(!response.path("has_more").asBoolean()||targets.size()>=200)break;
                body.put("start_cursor",response.path("next_cursor").asText());
            }
        }
        return targets.stream().limit(200).toList();
    }
    JsonNode target(String provider,String token,String id,String branch,String prefix){
        if(provider.equals("GITHUB")) {
            if(!id.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new Failure("INVALID_TARGET",false);
            var repo=api(provider,token,"GET","/repos/"+id,null);
            if(repo.path("archived").asBoolean()||!repo.path("permissions").path("push").asBoolean())throw new Failure("PERMISSION_REQUIRED",false);
            String selected=branch==null||branch.isBlank()?repo.path("default_branch").asText():branch;
            if(selected.isBlank()||selected.length()>200||selected.contains("..")||selected.chars().anyMatch(c->c<32))throw new Failure("INVALID_TARGET",false);
            // An empty repository has no branch yet; the Contents API creates its initial branch.
            if(repo.path("size").asLong()>0)api(provider,token,"GET","/repos/"+id+"/branches/"+enc(selected),null);
            String folder=prefix==null||prefix.isBlank()?"GamjaOJ":prefix;
            if(!folder.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")||folder.length()>120)throw new Failure("INVALID_TARGET",false);
            return obj().put("id",repo.path("id").asText()).put("label",repo.path("full_name").asText()).put("repo",repo.path("full_name").asText())
                .put("branch",selected).put("prefix",folder).put("private",repo.path("private").asBoolean()).put("url",repo.path("html_url").asText());
        }
        boolean table=id.startsWith("data_source:");
        try{id=UUID.fromString(table?id.substring(12):id).toString();}catch(Exception e){throw new Failure("INVALID_TARGET",false);}
        if(table)return new NotionTables(this).target(token,id);
        var page=api(provider,token,"GET","/pages/"+id,null);
        if(page.path("archived").asBoolean()||page.path("in_trash").asBoolean())throw new Failure("TARGET_NOT_FOUND",false);
        String title="";for(var prop:page.path("properties"))if(prop.path("type").asText().equals("title"))title=plain(prop.path("title"));
        return obj().put("id",id).put("kind","notion_table_parent").put("label",title.isBlank()?"제목 없는 페이지":title).put("url",page.path("url").asText());
    }
    String publish(String provider,String token,JsonNode target,JsonNode payload,ObjectNode state,Consumer<ObjectNode> checkpoint,Runnable fence){
        deliveryFence.set(fence);
        try {if(provider.equals("GITHUB"))return github(token,target,payload,state,checkpoint,fence);
            if(target.path("kind").asText().startsWith("notion_table"))
                return new NotionTables(this).publish(token,target,payload,state,checkpoint,fence);
            return notion(token,target,payload,state,checkpoint,fence);
        } finally {deliveryFence.remove();}
    }
    String notionTableSource(String token,JsonNode target,ObjectNode state,Consumer<ObjectNode> checkpoint,Runnable fence){
        deliveryFence.set(fence);
        try{return new NotionTables(this).source(token,target,state,checkpoint,fence);}
        finally{deliveryFence.remove();}
    }
    String github(String token,JsonNode target,JsonNode payload,ObjectNode state,Consumer<ObjectNode> checkpoint,Runnable fence){
        String repo=target.path("repo").asText(),branch=target.path("branch").asText();
        var current=api("GITHUB",token,"GET","/repos/"+repo,null);
        if(!current.path("id").asText().equals(target.path("id").asText()))throw new Failure("TARGET_CHANGED",false);
        String folder=state.path("githubFolder").asText();
        if(folder.isBlank()){
            folder=GitHubSolutionLayout.folder(target,payload);state.put("githubFolder",folder);checkpoint.accept(state);
        }
        if(!folder.startsWith(target.path("prefix").asText()+"/")||Arrays.asList(folder.split("/")).contains(".."))throw new Failure("TARGET_CHANGED",false);
        boolean compact=target.path("layout").asText().equals("problem-v1");
        if(compact){
            try{
                var old=api("GITHUB",token,"GET","/repos/"+repo+"/contents/"+GitHubSolutionLayout.path(folder+"/README.md")+"?ref="+enc(branch),null);
                if(!old.path("type").asText().equals("file")||!old.path("encoding").asText().equals("base64"))throw new Failure("PATH_CONFLICT",false);
                String content=new String(Base64.getMimeDecoder().decode(old.path("content").asText()),StandardCharsets.UTF_8);
                if(!content.contains(GitHubSolutionLayout.marker(payload)))throw new Failure("PATH_CONFLICT",false);
            }catch(Failure e){
                if(!e.code.equals("TARGET_NOT_FOUND"))throw e;
                try{
                    api("GITHUB",token,"GET","/repos/"+repo+"/contents/"+GitHubSolutionLayout.path(folder)+"?ref="+enc(branch),null);
                    throw new Failure("PATH_CONFLICT",false);
                }catch(Failure directory){if(!directory.code.equals("TARGET_NOT_FOUND"))throw directory;}
            }
        }
        if(!compact)putFile(token,repo,branch,folder+"/"+payload.path("filename").asText(),payload.path("source").asText(),payload,fence);
        String readme="# "+payload.path("title").asText().replace('\n',' ')+"\n\n- 문제: "+payload.path("problemUrl").asText()+"\n- 언어: "+payload.path("language").asText()+"\n- 결과: AC\n- 통과 시각: "+payload.path("finishedAt").asText()+"\n- 최대 실행 시간: "+ExecutionMetrics.time(payload)+"\n- 최대 메모리: "+ExecutionMetrics.memory(payload)+"\n\n메모리는 호스트 관측 컨테이너 cgroup 최고 사용량 (런타임·파일 캐시 포함)입니다.\n\nGamjaOJ에서 자동으로 관리하는 풀이 기록입니다.\n";
        if(compact)readme=GitHubSolutionLayout.readme(payload);
        putFile(token,repo,branch,folder+"/README.md",readme,payload,fence);
        if(compact)putFile(token,repo,branch,folder+"/"+payload.path("filename").asText(),payload.path("source").asText(),payload,fence);
        return "https://github.com/"+repo+"/tree/"+enc(branch)+"/"+GitHubSolutionLayout.path(folder);
    }
    void putFile(String token,String repo,String branch,String path,String content,JsonNode payload,Runnable fence){
        String sha=null;try{
            var old=api("GITHUB",token,"GET","/repos/"+repo+"/contents/"+GitHubSolutionLayout.path(path)+"?ref="+enc(branch),null);
            if(!old.path("type").asText().equals("file"))throw new Failure("REMOTE_CONFLICT",false);
            if(old.path("encoding").asText().equals("base64")) {
                String existing=new String(Base64.getMimeDecoder().decode(old.path("content").asText()),StandardCharsets.UTF_8);
                if(existing.equals(content))return;
            }
            sha=old.path("sha").asText();
        }catch(Failure e){if(!e.code.equals("TARGET_NOT_FOUND"))throw e;}
        var body=obj().put("message","[GamjaOJ]["+GitHubSolutionLayout.rating(payload)+"] "+payload.path("title").asText().replaceAll("[\\p{Cntrl}]"," ")+" - "+GitHubSolutionLayout.language(payload)+" (AC), Time: "+ExecutionMetrics.time(payload)+", Memory: "+ExecutionMetrics.memory(payload))
            .put("branch",branch).put("content",Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if(sha!=null)body.put("sha",sha);fence.run();api("GITHUB",token,"PUT","/repos/"+repo+"/contents/"+GitHubSolutionLayout.path(path),body);
    }
    String notion(String token,JsonNode target,JsonNode payload,ObjectNode state,Consumer<ObjectNode> checkpoint,Runnable fence){
        String identity="GamjaOJ · "+payload.path("username").asText()+" · "+payload.path("problemVersion").asText()+" · "+payload.path("language").asText();
        String title=payload.path("title").asText()+" · "+payload.path("language").asText();
        if(!state.hasNonNull("pageId")) {
            // A lost create response is reconciled before any retry; never blindly create duplicate pages.
            if(state.path("creating").asBoolean()) {
                for(var child:children(token,target.path("id").asText()))if(child.path("type").asText().equals("child_page")&&child.path("child_page").path("title").asText().equals(title)) {
                    for(var block:children(token,child.path("id").asText()))if(block.path("type").asText().equals("paragraph")&&plain(block.path("paragraph").path("rich_text")).startsWith(identity+"\n")) {
                        state.put("pageId",child.path("id").asText());checkpoint.accept(state);break;
                    }
                    if(state.hasNonNull("pageId"))break;
                }
                if(!state.hasNonNull("pageId"))throw new Failure("DELIVERY_UNCERTAIN",false);
            } else {
                var body=obj();body.putObject("parent").put("page_id",target.path("id").asText());body.putObject("properties").putObject("title").set("title",rich(title));
                var blocks=body.putArray("children");blocks.add(block("paragraph",info(identity,payload)));blocks.add(code(payload));
                blocks.add(block("heading_2","내 회고"));blocks.add(block("paragraph",""));
                state.put("creating",true);checkpoint.accept(state);fence.run();
                JsonNode page;
                try {page=api("NOTION",token,"POST","/pages",body);}
                catch(Failure e){
                    if(Set.of("RECONNECT_REQUIRED","PERMISSION_REQUIRED","TARGET_NOT_FOUND","RATE_LIMITED","REMOTE_CONFLICT").contains(e.code)){state.put("creating",false);checkpoint.accept(state);}
                    throw e;
                }
                state.put("pageId",page.path("id").asText());checkpoint.accept(state);
            }
        }
        String page=state.path("pageId").asText();
        if(!state.hasNonNull("infoBlock")||!state.hasNonNull("codeBlock")) {
            boolean found=false;
            for(var child:children(token,page)) {
                if(child.path("type").asText().equals("paragraph")&&plain(child.path("paragraph").path("rich_text")).startsWith(identity+"\n")){state.put("infoBlock",child.path("id").asText());found=true;}
                else if(found&&child.path("type").asText().equals("code")){state.put("codeBlock",child.path("id").asText());break;}
            }
            if(!state.hasNonNull("infoBlock")||!state.hasNonNull("codeBlock"))throw new Failure("MANAGED_CONTENT_MISSING",false);
            checkpoint.accept(state);
        }
        // Only our two managed blocks are patched. User-written reflection and other blocks are untouched.
        var info=obj();info.putObject("paragraph").set("rich_text",rich(info(identity,payload)));fence.run();api("NOTION",token,"PATCH","/blocks/"+state.path("infoBlock").asText(),info);
        var source=obj();source.set("code",code(payload).path("code"));fence.run();api("NOTION",token,"PATCH","/blocks/"+state.path("codeBlock").asText(),source);
        return "https://www.notion.so/"+page.replace("-","");
    }
    List<JsonNode> children(String token,String id){
        var out=new ArrayList<JsonNode>();String cursor="";
        for(int page=0;page<20;page++){
            var response=api("NOTION",token,"GET","/blocks/"+id+"/children?page_size=100"+(cursor.isEmpty()?"":"&start_cursor="+enc(cursor)),null);
            response.path("results").forEach(out::add);if(!response.path("has_more").asBoolean())return out;cursor=response.path("next_cursor").asText();
        }
        throw new Failure("PAGE_TOO_LARGE",false);
    }
    static String info(String identity,JsonNode p){return identity+"\n난도: "+GitHubSolutionLayout.ratingDetails(p)+"\n문제: "+p.path("problemUrl").asText()+"\n결과: AC\n통과 시각: "+p.path("finishedAt").asText()+"\n최대 실행 시간: "+ExecutionMetrics.time(p)+"\n최대 메모리: "+ExecutionMetrics.memory(p)+"\n메모리는 호스트 관측 컨테이너 cgroup 최고 사용량 (런타임·파일 캐시 포함)입니다.";}
    static JsonNode rich(String text){
        var out=JudgeJson.JSON.createArrayNode();for(int from=0;from<text.length();){int to=Math.min(from+1800,text.length());if(to<text.length()&&Character.isHighSurrogate(text.charAt(to-1)))to--;out.addObject().put("type","text").putObject("text").put("content",text.substring(from,to));from=to;}return out;
    }
    static ObjectNode block(String type,String text){var b=obj().put("object","block").put("type",type);b.putObject(type).set("rich_text",rich(text));return b;}
    static ObjectNode code(JsonNode p){var b=block("code",p.path("source").asText());((ObjectNode)b.path("code")).put("language",switch(p.path("language").asText()){case "JAVA"->"java";case "CPP"->"c++";default->"python";});return b;}
    static String plain(JsonNode rich){var b=new StringBuilder();for(var r:rich)b.append(r.has("plain_text")?r.path("plain_text").asText():r.path("text").path("content").asText());return b.toString();}
}
