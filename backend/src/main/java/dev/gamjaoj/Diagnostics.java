package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Diagnostics {
    private final JdbcClient jdbc;
    public Diagnostics(JdbcClient jdbc) { this.jdbc=jdbc; }
    public record Item(UUID id,int position,String category,String difficulty,String problemVersion,
                       String status,int attempts,int pending,boolean externallySeen) {}
    public record Example(String input,String output) {}
    public record Question(UUID itemId,String problemVersion,String title,String statement,String sampleInput,String sampleOutput,List<Example> examples,List<LanguageProfiles.Option> languages) {}
    /** The first test and the EX-prefixed tests right after it are public examples; every later test stays hidden. */
    static List<Example> examples(JsonNode tests) {
        var out=new java.util.ArrayList<Example>();
        for(int i=0;i<tests.size()&&(i==0||tests.get(i).path("id").asText().startsWith("EX"));i++)
            out.add(new Example(tests.get(i).path("input").asText(),tests.get(i).path("output").asText()));
        return out;
    }
    public record View(UUID id,String bankId,String status,List<Item> items,Question current,UUID sourceSessionId) {}
    record Snapshot(String json,String hash,String image,String policy,String limits) {}

    public record Bank(String id,List<String> categories,int questionCount) {}
    public List<Bank> banks() {
        return jdbc.sql("SELECT bank.id FROM diagnostic_bank bank WHERE bank.reviewed=true AND NOT EXISTS (SELECT 1 FROM diagnostic_bank_item i JOIN diagnostic_reassessment_pair r ON r.target_version=i.problem_version WHERE i.bank_id=bank.id AND r.reviewed=true) ORDER BY bank.id").query(String.class).list().stream().map(id->{
            var categories=jdbc.sql("SELECT category FROM diagnostic_bank_item WHERE bank_id=? GROUP BY category ORDER BY category")
                    .param(id).query(String.class).list();
            int count=jdbc.sql("SELECT count(*) FROM diagnostic_bank_item WHERE bank_id=?").param(id).query(Integer.class).single();
            int unavailable=jdbc.sql("SELECT count(*) FROM diagnostic_bank_item i JOIN problem_version p ON p.id=i.problem_version WHERE i.bank_id=? AND (p.ready=false OR p.review_hold=true OR p.diagnostic_only=false OR p.owner_id IS NOT NULL)")
                    .param(id).query(Integer.class).single();
            return count>0 && count==categories.size()*2 && unavailable==0?new Bank(id,categories,count):null;
        }).filter(java.util.Objects::nonNull).toList();
    }

    private UUID owner(String name) {
        return jdbc.sql("SELECT id FROM app_user WHERE username=? FOR UPDATE").param(name).query(UUID.class)
                .optional().orElseThrow(()->new AccountException(401,"다시 로그인해 주세요."));
    }
    @Transactional
    public View start(String name,UUID id,String bank) { return start(name,id,bank,null); }
    @Transactional
    public View start(String name,UUID id,String bank,List<String> categories) {
        return startInternal(name,id,bank,categories,null);
    }
    @Transactional
    public View reassess(String name,UUID id,UUID source,String bank,List<String> categories) {
        if(source==null||categories==null)throw new AccountException(400,"재평가할 원래 진단과 분야를 선택해 주세요.");
        return startInternal(name,id,bank,categories,source);
    }
    private View startInternal(String name,UUID id,String bank,List<String> categories,UUID source) {
        if(categories!=null && (categories.isEmpty() || categories.size()>20 || categories.stream().anyMatch(c->c==null||c.isBlank()||c.length()>80)
                || categories.stream().distinct().count()!=categories.size()))
            throw new AccountException(400,"진단할 분야를 중복 없이 선택해 주세요.");
        String requested=categories==null?null:JudgeJson.canonical(JudgeJson.JSON.valueToTree(categories.stream().sorted().toList()));
        UUID user=owner(name);
        if(jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE id=? AND user_id=?").param(id).param(user).query(Integer.class).single()>0) {
            View saved=view(user,id);
            String previous=jdbc.sql("SELECT requested_categories_json FROM diagnostic_session WHERE id=?").param(id)
                    .query((r,n)->new String[]{r.getString(1)}).single()[0];
            if(!saved.bankId().equals(bank)||!java.util.Objects.equals(previous,requested)||!java.util.Objects.equals(saved.sourceSessionId(),source))
                throw new AccountException(409,"같은 요청 키의 진단 은행이나 선택 분야가 달라요.");
            return saved;
        }
        if(jdbc.sql("SELECT count(*) FROM diagnostic_session WHERE id=? OR open_owner=?").param(id).param(user).query(Integer.class).single()>0)
            throw new AccountException(409,"진행 중인 진단을 이어서 진행해 주세요.");
        if(jdbc.sql("SELECT count(*) FROM diagnostic_bank WHERE id=? AND reviewed=true").param(bank).query(Integer.class).single()==0)
            throw new AccountException(404,"검토가 완료된 진단 은행을 찾을 수 없어요.");
        var rows=bankItems(bank,categories);
        if(categories!=null) {
            var available=rows.stream().map(BankItem::category).collect(java.util.stream.Collectors.toSet());
            if(!available.containsAll(categories))throw new AccountException(400,"선택한 분야가 진단 은행에 없어요.");
            rows=rows.stream().filter(row->categories.contains(row.category())).toList();
        }
        if(rows.isEmpty() || rows.stream().collect(java.util.stream.Collectors.groupingBy(BankItem::category)).values().stream()
                .anyMatch(pair->pair.size()!=2 || pair.stream().map(BankItem::difficulty).distinct().count()!=2))
            throw new AccountException(409,"카테고리별 하·중 문항 쌍이 준비되지 않았어요.");
        String correspondence=source==null?null:validateReassessment(user,source,rows);
        jdbc.sql("INSERT INTO diagnostic_session(id,user_id,bank_id,status,open_owner,requested_categories_json,source_session_id,correspondence_json) VALUES (?,?,?,'ACTIVE',?,?,?,?)")
                .param(id).param(user).param(bank).param(user).param(requested).param(source).param(correspondence).update();
        for(var row:rows) {
            String content=contentHash(row.json());
            if(jdbc.sql("SELECT count(*) FROM diagnostic_exposure WHERE user_id=? AND content_sha256=?").param(user).param(content).query(Integer.class).single()==0)
                jdbc.sql("INSERT INTO diagnostic_exposure(user_id,content_sha256) VALUES (?,?)").param(user).param(content).update();
        }
        for(var row:rows)jdbc.sql("INSERT INTO diagnostic_item(id,session_id,position,category,difficulty,problem_version,package_json,package_sha256,runtime_image,runner_policy,rubric_json,time_limits_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
                .param(UUID.randomUUID()).param(id).param(row.position()).param(row.category()).param(row.difficulty()).param(row.version())
                .param(row.json()).param(row.hash()).param(row.image()).param(row.policy()).param(row.rubric()).param(row.limits()).update();
        return view(user,id);
    }
    private List<BankItem> bankItems(String bank,List<String> categories) {
        return jdbc.sql("SELECT b.*,p.package_json,p.package_sha256,p.runtime_image,p.runner_policy,p.time_limits_json,p.ready,p.review_hold,p.diagnostic_only,p.owner_id FROM diagnostic_bank_item b JOIN problem_version p ON p.id=b.problem_version WHERE bank_id=? ORDER BY position")
                .param(bank).query((r,n)-> {
                    if(categories!=null&&!categories.contains(r.getString("category")))return null;
                    if(!r.getBoolean("ready")||r.getBoolean("review_hold")||!r.getBoolean("diagnostic_only")||r.getObject("owner_id")!=null)
                        throw new AccountException(409,"진단 문항을 사용할 수 없어요.");
                    String json=r.getString("package_json"),hash=r.getString("package_sha256");
                    if(!JudgeJson.hash(JudgeJson.canonical(JudgeJson.parse(json))).equals(hash))
                        throw new AccountException(409,"진단 문항의 검증 정보를 확인해야 해요.");
                    return new BankItem(r.getInt("position"),r.getString("category"),r.getString("difficulty"),r.getString("problem_version"),json,hash,r.getString("runtime_image"),r.getString("runner_policy"),r.getString("rubric_json"),r.getString("time_limits_json"));
                }).list().stream().filter(java.util.Objects::nonNull).toList();
    }
    @Transactional
    public List<Bank> reassessmentOptions(String name,UUID source) {
        UUID user=owner(name);var original=view(user,source);
        if(!original.status().equals("COMPLETED"))throw new AccountException(409,"완료한 진단에서 재평가를 선택해 주세요.");
        var result=new java.util.ArrayList<Bank>();
        for(String bank:jdbc.sql("SELECT id FROM diagnostic_bank WHERE reviewed=true ORDER BY id").query(String.class).list()) {
            var available=new java.util.ArrayList<String>();
            for(String category:original.items().stream().map(Item::category).distinct().toList()) {
                try {
                    var pair=bankItems(bank,List.of(category));
                    if(pair.size()!=2||pair.stream().map(BankItem::difficulty).distinct().count()!=2)continue;
                    validateReassessment(user,source,pair);available.add(category);
                } catch(AccountException unavailable) { /* Ineligible content must not appear as a selectable pair. */ }
            }
            if(!available.isEmpty())result.add(new Bank(bank,available,available.size()*2));
        }
        return result;
    }
    @Transactional
    public View reportExposure(String name,UUID session,UUID item) {
        UUID user=owner(name);var saved=view(user,session);
        var chosen=saved.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow(()->new AccountException(404,"진단 문항을 찾을 수 없어요."));
        if(chosen.externallySeen())return saved;
        if(saved.sourceSessionId()==null||chosen.pending()>0||(!saved.status().equals("COMPLETED")&&(!saved.status().equals("ACTIVE")||saved.current()==null||!saved.current().itemId().equals(item))))
            throw new AccountException(409,"현재 재평가 문항 또는 완료한 재평가에서 채점이 끝난 뒤 알려 주세요.");
        jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        jdbc.sql("UPDATE diagnostic_item SET externally_seen=true,exposure_reported_at=CURRENT_TIMESTAMP,status=CASE WHEN status='OPEN' THEN 'SKIPPED' ELSE status END WHERE id=?").param(item).update();
        jdbc.sql("UPDATE diagnostic_session SET exposure_revision=exposure_revision+1 WHERE id=?").param(session).update();
        return view(user,session);
    }
    // Version labels do not make an otherwise identical package independent evidence.
    static String contentHash(String json) {
        var content=(com.fasterxml.jackson.databind.node.ObjectNode)JudgeJson.parse(json).deepCopy();
        content.remove("version");return JudgeJson.hash(JudgeJson.canonical(content));
    }
    private String validateReassessment(UUID user,UUID source,List<BankItem> targets) {
        var original=view(user,source);
        if(!original.status().equals("COMPLETED"))throw new AccountException(409,"원래 진단을 마친 뒤 재평가를 선택해 주세요.");
        // Legacy snapshots are also considered assigned, even if the statement was never opened.
        var seen=jdbc.sql("SELECT i.package_json FROM diagnostic_item i JOIN diagnostic_session s ON s.id=i.session_id WHERE s.user_id=?")
                .param(user).query(String.class).list().stream().map(Diagnostics::contentHash).collect(java.util.stream.Collectors.toSet());
        seen.addAll(jdbc.sql("SELECT content_sha256 FROM diagnostic_exposure WHERE user_id=?").param(user).query(String.class).list());
        var mapping=JudgeJson.JSON.createArrayNode();
        var selected=new java.util.HashSet<String>();
        for(var target:targets) {
            String content=contentHash(target.json());
            if(seen.contains(content)||!selected.add(content))throw new AccountException(409,"이미 배정된 문제 또는 중복 문제는 새 재평가에 사용할 수 없어요.");
            var prior=original.items().stream().filter(i->i.category().equals(target.category())&&i.difficulty().equals(target.difficulty())).findFirst()
                    .orElseThrow(()->new AccountException(409,"원래 진단에 포함된 분야의 대응 문항을 선택해 주세요."));
            if(jdbc.sql("SELECT count(*) FROM problem_version WHERE id=? AND review_hold=false AND ready=true")
                    .param(prior.problemVersion()).query(Integer.class).single()!=1)throw new AccountException(409,"원래 진단 문항의 재검토가 끝난 뒤 진행해 주세요.");
            String sourceHash=jdbc.sql("SELECT package_sha256 FROM diagnostic_item WHERE id=?").param(prior.id()).query(String.class).single();
            if(jdbc.sql("SELECT count(*) FROM diagnostic_reassessment_pair WHERE source_version=? AND target_version=? AND source_sha256=? AND target_sha256=? AND reviewed=true")
                    .param(prior.problemVersion()).param(target.version()).param(sourceHash).param(target.hash()).query(Integer.class).single()!=1)
                throw new AccountException(409,"검토된 A/B 대응 문항이 아직 준비되지 않았어요.");
            mapping.addObject().put("sourceItemId",prior.id().toString()).put("sourceVersion",prior.problemVersion()).put("sourceHash",sourceHash)
                    .put("targetVersion",target.version()).put("targetHash",target.hash()).put("category",target.category()).put("difficulty",target.difficulty());
        }
        return JudgeJson.canonical(mapping);
    }
    record BankItem(int position,String category,String difficulty,String version,String json,String hash,String image,String policy,String rubric,String limits) {}

    @Transactional
    public View detail(String name,UUID id) { return view(owner(name),id); }
    @Transactional
    public List<View> history(String name) {
        UUID user=owner(name);
        return jdbc.sql("SELECT id FROM diagnostic_session WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20")
                .param(user).query(UUID.class).list().stream().map(id->view(user,id)).toList();
    }
    @Transactional
    public View state(String name,UUID id,String target) {
        UUID user=owner(name); View saved=view(user,id);
        if(!List.of("PAUSED","ACTIVE").contains(target))throw new AccountException(400,"진단 상태를 확인해 주세요.");
        if(!saved.status().equals("COMPLETED"))jdbc.sql("UPDATE diagnostic_session SET status=? WHERE id=?").param(target).param(id).update();
        return view(user,id);
    }
    /** Ends an open session: every unfinished item is recorded as SKIPPED (unassessed, never weak). */
    @Transactional
    public View finish(String name,UUID session) {
        UUID user=owner(name); View saved=view(user,session);
        if(saved.status().equals("COMPLETED"))return saved; // Replay after completion is a no-op.
        if(saved.items().stream().anyMatch(i->i.pending()>0))throw new AccountException(409,"진행 중인 정식 채점이 끝난 뒤 진단을 끝내 주세요.");
        jdbc.sql("UPDATE diagnostic_item SET status='SKIPPED' WHERE session_id=? AND status='OPEN'").param(session).update();
        return view(user,session);
    }
    @Transactional
    public View skip(String name,UUID session,UUID item) {
        UUID user=owner(name); View saved=view(user,session);
        var chosen=saved.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow(()->new AccountException(404,"진단 문항을 찾을 수 없어요."));
        if(chosen.status().equals("SKIPPED"))return saved; // Retrying skip never skips the next item.
        if(!saved.status().equals("ACTIVE")||saved.current()==null||!saved.current().itemId().equals(item)||chosen.pending()>0)
            throw new AccountException(409,"현재 문항과 진행 중인 채점을 확인해 주세요.");
        jdbc.sql("UPDATE diagnostic_item SET status='SKIPPED' WHERE id=?").param(item).update();
        return view(user,session);
    }
    // Caller owns app_user lock, shared with ordinary submission admission. No judge->owner lock inversion.
    Snapshot admit(UUID user,UUID item,String version,boolean run) {
        var session=jdbc.sql("SELECT d.id FROM diagnostic_session d JOIN diagnostic_item i ON i.session_id=d.id WHERE i.id=? AND d.user_id=?")
                .param(item).param(user).query(UUID.class).optional().orElseThrow(()->new AccountException(404,"진단 문항을 찾을 수 없어요."));
        View saved=view(user,session);
        if(!saved.status().equals("ACTIVE")||saved.current()==null||!saved.current().itemId().equals(item)||!saved.current().problemVersion().equals(version))
            throw new AccountException(409,"진행 중인 진단 문항을 확인해 주세요.");
        Item current=saved.items().stream().filter(i->i.id().equals(item)).findFirst().orElseThrow();
        if(!run && (current.pending()>0||current.attempts()>=5))throw new AccountException(409,"진행 중인 채점이 끝난 뒤 제출해 주세요.");
        return jdbc.sql("SELECT package_json,package_sha256,runtime_image,runner_policy,time_limits_json FROM diagnostic_item WHERE id=?").param(item)
                .query((r,n)->new Snapshot(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).single();
    }
    private View view(UUID user,UUID id) {
        var session=jdbc.sql("SELECT bank_id,status FROM diagnostic_session WHERE id=? AND user_id=?").param(id).param(user)
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional().orElseThrow(()->new AccountException(404,"진단 기록을 찾을 수 없어요."));
        // Freeze admitted results during reconciliation/skip. Judge completion only locks jobs/attempts,
        // never app_user, so this owner->job ordering does not introduce an inverse lock cycle.
        jdbc.sql("SELECT j.submission_id FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.diagnostic_item_id IN (SELECT id FROM diagnostic_item WHERE session_id=?) ORDER BY j.submission_id FOR UPDATE")
                .param(id).query(UUID.class).list();
        // Derived from distinct submissions, never Runner attempts. Persist outcomes for restart and read-only history.
        jdbc.sql("UPDATE diagnostic_item SET status='PASSED' WHERE session_id=? AND status='OPEN' AND EXISTS (SELECT 1 FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id=diagnostic_item.id AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict='AC')").param(id).update();
        jdbc.sql("UPDATE diagnostic_item SET status='EXHAUSTED' WHERE session_id=? AND status='OPEN' AND (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id=diagnostic_item.id AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict<>'IE')>=5").param(id).update();
        var items=jdbc.sql("SELECT i.*, (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id=i.id AND s.run_input IS NULL AND j.status='FINISHED' AND j.verdict<>'IE') AS attempts, (SELECT count(*) FROM submission s JOIN judge_job j ON j.submission_id=s.id WHERE s.diagnostic_item_id=i.id AND s.run_input IS NULL AND j.status<>'FINISHED') AS pending FROM diagnostic_item i WHERE session_id=? ORDER BY position")
                .param(id).query((r,n)->new Item(r.getObject("id",UUID.class),r.getInt("position"),r.getString("category"),r.getString("difficulty"),r.getString("problem_version"),r.getString("status"),r.getInt("attempts"),r.getInt("pending"),r.getBoolean("externally_seen"))).list();
        var current=items.stream().filter(i->i.status().equals("OPEN")).findFirst();
        String status=session[1];
        if(current.isEmpty()) {
            jdbc.sql("UPDATE diagnostic_session SET status='COMPLETED',open_owner=NULL WHERE id=?").param(id).update();status="COMPLETED";
        }
        Question question=current.map(i->{
            JsonNode p=JudgeJson.parse(jdbc.sql("SELECT package_json FROM diagnostic_item WHERE id=?").param(i.id()).query(String.class).single());
            return new Question(i.id(),i.problemVersion(),p.path("title").asText(),p.path("statement").asText(),p.path("tests").path(0).path("input").asText(),p.path("tests").path(0).path("output").asText(),examples(p.path("tests")),LanguageProfiles.options(jdbc.sql("SELECT time_limits_json FROM diagnostic_item WHERE id=?").param(i.id()).query((r,n)->r.getString(1)).optional().orElse(null)));
        }).orElse(null);
        UUID source=jdbc.sql("SELECT source_session_id FROM diagnostic_session WHERE id=?").param(id)
                .query((r,n)->new UUID[]{r.getObject(1,UUID.class)}).single()[0];
        return new View(id,session[0],status,items,question,source);
    }
}
