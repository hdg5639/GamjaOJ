package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** All mutations run under the existing ai_budget_lock in the caller's transaction. */
@Service
class VerificationLedger {
    private final JdbcClient jdbc;
    VerificationLedger(JdbcClient jdbc) { this.jdbc=jdbc; }
    record Entry(UUID id, UUID jobId, int revision, String snapshotJson, String snapshotSha256) {}
    private static String version(UUID job,int revision) { return "generated-"+job+"-r"+revision; }
    Entry entry(UUID job,int revision) {
        return jdbc.sql("SELECT id,job_id,revision,snapshot_json,snapshot_sha256 FROM generation_evidence WHERE job_id=? AND revision=?")
                .param(job).param(revision).query(Entry.class).optional().orElse(null);
    }
    boolean active(Entry entry) {
        return entry!=null && JudgeJson.hash(entry.snapshotJson()).equals(entry.snapshotSha256())
                && jdbc.sql("SELECT count(*) FROM generation_evidence_revocation WHERE evidence_id=?").param(entry.id()).query(Integer.class).single()==0;
    }
    UUID freeze(UUID job,int revision,JsonNode report) {
        if(!valid(job))throw new IllegalStateException("Revoked generation dependency");
        var payload=jdbc.sql("SELECT g.artifacts_json,g.oracle_json,g.structure_contract,p.package_json,p.runtime_image,p.runner_policy FROM generation_job g JOIN problem_version p ON p.id=? WHERE g.id=?")
                .param(version(job,revision)).param(job).query((r,n)-> {
                    var snapshot=JudgeJson.JSON.createObjectNode().put("format","generation-evidence-v1")
                            .put("jobId",job.toString()).put("revision",revision).put("contract",r.getString(3))
                            .put("runtimeImage",r.getString(5)).put("runnerPolicy",r.getString(6));
                    snapshot.set("artifacts",JudgeJson.parse(r.getString(1)));snapshot.set("oracle",JudgeJson.parse(r.getString(2)));
                    snapshot.set("package",JudgeJson.parse(r.getString(4)));
                    var validation=report.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)validation).remove("evidenceId");
                    snapshot.set("validation",validation);return snapshot;
                }).single();
        var parent=jdbc.sql("SELECT source_evidence_id FROM generation_dependency WHERE job_id=?").param(job).query(UUID.class).optional();
        parent.ifPresent(id->payload.put("sourceEvidenceId",id.toString()));
        String json=JudgeJson.canonical(payload);var existing=entry(job,revision);
        if(existing!=null) {
            if(!existing.snapshotJson().equals(json)||!active(existing))throw new IllegalStateException("Evidence is immutable or revoked");
            return existing.id();
        }
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO generation_evidence(id,job_id,revision,snapshot_json,snapshot_sha256) VALUES (?,?,?,?,?)")
                .param(id).param(job).param(revision).param(json).param(JudgeJson.hash(json)).update();return id;
    }
    boolean bind(UUID target,UUID source,JsonNode artifacts,JsonNode oracle,JsonNode validation) {
        var data=jdbc.sql("SELECT revision FROM generation_job WHERE id=? AND owner_id=(SELECT owner_id FROM generation_job WHERE id=?) AND status='READY'")
                .param(source).param(target).query(Integer.class).optional();
        if(data.isEmpty()||!valid(source))return false;
        var evidence=entry(source,data.get());if(!active(evidence)||!available(source,data.get()))return false;
        var saved=JudgeJson.parse(evidence.snapshotJson());
        var validated=validation.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)validated).remove("evidenceId");
        if(!saved.path("artifacts").equals(artifacts)||!saved.path("oracle").equals(oracle)||!saved.path("validation").equals(validated))return false;
        jdbc.sql("INSERT INTO generation_dependency(job_id,source_job_id,source_revision,source_evidence_id,source_evidence_sha256) VALUES (?,?,?,?,?)")
                .param(target).param(source).param(data.get()).param(evidence.id()).param(evidence.snapshotSha256()).update();return true;
    }
    private boolean available(UUID job,int revision) {
        return jdbc.sql("SELECT count(*) FROM problem_version WHERE id=? AND ready=true AND review_hold=false")
                .param(version(job,revision)).query(Integer.class).single()==1;
    }
    boolean valid(UUID job) {
        var seen=new HashSet<UUID>();UUID current=job;
        while(seen.add(current)) {
            var dependency=jdbc.sql("SELECT source_job_id,source_revision,source_evidence_id,source_evidence_sha256 FROM generation_dependency WHERE job_id=?")
                    .param(current).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getObject(3,UUID.class),r.getString(4)}).optional();
            if(dependency.isEmpty()) {
                // Never silently detach a legacy/partial snapshot from its source.
                return jdbc.sql("SELECT count(*) FROM generation_job WHERE id=? AND structure_reuse_json IS NULL").param(current).query(Integer.class).single()==1;
            }
            var d=dependency.get();UUID parent=(UUID)d[0];int revision=(Integer)d[1];var evidence=entry(parent,revision);
            if(!active(evidence)||!evidence.id().equals(d[2])||!evidence.snapshotSha256().equals(d[3])||!available(parent,revision))return false;
            if(jdbc.sql("SELECT count(*) FROM generation_job a JOIN generation_job b ON a.owner_id=b.owner_id WHERE a.id=? AND b.id=?")
                    .param(current).param(parent).query(Integer.class).single()!=1)return false;
            current=parent;
        }
        return false;
    }
    void block(UUID job) {
        jdbc.sql("UPDATE generation_job SET status='NEEDS_REVIEW',error_code='STRUCTURE_EVIDENCE_REVOKED',updated_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(job).update();
    }
    void revokeTree(UUID owner,UUID root,String reason) {
        // Include old snapshots for conservative hold propagation; do not certify them as evidence.
        var links=new HashMap<UUID,UUID>();
        jdbc.sql("SELECT id,structure_reuse_json FROM generation_job WHERE owner_id=?").param(owner).query((r,n)-> {
            UUID id=r.getObject(1,UUID.class);String raw=r.getString(2);
            if(raw!=null) {var parent=JudgeJson.parse(raw).path("sourceJobId").asText();if(!parent.isBlank())links.put(id,UUID.fromString(parent));}
            return id;
        }).list();
        jdbc.sql("SELECT d.job_id,d.source_job_id FROM generation_dependency d JOIN generation_job g ON g.id=d.job_id WHERE g.owner_id=?")
                .param(owner).query((r,n)-> {links.put(r.getObject(1,UUID.class),r.getObject(2,UUID.class));return 0;}).list();
        var affected=new HashSet<UUID>();affected.add(root);
        boolean changed;do {changed=false;for(var link:links.entrySet())if(affected.contains(link.getValue()))changed|=affected.add(link.getKey());}while(changed);
        for(UUID id:affected) {
            String propagated=id.equals(root)?reason:"원본 검증 근거 보류: "+root+" · "+reason;
            propagated=propagated.substring(0,Math.min(500,propagated.length()));
            for(var evidence:jdbc.sql("SELECT id FROM generation_evidence WHERE job_id=?").param(id).query(UUID.class).list())
                if(jdbc.sql("SELECT count(*) FROM generation_evidence_revocation WHERE evidence_id=?").param(evidence).query(Integer.class).single()==0)
                    jdbc.sql("INSERT INTO generation_evidence_revocation(evidence_id,root_job_id,reason) VALUES (?,?,?)").param(evidence).param(root).param(propagated).update();
            // Keep READY records and every prior attempt/report; only new use is held.
            jdbc.sql("UPDATE problem_version SET review_hold=true,review_reason=?,review_held_at=CURRENT_TIMESTAMP WHERE owner_id=? AND review_hold=false AND id IN (SELECT CONCAT(CONCAT(CONCAT('generated-',CAST(id AS VARCHAR(36))),'-r'),CAST(revision AS VARCHAR(10))) FROM generation_job WHERE id=?)")
                    .param(propagated).param(owner).param(id).update();
            jdbc.sql("UPDATE generation_job SET status=CASE WHEN status IN ('QUEUED','AWAITING_REVIEW','VALIDATING') THEN 'NEEDS_REVIEW' ELSE status END,error_code='STRUCTURE_EVIDENCE_REVOKED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner_id=?")
                    .param(id).param(owner).update();
        }
    }
}
