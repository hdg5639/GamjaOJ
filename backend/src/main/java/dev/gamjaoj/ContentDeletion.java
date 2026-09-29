package dev.gamjaoj;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member deletes one problem or rule they made. Same principle as account deletion: content another member
 * already relies on moves to the non-login archive account (and leaves the catalog); otherwise it is deleted
 * together with the owner's own records on it. Generation histories stay, without a link to the deleted problem.
 */
@Service
class ContentDeletion {
    record Result(String outcome) {
        static final String DELETED="DELETED",ARCHIVED="ARCHIVED";
    }
    private final JdbcClient jdbc;private final Submissions submissions;
    ContentDeletion(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}

    @Transactional
    public Result problem(String username,String version) {
        UUID user=submissions.owner(username,true);
        var owned=jdbc.sql("SELECT count(*) FROM problem_version WHERE id=? AND owner_id=? AND diagnostic_only=false").param(version).param(user).query(Integer.class).single();
        if(owned==0)throw new AccountException(404,"삭제할 수 있는 내 문제를 찾을 수 없어요.");
        if(jdbc.sql("SELECT count(*) FROM judge_job j JOIN submission s ON s.id=j.submission_id WHERE s.problem_version=? AND j.status<>'FINISHED'")
                .param(version).query(Integer.class).single()>0)
            throw new AccountException(409,"이 문제의 채점이 끝난 뒤 삭제할 수 있어요.");
        boolean others=jdbc.sql("""
                SELECT count(*) FROM problem_version p WHERE p.id=? AND (
                  EXISTS (SELECT 1 FROM submission s WHERE s.problem_version=p.id AND s.user_id<>?)
                  OR EXISTS (SELECT 1 FROM training_session t WHERE t.problem_version=p.id AND t.user_id<>?)
                  OR EXISTS (SELECT 1 FROM practice_followup f WHERE f.source_version=p.id AND f.user_id<>?))""")
                .param(version).param(user).param(user).param(user).query(Integer.class).single()>0;
        if(others) {
            // Other members' submissions and training keep their problem; it just leaves the catalog.
            AccountDeletion.moveToArchive(jdbc,version,AccountDeletion.archive(jdbc));
            jdbc.sql("UPDATE problem_version SET shared=false WHERE id=?").param(version).update();
            return new Result(Result.ARCHIVED);
        }
        String subs="SELECT id FROM submission WHERE problem_version=?";
        jdbc.sql("UPDATE hybrid_generation SET published_version_id=NULL WHERE published_version_id=?").param(version).update();
        jdbc.sql("DELETE FROM generation_execution WHERE submission_id IN ("+subs+")").param(version).update();
        jdbc.sql("DELETE FROM generation_spec_execution WHERE submission_id IN ("+subs+")").param(version).update();
        jdbc.sql("DELETE FROM practice_followup WHERE source_version=?").param(version).update();
        // Judge jobs/attempts, AI tasks and execution checks cascade from the submissions.
        jdbc.sql("DELETE FROM submission WHERE problem_version=?").param(version).update();
        jdbc.sql("DELETE FROM training_session WHERE problem_version=?").param(version).update();
        jdbc.sql("DELETE FROM code_draft WHERE scope=?").param("p:"+version).update();
        jdbc.sql("DELETE FROM problem_version WHERE id=?").param(version).update();
        return new Result(Result.DELETED);
    }

    @Transactional
    public Result rule(String username,String versionId) {
        UUID user=submissions.owner(username,true);
        String family=jdbc.sql("SELECT f.id FROM hybrid_rule_family f JOIN hybrid_rule_version v ON v.family_id=f.id WHERE v.id=? AND f.owner_id=? FOR UPDATE")
                .param(versionId).param(user).query(String.class).optional()
                .orElseThrow(()->new AccountException(404,"삭제할 수 있는 내 규칙을 찾을 수 없어요."));
        List<String> versions=jdbc.sql("SELECT id FROM hybrid_rule_version WHERE family_id=?").param(family).query(String.class).list();
        String in="SELECT id FROM hybrid_rule_version WHERE family_id=?";
        if(jdbc.sql("SELECT count(*) FROM hybrid_generation g JOIN hybrid_public_request r ON r.generation_id=g.id WHERE r.rule_version_id IN ("+in+")"
                +" AND g.status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') AND g.deadline_at>CURRENT_TIMESTAMP")
                .param(family).query(Integer.class).single()>0)
            throw new AccountException(409,"이 규칙으로 진행 중인 문제 생성이 끝난 뒤 삭제할 수 있어요.");
        boolean others=jdbc.sql("SELECT count(*) FROM hybrid_generation g JOIN hybrid_public_request r ON r.generation_id=g.id WHERE r.rule_version_id IN ("+in+") AND g.owner_id<>?")
                .param(family).param(user).query(Integer.class).single()>0;
        // The registration requests that produced this rule leave the member's list either way.
        for(String v:versions)jdbc.sql("DELETE FROM hybrid_rule_onboarding WHERE owner_id=? AND version_id=?").param(user).param(v).update();
        if(others) {
            // Other members generated from it: keep the rule rows their generations point to, but nobody can select it.
            jdbc.sql("UPDATE hybrid_rule_artifact SET source_onboarding_id=NULL WHERE rule_version_id IN ("+in+")").param(family).update();
            jdbc.sql("UPDATE hybrid_rule_version SET status='RETIRED',status_reason='OWNER_DELETED',updated_at=CURRENT_TIMESTAMP WHERE family_id=?").param(family).update();
            jdbc.sql("UPDATE hybrid_rule_family SET owner_id=?,shared=false WHERE id=?").param(AccountDeletion.archive(jdbc)).param(family).update();
            return new Result(Result.ARCHIVED);
        }
        jdbc.sql("UPDATE hybrid_public_request SET rule_version_id=NULL,reference_artifact_id=NULL WHERE rule_version_id IN ("+in+")").param(family).update();
        jdbc.sql("DELETE FROM hybrid_rule_artifact WHERE rule_version_id IN ("+in+")").param(family).update();
        jdbc.sql("DELETE FROM hybrid_rule_version WHERE family_id=?").param(family).update();
        jdbc.sql("DELETE FROM hybrid_rule_family WHERE id=?").param(family).update();
        return new Result(Result.DELETED);
    }
}
