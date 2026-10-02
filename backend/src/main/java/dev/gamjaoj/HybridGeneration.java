package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted hybrid DAG. HybridExecution supplies budgeted dispatch; publication remains gated. */
@Service
class HybridGeneration {
    enum Role { CONTRACT, CORE, PRESENTATION, READER, VALIDATION, CONTENT_REVIEW }
    private static final Set<String> TERMINAL=Set.of("HELD","FAILED","CANCELLED","DEADLINE_EXCEEDED","PUBLISHED");
    private final JdbcClient jdbc;private final Submissions submissions;private final AiSettings settings;
    HybridGeneration(JdbcClient jdbc,Submissions submissions,AiSettings settings){this.jdbc=jdbc;this.submissions=submissions;this.settings=settings;}
    /** Whole-generation deadline. Registered rules with large inputs need several exclusive Runner replays, so 120 s was too short. */
    long deadlineSeconds() {
        try{return Math.max(120,Math.min(1800,Long.parseLong(settings.value("HYBRID_GENERATION_SECONDS","600").trim())));}catch(NumberFormatException e){return 600;}
    }
    record Job(UUID id,UUID owner,int revision,String status,int repairs,boolean shared,String contractHash,
               String publicHash,String error,OffsetDateTime acceptedAt,OffsetDateTime deadlineAt) {}
    record Branch(UUID id,UUID generation,int revision,Role role,int attempt,String status,JsonNode input,
                  String inputHash,String contractHash,String publicHash,UUID token,String completion,String outputHash) {}
    record Assignment(UUID branchId,UUID generationId,int revision,Role role,UUID token,String inputHash,
                      String contractHash,String publicHash,JsonNode input) {}
    record Completion(UUID branchId,int revision,Role role,UUID token,String inputHash,String contractHash,
                      String publicHash,JsonNode payload,JsonNode usage,String error) {}
    record Progress(UUID id,String pipelineVersion,int revision,String status,int repairRounds,boolean shared,
                    String contractHash,String publicHash,String error,OffsetDateTime acceptedAt,
                    OffsetDateTime deadlineAt,Map<Role,String> branches,String publishedVersionId,boolean problemHeld,String profileId,
                    boolean referenceReused) {}
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private AccountException conflict(){return new AccountException(409,"현재 출제 단계와 맞지 않는 결과예요.");}
    private Job job(UUID id,boolean lock) {
        return jdbc.sql("SELECT * FROM hybrid_generation WHERE id=?"+(lock?" FOR UPDATE":"")).param(id)
                .query((r,n)->new Job(id,r.getObject("owner_id",UUID.class),r.getInt("revision"),r.getString("status"),
                        r.getInt("repair_rounds"),r.getBoolean("share_on_publish"),r.getString("contract_sha256"),
                        r.getString("public_sha256"),r.getString("error_code"),r.getObject("created_at",OffsetDateTime.class),
                        r.getObject("deadline_at",OffsetDateTime.class))).optional()
                .orElseThrow(()->new AccountException(404,"출제 기록을 찾을 수 없어요."));
    }
    private Job owned(String username,UUID id,boolean lock) {
        UUID owner=submissions.owner(username,false);Job j=job(id,lock);
        if(!j.owner.equals(owner))throw new AccountException(404,"출제 기록을 찾을 수 없어요.");return j;
    }
    private List<Branch> branches(UUID id,int revision) {
        return jdbc.sql("SELECT * FROM hybrid_branch WHERE generation_id=? AND revision=? ORDER BY role,attempt")
                .param(id).param(revision).query((r,n)->new Branch(r.getObject("id",UUID.class),id,revision,
                        Role.valueOf(r.getString("role")),r.getInt("attempt"),r.getString("status"),JudgeJson.parse(r.getString("input_json")),
                        r.getString("input_sha256"),r.getString("contract_sha256"),r.getString("public_sha256"),
                        r.getObject("attempt_token",UUID.class),r.getString("completion_json"),r.getString("output_sha256"))).list();
    }
    private Branch branch(UUID branchId) {
        var location=jdbc.sql("SELECT generation_id,revision FROM hybrid_branch WHERE id=?").param(branchId)
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getInt(2)}).optional().orElseThrow(this::conflict);
        return branches((UUID)location[0],(Integer)location[1]).stream().filter(b->b.id.equals(branchId)).findFirst().orElseThrow();
    }
    private Map<Role,Branch> latest(Job j) {
        var out=new EnumMap<Role,Branch>(Role.class);branches(j.id,j.revision).forEach(b->out.put(b.role,b));return out;
    }
    private void budgetLock(){jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private void status(UUID id,String value,String error) {
        jdbc.sql("UPDATE hybrid_generation SET status=?,error_code=?,updated_at=? WHERE id=?")
                .param(value).param(error).param(now()).param(id).update();
        if(TERMINAL.contains(value)&&!Set.of("VALIDATION_ADAPTER_NOT_CONNECTED","CONTENT_REVIEW_REQUIRED").contains(error==null?"":error))jdbc.sql("UPDATE ai_attempt SET status='HYBRID_RELEASED',actual_usd=0,finished_at=CURRENT_TIMESTAMP WHERE status='HYBRID_RESERVED' AND id IN (SELECT attempt_id FROM hybrid_api_reservation WHERE generation_id=?)").param(id).update();
    }
    private void cancelPending(UUID id,int revision,Set<Role> roles) {
        for(Role role:roles)jdbc.sql("UPDATE hybrid_branch SET status='CANCELLED',error_code='SUPERSEDED_OR_STOPPED',finished_at=? WHERE generation_id=? AND revision=? AND role=? AND status IN ('QUEUED','RUNNING','BLOCKED','EARLY')")
                .param(now()).param(id).param(revision).param(role.name()).update();
    }
    private boolean expire(Job j) {
        if((!TERMINAL.contains(j.status)||(j.status.equals("HELD")&&Set.of("VALIDATION_ADAPTER_NOT_CONNECTED","CONTENT_REVIEW_REQUIRED").contains(j.error==null?"":j.error)))&&!now().isBefore(j.deadlineAt)) {
            status(j.id,"DEADLINE_EXCEEDED","INTERACTIVE_DEADLINE_EXCEEDED");
            cancelPending(j.id,j.revision,EnumSet.allOf(Role.class));return true;
        }
        return j.status.equals("DEADLINE_EXCEEDED")||!now().isBefore(j.deadlineAt);
    }
    private Branch enqueue(Job j,Role role,JsonNode input,String state) {
        int attempt=branches(j.id,j.revision).stream().filter(b->b.role==role).mapToInt(Branch::attempt).max().orElse(-1)+1;
        String payload=JudgeJson.canonical(HybridArtifacts.bounded(input));UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,public_sha256,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
                .param(id).param(j.id).param(j.revision).param(role.name()).param(attempt).param(state).param(payload)
                .param(JudgeJson.hash(payload)).param(j.contractHash).param(role==Role.READER||role==Role.VALIDATION||role==Role.CONTENT_REVIEW?j.publicHash:null).param(now()).update();
        return branch(id);
    }

    // Trusted compatibility-adapter entry point. Not exposed to HTTP until budget/profile admission exists.
    @Transactional
    Progress start(String username,UUID id,String request,boolean shared) {
        UUID owner=submissions.owner(username,false);
        if(request==null||request.isBlank()||request.length()>2000)throw new AccountException(400,"출제 요청을 확인해 주세요.");
        jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        String input=JudgeJson.canonical(JudgeJson.JSON.createObjectNode().put("request",request).put("shared",shared));
        if(jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?").param(id).query(Integer.class).single()>0) {
            owned(username,id,true);
            String old=jdbc.sql("SELECT request_sha256 FROM hybrid_generation WHERE id=?").param(id).query(String.class).single();
            if(!old.equals(JudgeJson.hash(input)))throw conflict();return view(username,id);
        }
        OffsetDateTime accepted=now();
        jdbc.sql("INSERT INTO hybrid_generation(id,owner_id,pipeline_version,request_json,request_sha256,status,share_on_publish,created_at,deadline_at,updated_at) VALUES (?,?,?,?,?,'QUEUED',?,?,?,?)")
                .param(id).param(owner).param(HybridArtifacts.VERSION).param(input).param(JudgeJson.hash(input))
                .param(shared).param(accepted).param(accepted.plusSeconds(deadlineSeconds())).param(accepted).update();
        enqueue(job(id,false),Role.CONTRACT,JudgeJson.JSON.createObjectNode().put("request",request),"QUEUED");return view(username,id);
    }
    Progress view(String username,UUID id) {
        Job j=owned(username,id,false);var states=new EnumMap<Role,String>(Role.class);
        for(Role role:Role.values())states.put(role,"NOT_STARTED");latest(j).forEach((r,b)->states.put(r,b.status));
        return new Progress(id,HybridArtifacts.VERSION,j.revision,j.status,j.repairs,j.shared,j.contractHash,j.publicHash,
                j.error,j.acceptedAt,j.deadlineAt,Collections.unmodifiableMap(states),jdbc.sql("SELECT published_version_id FROM hybrid_generation WHERE id=?").param(id).query(String.class).optional().orElse(null),jdbc.sql("SELECT count(*) FROM problem_version WHERE id=(SELECT published_version_id FROM hybrid_generation WHERE id=?) AND review_hold=true").param(id).query(Integer.class).single()>0,jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?").param(id).query(String.class).optional().orElse(null),
                jdbc.sql("SELECT count(*) FROM hybrid_public_request WHERE generation_id=? AND reference_artifact_id IS NOT NULL").param(id).query(Integer.class).single()>0);
    }
    @Transactional
    Assignment claim(UUID id,Role role) {
        budgetLock();
        Job j=job(id,true);if(expire(j)||TERMINAL.contains(j.status)||role==Role.VALIDATION)return null;
        Branch b=latest(j).get(role);if(b==null||!b.status.equals("QUEUED"))return null;
        UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE hybrid_branch SET status='RUNNING',attempt_token=?,started_at=? WHERE id=?")
                .param(token).param(now()).param(b.id).update();
        if(role==Role.CONTRACT)status(id,"DESIGNING",null);
        return new Assignment(b.id,id,j.revision,role,token,b.inputHash,b.contractHash,b.publicHash,b.input.deepCopy());
    }
    @Transactional
    boolean complete(Completion c) {
        budgetLock();
        Branch location=branch(c.branchId);Job j=job(location.generation,true);Branch b=branch(c.branchId);
        if(c.token==null||!c.token.equals(b.token)||c.revision!=b.revision||c.role!=b.role
                ||!Objects.equals(c.inputHash,b.inputHash)||!Objects.equals(c.contractHash,b.contractHash)
                ||!Objects.equals(c.publicHash,b.publicHash))throw conflict();
        // Provider usage is retained even for cancelled/superseded work. This is not an API ledger settlement.
        JsonNode wire=JudgeJson.JSON.valueToTree(c);String completion=JudgeJson.canonical(HybridArtifacts.bounded(wire));
        if(b.completion!=null){if(!b.completion.equals(completion))throw conflict();return b.status.equals("SUCCEEDED");}
        boolean expired=expire(j);
        boolean late=expired||Set.of("FAILED","CANCELLED","DEADLINE_EXCEEDED","PUBLISHED").contains(j.status)||j.revision!=b.revision||!b.status.equals("RUNNING")
                ||!latest(j).get(b.role).id.equals(b.id);
        jdbc.sql("UPDATE hybrid_branch SET completion_json=?,late_result=?,finished_at=? WHERE id=?")
                .param(completion).param(late).param(now()).param(b.id).update();
        if(late)return false;
        try {
            HybridArtifacts.require(c.error==null,"PROVIDER_FAILED");
            JsonNode payload=switch(b.role) {
                case CONTRACT -> HybridArtifacts.contract(c.payload);
                case CORE -> HybridCoreSupport.assemble(b.input,c.payload);
                case PRESENTATION -> HybridPresentationRules.assemble(b.input,c.payload,artifact(latest(j).get(Role.CONTRACT)));
                case READER -> HybridArtifacts.reader(c.payload,b.input.path("semantics"));
                case CONTENT_REVIEW -> HybridArtifacts.contentReview(c.payload,b.inputHash,b.input.has("requirements"),b.input.has("thinkingRubric"));
                case VALIDATION -> throw new HybridArtifacts.Invalid("VALIDATION_ADAPTER_NOT_CONNECTED");
            };
            String raw=JudgeJson.canonical(payload),hash=JudgeJson.hash(raw);
            jdbc.sql("INSERT INTO hybrid_artifact(branch_id,schema_version,prompt_version,payload_json,payload_sha256,created_at) VALUES (?,'1',?,?,?,?)")
                    .param(b.id).param("hybrid-"+b.role.name().toLowerCase(Locale.ROOT)+"-v1").param(raw).param(hash).param(now()).update();
            jdbc.sql("UPDATE hybrid_branch SET status='SUCCEEDED',output_sha256=? WHERE id=?").param(hash).param(b.id).update();
            if(b.role==Role.CONTRACT) {
                jdbc.sql("UPDATE hybrid_generation SET contract_sha256=?,status='BUILDING',updated_at=? WHERE id=?")
                        .param(hash).param(now()).param(j.id).update();
                Job accepted=job(j.id,false);
                var coreInput=JudgeJson.JSON.createObjectNode().set("contract",payload);
                var selected=jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?").param(j.id).query(String.class).optional().map(HybridProfiles::byId);
                if(selected.isPresent()) {
                    HybridArtifacts.require(payload.equals(selected.get().contract()),"ADMISSION_PROFILE_FENCE");
                    ((com.fasterxml.jackson.databind.node.ObjectNode)coreInput).set("serverSupport",HybridCoreSupport.bundle(selected.get()));
                }
                enqueue(accepted,Role.CORE,coreInput,"QUEUED");
                var writerInput=JudgeJson.JSON.createObjectNode().put("language","ko");
                writerInput.set("semantics",HybridArtifacts.publicSemantics(payload));
                if(coreInput.has("serverSupport"))writerInput.set("serverRules",HybridPresentationRules.bundle(selected.orElseThrow()));
                jdbc.sql("SELECT requirements_json FROM hybrid_public_request WHERE generation_id=?").param(j.id)
                        .query((r,n)->r.getString(1)).optional().filter(java.util.Objects::nonNull).map(JudgeJson::parse)
                        .filter(r->r.path("presentation").path("policy").asText().equals("RETHEME_V1"))
                        .ifPresent(r->writerInput.set("presentation",r.path("presentation").deepCopy()));
                enqueue(accepted,Role.PRESENTATION,writerInput,"QUEUED");
            }
            if(b.role==Role.PRESENTATION) {
                JsonNode publicInput=HybridArtifacts.publicSnapshot(payload);String publicHash=JudgeJson.hash(JudgeJson.canonical(publicInput));
                jdbc.sql("UPDATE hybrid_generation SET public_sha256=?,updated_at=? WHERE id=?").param(publicHash).param(now()).param(j.id).update();
                enqueue(job(j.id,false),Role.READER,publicInput,"QUEUED");
            }
            join(job(j.id,false));return true;
        } catch(HybridArtifacts.Invalid invalid) {
            jdbc.sql("UPDATE hybrid_branch SET status='FAILED',error_code=? WHERE id=?").param(invalid.getMessage()).param(b.id).update();
            // Stop new claims, but preserve independent in-flight results for a targeted repair.
            // Queued siblings remain durable and are claimable only after explicit repair unholds the job.
            status(j.id,"HELD",invalid.getMessage());return false;
        }
    }
    /**
     * Records a quota-limited Codex outcome and queues the same frozen input as a new attempt.
     * Returns the new branch, or null for duplicate/late delivery. Caller owns the API reservation.
     */
    @Transactional
    Branch reroute(Completion c) {
        budgetLock();
        Branch location=branch(c.branchId);Job j=job(location.generation,true);Branch b=branch(c.branchId);
        if(c.token==null||!c.token.equals(b.token)||c.revision!=b.revision||c.role!=b.role
                ||!Objects.equals(c.inputHash,b.inputHash)||!Objects.equals(c.contractHash,b.contractHash)
                ||!Objects.equals(c.publicHash,b.publicHash)||!HybridModels.author(b.role))throw conflict();
        String completion=JudgeJson.canonical(HybridArtifacts.bounded(JudgeJson.JSON.valueToTree(c)));
        if(b.completion!=null){if(!b.completion.equals(completion))throw conflict();return null;}
        boolean late=expire(j)||TERMINAL.contains(j.status)||j.revision!=b.revision||!b.status.equals("RUNNING")
                ||!latest(j).get(b.role).id.equals(b.id);
        jdbc.sql("UPDATE hybrid_branch SET completion_json=?,late_result=?,finished_at=? WHERE id=?")
                .param(completion).param(late).param(now()).param(b.id).update();
        if(late)return null;
        jdbc.sql("UPDATE hybrid_branch SET status='FAILED',error_code='CODEX_QUOTA_EXHAUSTED' WHERE id=?").param(b.id).update();
        return enqueue(j,b.role,b.input,"QUEUED");
    }
    /** Moves a queued, never-claimed author branch to the API lane; no provider call has happened. */
    boolean queuedAuthor(UUID id,Role role) {
        Job j=job(id,false);Branch b=latest(j).get(role);
        return HybridModels.author(role)&&b!=null&&b.status.equals("QUEUED")&&!TERMINAL.contains(j.status);
    }
    void hold(UUID id,String error){status(id,"HELD",error);}
    private JsonNode artifact(Branch b) {
        if(b==null||!b.status.equals("SUCCEEDED"))throw conflict();
        var row=jdbc.sql("SELECT payload_json,payload_sha256 FROM hybrid_artifact WHERE branch_id=?").param(b.id)
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        if(!JudgeJson.hash(row[0]).equals(row[1])||!row[1].equals(b.outputHash))throw new HybridArtifacts.Invalid("ARTIFACT_INTEGRITY_FAILURE");
        return JudgeJson.parse(row[0]);
    }
    private void join(Job j) {
        var latest=latest(j);
        Branch early=latest.get(Role.VALIDATION)!=null&&latest.get(Role.VALIDATION).status.equals("EARLY")?latest.get(Role.VALIDATION):null;
        if(latest.containsKey(Role.VALIDATION)&&early==null&&!latest.get(Role.VALIDATION).status.equals("SUPERSEDED"))return;
        for(Role role:List.of(Role.CONTRACT,Role.CORE,Role.PRESENTATION,Role.READER)) {
            Branch b=latest.get(role);if(b==null||!b.status.equals("SUCCEEDED"))return;
            artifact(b);
            if(role!=Role.CONTRACT&&!Objects.equals(b.contractHash,j.contractHash))throw new HybridArtifacts.Invalid("CONTRACT_REVISION_MISMATCH");
        }
        if(!Objects.equals(latest.get(Role.READER).publicHash,j.publicHash))throw new HybridArtifacts.Invalid("PUBLIC_REVISION_MISMATCH");
        var manifest=JudgeJson.JSON.createObjectNode().put("pipelineVersion",HybridArtifacts.VERSION)
                .put("revision",j.revision).put("contractHash",j.contractHash).put("publicHash",j.publicHash);
        var hashes=manifest.putObject("artifacts");
        for(Role role:List.of(Role.CONTRACT,Role.CORE,Role.PRESENTATION,Role.READER))hashes.put(role.name(),latest.get(role).outputHash);
        if(early!=null) {
            // Checks already queued under this branch were bound to exactly these CONTRACT and CORE outputs.
            var partial=early.input.path("artifacts");
            if(!partial.path("CONTRACT").asText().equals(hashes.path("CONTRACT").asText())||!partial.path("CORE").asText().equals(hashes.path("CORE").asText())
                    ||!early.input.path("contractHash").asText().equals(j.contractHash))throw new HybridArtifacts.Invalid("EARLY_VALIDATION_FENCE");
            String raw=JudgeJson.canonical(HybridArtifacts.bounded(manifest));
            jdbc.sql("UPDATE hybrid_branch SET status='BLOCKED',input_json=?,input_sha256=?,public_sha256=? WHERE id=? AND status='EARLY'")
                    .param(raw).param(JudgeJson.hash(raw)).param(j.publicHash).param(early.id).update();
        }
        else enqueue(j,Role.VALIDATION,manifest,"BLOCKED");
        // A fixture join is never validation evidence. No READY or problem_version write exists here.
        status(j.id,"HELD","VALIDATION_ADAPTER_NOT_CONNECTED");
    }
    @Transactional
    Progress cancel(String username,UUID id) {
        budgetLock();
        Job j=owned(username,id,true);if(Set.of("FAILED","CANCELLED","DEADLINE_EXCEEDED","PUBLISHED").contains(j.status))return view(username,id);
        if(j.status.equals("HELD")||!expire(j)){status(id,"CANCELLED","CANCELLED_BY_OWNER");cancelPending(id,j.revision,EnumSet.allOf(Role.class));}
        return view(username,id);
    }
    @Transactional
    Progress repair(String username,UUID id,int revision,Role role,String expectedInputHash) {
        budgetLock();
        owned(username,id,false);
        if(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?").param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"실행 경로의 추가 수정은 별도 예산 정책 연결 후 지원해요.");
        Job j=owned(username,id,true);
        if(!now().isBefore(j.deadlineAt)||j.status.equals("CANCELLED")||j.status.equals("DEADLINE_EXCEEDED")
                ||j.revision!=revision||j.repairs!=0||!Set.of(Role.CORE,Role.PRESENTATION,Role.READER).contains(role))throw conflict();
        Branch old=latest(j).get(role);if(old==null||!old.inputHash.equals(expectedInputHash))throw conflict();
        // The global round is shared by roles. Existing inputs contain no other implementation.
        jdbc.sql("UPDATE hybrid_generation SET repair_rounds=1,status='BUILDING',error_code=NULL,updated_at=? WHERE id=?")
                .param(now()).param(id).update();
        var affected=EnumSet.of(role,Role.VALIDATION);if(role==Role.PRESENTATION)affected.add(Role.READER);
        cancelPending(id,revision,affected);
        // Completed downstream records remain immutable, but no longer qualify as the current branch.
        for(Role dependent:affected)if(dependent!=role) {
            Branch prior=latest(j).get(dependent);
            if(prior!=null)jdbc.sql("UPDATE hybrid_branch SET status='SUPERSEDED' WHERE id=?").param(prior.id).update();
        }
        if(role==Role.PRESENTATION)jdbc.sql("UPDATE hybrid_generation SET public_sha256=NULL WHERE id=?").param(id).update();
        enqueue(job(id,false),role,old.input,"QUEUED");return view(username,id);
    }
    @Transactional
    Progress reviseContract(String username,UUID id,int revision,String expectedContractHash) {
        budgetLock();
        owned(username,id,false);
        if(jdbc.sql("SELECT count(*) FROM hybrid_api_reservation WHERE generation_id=?").param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"실행 경로의 추가 수정은 별도 예산 정책 연결 후 지원해요.");
        Job j=owned(username,id,true);
        if(j.revision!=revision||!Objects.equals(j.contractHash,expectedContractHash)||j.repairs!=0
                ||!now().isBefore(j.deadlineAt)||Set.of("CANCELLED","DEADLINE_EXCEEDED").contains(j.status))throw conflict();
        cancelPending(id,revision,EnumSet.allOf(Role.class));
        jdbc.sql("UPDATE hybrid_generation SET revision=revision+1,repair_rounds=1,status='QUEUED',contract_sha256=NULL,public_sha256=NULL,error_code=NULL,updated_at=? WHERE id=?")
                .param(now()).param(id).update();
        Branch design=latest(j).get(Role.CONTRACT);enqueue(job(id,false),Role.CONTRACT,design.input,"QUEUED");return view(username,id);
    }
    @Transactional
    void expirePending() {
        budgetLock();
        var ids=jdbc.sql("SELECT id FROM hybrid_generation WHERE deadline_at<=? AND (status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (status='HELD' AND error_code IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))")
                .param(now()).query(UUID.class).list();for(UUID id:ids)expire(job(id,true));
    }
}
