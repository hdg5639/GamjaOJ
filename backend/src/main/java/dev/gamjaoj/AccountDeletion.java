package dev.gamjaoj;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard account deletion. The account row and all private data are deleted. Problems and rules other members
 * can use (shared, or already referenced by another member's work) move to one non-login archive account so
 * they neither disappear from others' history nor turn into official content (owner_id NULL means official).
 */
@Service
class AccountDeletion {
    /** Signup allows only [a-z0-9_], so this name can never be registered or used to log in. */
    static final String ARCHIVE_USERNAME="#withdrawn";
    record Result(int keptProblems,int keptRules) {}
    private final JdbcClient jdbc;private final PasswordEncoder passwords;private final GenerationSpecDrafts drafts;
    AccountDeletion(JdbcClient jdbc,PasswordEncoder passwords,GenerationSpecDrafts drafts){this.jdbc=jdbc;this.passwords=passwords;this.drafts=drafts;}

    private UUID archive() {return archive(jdbc);}
    /** The shared non-login owner of content kept for other members after its author removed it. */
    static UUID archive(JdbcClient jdbc) {
        var existing=jdbc.sql("SELECT id FROM app_user WHERE username=?").param(ARCHIVE_USERNAME).query(UUID.class).optional();
        if(existing.isPresent())return existing.get();
        UUID id=UUID.randomUUID();
        // "!" is not a valid BCrypt hash, so no password can match.
        jdbc.sql("INSERT INTO app_user(id,username,password_hash,nickname) VALUES (?,?,?,?)").param(id).param(ARCHIVE_USERNAME).param("!").param("탈퇴한 회원").update();
        return id;
    }

    @Transactional
    public Result delete(String username,String password,String confirmation) {
        if(ARCHIVE_USERNAME.equals(username))throw new AccountException(403,"삭제할 수 없는 계정이에요.");
        var row=jdbc.sql("SELECT id,password_hash FROM app_user WHERE username=? FOR UPDATE").param(username)
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2)}).optional().orElseThrow(()->new AccountException(401,"다시 로그인해 주세요."));
        UUID user=(UUID)row[0];
        if(!username.equals(confirmation))throw new AccountException(400,"확인을 위해 아이디를 정확히 입력해 주세요.");
        if(password==null||!passwords.matches(password,(String)row[1]))throw new AccountException(403,"비밀번호가 맞지 않아요.");
        if(active(user))throw new AccountException(409,"진행 중인 채점·출제·규칙 등록·AI 요청이 끝난 뒤 탈퇴할 수 있어요.");
        UUID archive=archive();

        // 1. Problems other members can see or already used stay, with catalog labels copied onto the problem.
        var kept=jdbc.sql("""
                SELECT p.id FROM problem_version p WHERE p.owner_id=? AND p.diagnostic_only=false AND (p.shared=true
                  OR EXISTS (SELECT 1 FROM submission s WHERE s.problem_version=p.id AND s.user_id<>?)
                  OR EXISTS (SELECT 1 FROM training_session t WHERE t.problem_version=p.id AND t.user_id<>?)
                  OR EXISTS (SELECT 1 FROM practice_followup f WHERE f.source_version=p.id AND f.user_id<>?))""")
                .param(user).param(user).param(user).param(user).query(String.class).list();
        for(String id:kept)moveToArchive(jdbc,id,archive);
        // 2. Shared rules, or rules another member generated from, stay; detach them from sources that are deleted below.
        var keptRules=jdbc.sql("""
                SELECT f.id FROM hybrid_rule_family f WHERE f.owner_id=? AND (f.shared=true OR EXISTS (
                  SELECT 1 FROM hybrid_rule_version v JOIN hybrid_public_request r ON r.rule_version_id=v.id JOIN hybrid_generation g ON g.id=r.generation_id
                  WHERE v.family_id=f.id AND g.owner_id<>?))""").param(user).param(user).query(String.class).list();
        for(String family:keptRules) {
            jdbc.sql("UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL,source_generation_id=NULL WHERE rule_version_id IN (SELECT id FROM hybrid_rule_version WHERE family_id=?)").param(family).update();
            jdbc.sql("UPDATE hybrid_rule_family SET owner_id=? WHERE id=?").param(archive).param(family).update();
        }
        // Reference artifacts of rules this account does not own (built-in or other members') may have been qualified
        // from this account's generations or onboarding; they cascade on their source, so detach them first.
        jdbc.sql("""
                UPDATE hybrid_rule_artifact SET source_generation_id=NULL WHERE source_generation_id IN (SELECT id FROM hybrid_generation WHERE owner_id=?)
                  AND rule_version_id NOT IN (SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE f.owner_id=?)""").param(user).param(user).update();
        jdbc.sql("""
                UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL WHERE source_onboarding_id IN (SELECT id FROM hybrid_rule_onboarding WHERE owner_id=?)
                  AND rule_version_id NOT IN (SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE f.owner_id=?)""").param(user).param(user).update();
        // 3. Private data, in foreign-key order (several links are NO ACTION and are not cascaded by the account row).
        String jobs="SELECT id FROM generation_job WHERE owner_id=?",specs="SELECT id FROM generation_spec_draft WHERE owner_id=?",mine="SELECT id FROM submission WHERE user_id=?";
        jdbc.sql("DELETE FROM generation_execution WHERE job_id IN ("+jobs+") OR submission_id IN ("+mine+")").param(user).param(user).update();
        jdbc.sql("DELETE FROM generation_attempt WHERE job_id IN ("+jobs+")").param(user).update();
        jdbc.sql("DELETE FROM generation_spec_execution WHERE draft_id IN ("+specs+") OR submission_id IN ("+mine+")").param(user).param(user).update();
        jdbc.sql("DELETE FROM diagnostic_practice_plan WHERE user_id=?").param(user).update();
        jdbc.sql("DELETE FROM practice_followup WHERE user_id=?").param(user).update();
        jdbc.sql("DELETE FROM submission WHERE user_id=?").param(user).update();
        jdbc.sql("DELETE FROM training_session WHERE user_id=?").param(user).update();
        jdbc.sql("DELETE FROM hybrid_generation WHERE owner_id=?").param(user).update();
        jdbc.sql("DELETE FROM generation_job WHERE owner_id=?").param(user).update();
        jdbc.sql("DELETE FROM generation_spec_draft WHERE owner_id=?").param(user).update();
        jdbc.sql("DELETE FROM problem_version WHERE owner_id=?").param(user).update();
        jdbc.sql("DELETE FROM spring_session WHERE principal_name=?").param(username).update();
        // Remaining rows (AI tasks, diagnostics, grants, private rules, onboarding) cascade from the account row.
        jdbc.sql("DELETE FROM app_user WHERE id=?").param(user).update();
        return new Result(kept.size(),keptRules.size());
    }

    /** Keeps a problem other members rely on: owned by the archive account with its catalog labels copied onto it. */
    static void moveToArchive(JdbcClient jdbc,String id,UUID archive) {
        var metadata=jdbc.sql("SELECT p.id,p.catalog_category,p.catalog_tags,p.catalog_difficulty,g.template_id,g.focus,d.spec_json FROM problem_version p "
                +"LEFT JOIN generation_job g ON p.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10))) "
                +"LEFT JOIN generation_spec_draft d ON p.id=CONCAT('experimental-check-',CAST(d.id AS VARCHAR(36))) WHERE p.id=?")
                .param(id).query((r,n)->ProblemCatalogMetadata.read(r)).single();
        jdbc.sql("UPDATE problem_version SET owner_id=?,catalog_category=?,catalog_tags=? WHERE id=?")
                .param(archive).param(metadata.category()).param(String.join(",",metadata.tags())).param(id).update();
    }
    private boolean active(UUID user) {
        int judging=jdbc.sql("""
                SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.user_id=? AND j.status<>'FINISHED'
                  AND (s.hybrid_branch_id IS NULL OR EXISTS (SELECT 1 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id
                    WHERE b.id=s.hybrid_branch_id AND g.deadline_at>CURRENT_TIMESTAMP))""").param(user).query(Integer.class).single();
        return judging>0
                || jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(user).query(Integer.class).single()>0
                || drafts.active(user)
                || HybridAdmission.active(jdbc,user)
                || jdbc.sql("SELECT count(*) FROM hybrid_rule_onboarding WHERE owner_id=? AND status IN ('QUEUED','AUTHORING','AUTHORED','ORACLE','QUALIFYING') AND deadline_at>CURRENT_TIMESTAMP").param(user).query(Integer.class).single()>0
                || jdbc.sql("SELECT count(*) FROM ai_task WHERE user_id=? AND status IN ('QUEUED','RUNNING')").param(user).query(Integer.class).single()>0;
    }
    static final List<String> DELETED=List.of("계정·닉네임·학습 목표","제출·실행 기록과 채점 결과","훈련·후속 연습","진단·평가·학습 계획","AI 분석·힌트 요청","문제 생성 요청과 비공개 문제","비공개 규칙과 규칙 등록 요청","로그인 세션");
}
