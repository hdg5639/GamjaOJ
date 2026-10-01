package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
class SolutionExports {
    record Accepted(UUID submission) {}
    record Connection(UUID id,UUID owner,String provider,String credentials,String label,JsonNode target,boolean auto,String status,int tokenVersion) {}
    record ConnectionView(String provider,boolean available,boolean connected,String status,String account,JsonNode target,boolean autoEnabled,String installUrl) {}
    record Delivery(UUID id,String provider,String problemVersion,String language,UUID submissionId,String status,String error,String url,OffsetDateTime updatedAt) {}
    record Work(UUID id,UUID user,String provider,UUID lease,int revision,int attempts,JsonNode target,JsonNode payload,ObjectNode remote) {}
    final JdbcClient jdbc;final ExportSettings settings;final ExportVault vault;final ExportRemote remote;final TransactionTemplate tx;
    SolutionExports(JdbcClient jdbc,ExportSettings settings,ExportVault vault,ExportRemote remote,PlatformTransactionManager transactions){this.jdbc=jdbc;this.settings=settings;this.vault=vault;this.remote=remote;tx=new TransactionTemplate(transactions);}
    static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    UUID owner(String username){return jdbc.sql("SELECT id FROM app_user WHERE username=?").param(username).query(UUID.class).optional().orElseThrow(()->new AccountException(401,"다시 로그인해 주세요."));}
    Connection connection(UUID user,String provider){
        return jdbc.sql("SELECT * FROM export_connection WHERE user_id=? AND provider=?").param(user).param(provider).query((r,n)->new Connection(r.getObject("id",UUID.class),user,provider,r.getString("credentials"),r.getString("account_label"),r.getString("target_json")==null?null:JudgeJson.parse(r.getString("target_json")),r.getBoolean("auto_enabled"),r.getString("status"),r.getInt("token_version"))).optional().orElseThrow(()->new AccountException(409,"먼저 계정을 연결해 주세요."));
    }
    List<ConnectionView> connections(String username){UUID user=owner(username);var result=new ArrayList<ConnectionView>();
        for(String provider:List.of("GITHUB","NOTION")){
            Connection c=null;try{c=connection(user,provider);}catch(AccountException ignored){}
            result.add(new ConnectionView(provider,settings.ready(provider),c!=null,c==null?"DISCONNECTED":c.status,c==null?null:c.label,c==null?null:c.target,c!=null&&c.auto,provider.equals("GITHUB")?settings.installUrl():null));
        }return result;
    }
    String start(String username,String provider){
        if(!settings.ready(provider))throw new AccountException(503,"이 서비스의 연동을 준비 중이에요.");
        UUID user=owner(username);String state=vault.random(),verifier=vault.random();
        tx.executeWithoutResult(s->{
            jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(UUID.class).single();
            jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=?").param(user).param(provider).update();
            jdbc.sql("INSERT INTO export_oauth(user_id,provider,state_sha256,verifier,expires_at) VALUES (?,?,?,?,?)").param(user).param(provider).param(JudgeJson.hash(state))
                .param(vault.seal("oauth:"+user+":"+provider,JudgeJson.JSON.getNodeFactory().textNode(verifier))).param(now().plusMinutes(10)).update();
        });return remote.authorization(provider,state,verifier);
    }
    void callback(String username,String provider,String state,String code){
        if(!settings.ready(provider)||state==null||state.length()>200||code==null||code.isBlank()||code.length()>2000)throw new AccountException(400,"계정 연결을 다시 시작해 주세요.");
        UUID user=owner(username);String hash=JudgeJson.hash(state);
        String encrypted=tx.execute(s->{
            var row=jdbc.sql("SELECT verifier FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=? AND claimed=false AND expires_at>? FOR UPDATE")
                .param(user).param(provider).param(hash).param(now()).query(String.class).optional().orElseThrow(()->new AccountException(400,"만료되거나 이미 사용한 연결 요청이에요."));
            jdbc.sql("UPDATE export_oauth SET claimed=true WHERE user_id=? AND provider=? AND state_sha256=?").param(user).param(provider).param(hash).update();return row;
        });
        var credentials=normalize(remote.exchange(provider,code,vault.open("oauth:"+user+":"+provider,encrypted).asText()),null);
        String account=remote.account(provider,credentials);
        tx.executeWithoutResult(s->{
            if(jdbc.sql("SELECT user_id FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=? AND claimed=true AND expires_at>? FOR UPDATE")
                .param(user).param(provider).param(hash).param(now()).query(UUID.class).optional().isEmpty())throw new AccountException(409,"취소된 연결 요청이에요.");
            boolean sameAccount=false;
            var old=jdbc.sql("SELECT credentials FROM export_connection WHERE user_id=? AND provider=? FOR UPDATE").param(user).param(provider).query(String.class).optional();
            if(old.isPresent()&&!credentials.path("provider_account_id").asText().isBlank()){
                try{sameAccount=credentials.path("provider_account_id").asText().equals(vault.open(user+":"+provider,old.get()).path("provider_account_id").asText());}catch(ExportRemote.Failure ignored){}
            }
            if(sameAccount){
                jdbc.sql("UPDATE export_connection SET id=?,credentials=?,account_label=?,status='CONNECTED',token_version=token_version+1,refreshing_until=NULL WHERE user_id=? AND provider=?")
                    .param(UUID.randomUUID()).param(vault.seal(user+":"+provider,credentials)).param(account.substring(0,Math.min(200,account.length()))).param(user).param(provider).update();
                jdbc.sql("UPDATE solution_export SET status='QUEUED',lease_token=NULL,lease_until=NULL,next_at=? WHERE user_id=? AND provider=? AND status='RUNNING'").param(now()).param(user).param(provider).update();
            }else{
            jdbc.sql("DELETE FROM export_connection WHERE user_id=? AND provider=?").param(user).param(provider).update();
            jdbc.sql("INSERT INTO export_connection(id,user_id,provider,credentials,account_label) VALUES (?,?,?,?,?)")
                .param(UUID.randomUUID()).param(user).param(provider).param(vault.seal(user+":"+provider,credentials)).param(account.substring(0,Math.min(200,account.length()))).update();
            }
            jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=? AND state_sha256=?").param(user).param(provider).param(hash).update();
        });
    }
    static ObjectNode normalize(JsonNode token,JsonNode old){
        var result=(ObjectNode)token.deepCopy();
        if(!result.hasNonNull("refresh_token")&&old!=null&&old.hasNonNull("refresh_token"))result.set("refresh_token",old.path("refresh_token"));
        if(old!=null&&old.hasNonNull("provider_account_id"))result.set("provider_account_id",old.path("provider_account_id"));
        result.put("expires_at",result.has("expires_in")?now().plusSeconds(result.path("expires_in").asLong()).toString():"");return result;
    }
    String access(Connection c){
        if(!c.status.equals("CONNECTED"))throw new ExportRemote.Failure("RECONNECT_REQUIRED",false);
        var tokens=vault.open(c.owner+":"+c.provider,c.credentials);String expires=tokens.path("expires_at").asText();
        if(expires.isBlank()||OffsetDateTime.parse(expires).isAfter(now().plusMinutes(2)))return tokens.path("access_token").asText();
        if(tokens.path("refresh_token").asText().isBlank())throw new ExportRemote.Failure("RECONNECT_REQUIRED",false);
        int claimed=jdbc.sql("UPDATE export_connection SET refreshing_until=? WHERE id=? AND token_version=? AND (refreshing_until IS NULL OR refreshing_until<?)")
            .param(now().plusMinutes(1)).param(c.id).param(c.tokenVersion).param(now()).update();
        if(claimed!=1)throw new ExportRemote.Failure("CONNECTION_BUSY",true);
        try{
            var next=normalize(remote.refresh(c.provider,tokens.path("refresh_token").asText()),tokens);
            if(jdbc.sql("UPDATE export_connection SET credentials=?,token_version=token_version+1,refreshing_until=NULL WHERE id=? AND token_version=?")
                .param(vault.seal(c.owner+":"+c.provider,next)).param(c.id).param(c.tokenVersion).update()!=1)throw new ExportRemote.Failure("CONNECTION_CHANGED",false);
            return next.path("access_token").asText();
        }catch(ExportRemote.Failure e){
            // Rotating refresh-token responses can be lost. Require reconnection instead of replaying an unknown exchange.
            jdbc.sql("UPDATE export_connection SET status='RECONNECT_REQUIRED',auto_enabled=false,refreshing_until=NULL WHERE id=? AND token_version=?").param(c.id).param(c.tokenVersion).update();throw e;
        }
    }
    List<ExportRemote.Target> targets(String username,String provider,String search){
        if(!settings.ready(provider))throw new AccountException(503,"연동을 준비 중이에요.");
        var c=connection(owner(username),provider);return remote.targets(provider,access(c),search==null?"":search.substring(0,Math.min(100,search.length())));
    }
    void save(String username,String provider,String target,String branch,String prefix,boolean auto){
        if(!settings.ready(provider))throw new AccountException(503,"연동을 준비 중이에요.");
        var c=connection(owner(username),provider);var selected=remote.target(provider,access(c),target,branch,prefix);
        tx.executeWithoutResult(s->{
            if(jdbc.sql("UPDATE export_connection SET target_json=?,auto_enabled=? WHERE id=? AND status='CONNECTED'").param(JudgeJson.canonical(selected)).param(auto).param(c.id).update()!=1)throw new AccountException(409,"연결이 변경됐어요. 새로고침해 주세요.");
            jdbc.sql("UPDATE solution_export SET status='CANCELLED',lease_token=NULL,lease_until=NULL,error_code='TARGET_CHANGED' WHERE user_id=? AND provider=? AND target_sha256<>? AND status IN ('QUEUED','RETRY','RUNNING','FAILED')")
                .param(c.owner).param(provider).param(targetHash(selected)).update();
        });
    }
    void disconnect(String username,String provider){UUID user=owner(username);tx.executeWithoutResult(s->{
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(UUID.class).single();
        jdbc.sql("DELETE FROM export_oauth WHERE user_id=? AND provider=?").param(user).param(provider).update();
        jdbc.sql("DELETE FROM export_connection WHERE user_id=? AND provider=?").param(user).param(provider).update();
    });}
    @EventListener @Transactional(propagation=Propagation.MANDATORY)
    public void accepted(Accepted event){
        if(!settings.enabled())return;
        UUID user=jdbc.sql("SELECT user_id FROM submission WHERE id=?").param(event.submission).query(UUID.class).single();
        for(String provider:jdbc.sql("SELECT provider FROM export_connection WHERE user_id=? AND auto_enabled=true AND status='CONNECTED' AND target_json IS NOT NULL").param(user).query(String.class).list())
            enqueue(user,provider,event.submission,false);
    }
    UUID request(String username,String provider,UUID submission){
        if(!settings.ready(provider))throw new AccountException(503,"연동을 준비 중이에요.");
        return tx.execute(s->enqueue(owner(username),provider,submission,true));
    }
    UUID enqueue(UUID user,String provider,UUID submission,boolean manual){
        // Ownership and exportability are resolved in one query; internal/diagnostic/custom-run code is never exported.
        var rows=jdbc.sql("SELECT s.*,j.finished_at,p.package_json,u.username FROM submission s JOIN judge_job j ON j.submission_id=s.id JOIN problem_version p ON p.id=s.problem_version JOIN app_user u ON u.id=s.user_id WHERE s.id=? AND s.user_id=? AND j.status='FINISHED' AND j.verdict='AC' AND s.run_input IS NULL AND s.diagnostic_item_id IS NULL AND s.hybrid_branch_id IS NULL AND s.generation_job_id IS NULL AND s.spec_draft_id IS NULL AND s.example_check=false AND p.diagnostic_only=false AND p.review_hold=false")
            .param(submission).param(user).query((r,n)->{
                var p=JudgeJson.parse(r.getString("package_json"));String language=r.getString("language");String version=r.getString("problem_version");
                if(!version.matches("[A-Za-z0-9_.-]{1,80}")||version.equals(".")||version.equals(".."))throw new AccountException(409,"이 문제의 저장 경로를 만들 수 없어요.");
                return ExportRemote.obj().put("username",r.getString("username")).put("problemVersion",version).put("language",language).put("title",p.path("title").asText(version))
                    .put("source",r.getString("source_code")).put("createdAt",r.getObject("created_at",OffsetDateTime.class).toString()).put("finishedAt",r.getObject("finished_at",OffsetDateTime.class).toString())
                    .put("problemUrl",settings.origin()+"/?problem="+ExportRemote.enc(version)+"#practice")
                    .put("filename",language.equals("JAVA")?(r.getString("callable_package")!=null||p.has("api")?"UserSolution.java":"Main.java"):language.equals("CPP")?"Main.cpp":"Main.py");
            }).optional();
        if(rows.isEmpty()){if(manual)throw new AccountException(404,"저장할 수 있는 본인의 정식 통과 제출을 선택해 주세요.");return null;}
        if(jdbc.sql("SELECT id FROM export_connection WHERE user_id=? AND provider=? AND status='CONNECTED' AND target_json IS NOT NULL FOR UPDATE")
            .param(user).param(provider).query(UUID.class).optional().isEmpty()){if(manual)throw new AccountException(409,"연결과 저장 위치를 먼저 설정해 주세요.");return null;}
        var c=connection(user,provider);if(!manual&&!c.auto)return null;var p=rows.get();String target=JudgeJson.canonical(c.target),hash=targetHash(c.target);var created=OffsetDateTime.parse(p.path("createdAt").asText());
        var existing=jdbc.sql("SELECT id,submission_id,submitted_at,status FROM solution_export WHERE user_id=? AND provider=? AND target_sha256=? AND problem_version=? AND language=? FOR UPDATE")
            .param(user).param(provider).param(hash).param(p.path("problemVersion").asText()).param(p.path("language").asText())
            .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,OffsetDateTime.class),r.getString(4)}).optional();
        if(existing.isPresent()){
            var row=existing.get();UUID id=(UUID)row[0];
            if(row[1].equals(submission)||created.isBefore((OffsetDateTime)row[2])){
                if(manual&&"CANCELLED".equals(row[3]))jdbc.sql("UPDATE solution_export SET status='QUEUED',attempts=0,error_code=NULL,next_at=?,target_json=? WHERE id=?").param(now()).param(target).param(id).update();
                return id;
            }
            jdbc.sql("UPDATE solution_export SET submission_id=?,submitted_at=?,payload_json=?,target_json=?,revision=revision+1,status=CASE WHEN status='RUNNING' THEN status ELSE 'QUEUED' END,attempts=0,next_at=?,error_code=NULL,updated_at=? WHERE id=?")
                .param(submission).param(created).param(JudgeJson.canonical(p)).param(target).param(now()).param(now()).param(id).update();return id;
        }
        UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO solution_export(id,user_id,provider,target_sha256,target_json,problem_version,language,submission_id,submitted_at,payload_json) VALUES (?,?,?,?,?,?,?,?,?,?)")
            .param(id).param(user).param(provider).param(hash).param(target).param(p.path("problemVersion").asText()).param(p.path("language").asText()).param(submission).param(created).param(JudgeJson.canonical(p)).update();return id;
    }
    static String targetHash(JsonNode target){
        var identity=ExportRemote.obj().put("id",target.path("id").asText());
        if(target.has("repo"))identity.put("branch",target.path("branch").asText()).put("prefix",target.path("prefix").asText());
        return JudgeJson.hash(JudgeJson.canonical(identity));
    }
    List<Delivery> deliveries(String username,UUID submission){
        var query=jdbc.sql("SELECT * FROM solution_export WHERE user_id=?"+(submission==null?"":" AND submission_id=?")+" ORDER BY updated_at DESC LIMIT 50").param(owner(username));if(submission!=null)query.param(submission);
        return query.query((r,n)->new Delivery(r.getObject("id",UUID.class),r.getString("provider"),r.getString("problem_version"),r.getString("language"),r.getObject("submission_id",UUID.class),r.getString("status"),r.getString("error_code"),r.getString("external_url"),r.getObject("updated_at",OffsetDateTime.class))).list();
    }
    void retry(String username,UUID id){
        UUID user=owner(username);int changed=jdbc.sql("UPDATE solution_export SET status='QUEUED',attempts=0,next_at=?,error_code=NULL WHERE id=? AND user_id=? AND status IN ('FAILED','RETRY') AND EXISTS (SELECT 1 FROM export_connection c WHERE c.user_id=solution_export.user_id AND c.provider=solution_export.provider AND c.status='CONNECTED')")
            .param(now()).param(id).param(user).update();if(changed!=1)throw new AccountException(409,"연결 상태와 전송 상태를 확인해 주세요.");
    }
    Work claim(){return tx.execute(s->{
        jdbc.sql("UPDATE solution_export SET status=CASE WHEN attempts>=6 THEN 'FAILED' ELSE 'RETRY' END,error_code='DELIVERY_INTERRUPTED',lease_token=NULL,lease_until=NULL,next_at=? WHERE status='RUNNING' AND lease_until<?").param(now()).param(now()).update();
        var candidates=jdbc.sql("SELECT id FROM solution_export WHERE status IN ('QUEUED','RETRY') AND next_at<=? ORDER BY next_at,id LIMIT 1 FOR UPDATE").param(now()).query(UUID.class).list();
        if(candidates.isEmpty())return null;UUID id=candidates.getFirst(),lease=UUID.randomUUID();
        jdbc.sql("UPDATE solution_export SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=? WHERE id=?").param(lease).param(now().plusSeconds(60)).param(now()).param(id).update();
        return jdbc.sql("SELECT * FROM solution_export WHERE id=?").param(id).query((r,n)->new Work(id,r.getObject("user_id",UUID.class),r.getString("provider"),lease,r.getInt("revision"),r.getInt("attempts"),JudgeJson.parse(r.getString("target_json")),JudgeJson.parse(r.getString("payload_json")),(ObjectNode)JudgeJson.parse(r.getString("remote_json")))).single();
    });}
    void fence(Work w){
        if(jdbc.sql("UPDATE solution_export SET lease_until=? WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>? AND EXISTS (SELECT 1 FROM export_connection c WHERE c.user_id=solution_export.user_id AND c.provider=solution_export.provider AND c.status='CONNECTED')")
            .param(now().plusSeconds(60)).param(w.id).param(w.lease).param(now()).update()!=1)throw new ExportRemote.Failure("CONNECTION_CHANGED",false);
    }
    void checkpoint(Work w,ObjectNode state){fence(w);if(jdbc.sql("UPDATE solution_export SET remote_json=? WHERE id=? AND lease_token=? AND status='RUNNING'")
        .param(JudgeJson.canonical(state)).param(w.id).param(w.lease).update()!=1)throw new ExportRemote.Failure("CONNECTION_CHANGED",false);}
    void finish(Work w,String url,ExportRemote.Failure failure){tx.executeWithoutResult(s->{
        jdbc.sql("SELECT id FROM export_connection WHERE user_id=? AND provider=? FOR UPDATE").param(w.user).param(w.provider).query(UUID.class).optional();
        if(jdbc.sql("SELECT id FROM solution_export WHERE id=? AND lease_token=? AND status='RUNNING' FOR UPDATE").param(w.id).param(w.lease).query(UUID.class).optional().isEmpty())return;
        String status=failure==null?"SUCCEEDED":failure.retryable&&w.attempts<6?"RETRY":"FAILED";
        if(failure!=null&&failure.code.equals("RECONNECT_REQUIRED"))jdbc.sql("UPDATE export_connection SET status='RECONNECT_REQUIRED',auto_enabled=false WHERE user_id=? AND provider=?").param(w.user).param(w.provider).update();
        jdbc.sql("UPDATE solution_export SET status=CASE WHEN revision<>? THEN 'QUEUED' ELSE ? END,external_url=COALESCE(?,external_url),error_code=?,next_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_token=? AND status='RUNNING'")
            .param(w.revision).param(status).param(url).param(failure==null?null:failure.code).param(now().plusSeconds(failure==null?0:Math.min(900,10L*(1L<<Math.min(w.attempts,6))))).param(now()).param(w.id).param(w.lease).update();
    });}
    boolean runOne(){var w=claim();if(w==null)return false;
        try{
            if(!settings.ready(w.provider))throw new ExportRemote.Failure("SERVICE_UNAVAILABLE",false);
            var c=connection(w.user,w.provider);String token=access(c);fence(w);
            String url=remote.publish(w.provider,token,w.target,w.payload,w.remote,state->checkpoint(w,state),()->fence(w));finish(w,url,null);
        }catch(ExportRemote.Failure e){finish(w,null,e);}catch(AccountException e){finish(w,null,new ExportRemote.Failure("CONNECTION_CHANGED",false));}
        catch(RuntimeException e){finish(w,null,new ExportRemote.Failure("DELIVERY_ERROR",true));}
        return true;
    }
}
