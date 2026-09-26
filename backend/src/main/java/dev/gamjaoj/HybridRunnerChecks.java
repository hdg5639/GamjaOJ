package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded execution evidence only. Never an arbitrary-contract semantic proof or publisher. */
@Service
class HybridRunnerChecks {
    static final String POLICY="hybrid-execution-smoke-v1";
    private final JdbcClient jdbc;private final AiSettings settings;
    HybridRunnerChecks(JdbcClient jdbc,AiSettings settings){this.jdbc=jdbc;this.settings=settings;}
    private record State(UUID branch,UUID generation,int revision,String manifest,String hash,boolean verifyOnly) {
        State(UUID b,UUID g,int r,String m,String h){this(b,g,r,m,h,false);}
        /** Reader-independent checks run before the public snapshot and reader exist. */
        boolean early(){return JudgeJson.parse(manifest).path("early").asBoolean(false);}
    }
    static final String PIPELINE="FUNCTIONAL_V2";
    private boolean pipelineEnabled() {
        return Boolean.parseBoolean(settings.value("HYBRID_PIPELINE_V2_ENABLED","false"))&&Boolean.parseBoolean(settings.value("HYBRID_FUNCTIONAL_ENABLED","false"));
    }
    // Rebuild the completed evidence without enqueueing or executing another job.
    Map<String,JsonNode> checkedPackage(UUID branch) {
        var s=jdbc.sql("SELECT b.generation_id,b.revision,b.input_json,b.input_sha256 FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.id=? AND b.role='VALIDATION' AND b.status='CHECKED' AND b.revision=g.revision")
                .param(branch).query((r,n)->new State(branch,r.getObject(1,UUID.class),r.getInt(2),r.getString(3),r.getString(4),true)).optional()
                .orElseThrow(()->new IllegalArgumentException("MISSING_CHECKED_PACKAGE"));
        var profile=jdbc.sql("SELECT policy,profile_hash FROM hybrid_validation_profile WHERE branch_id=?").param(branch)
                .query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        if(!HybridProfiles.packaged(profile[0])||!HybridProfiles.byPolicy(profile[0]).hash().equals(profile[1]))throw new IllegalArgumentException("PROFILE_HASH_MISMATCH");
        var data=artifacts(s);HybridProfiles.byPolicy(profile[0]).requireSupported(data.get("CONTRACT"),data.get("READER"));
        advanceResults(s,data,profile[0]);return data;
    }
    private void lock(){jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private Map<String,JsonNode> artifacts(State s) {
        var manifest=JudgeJson.parse(s.manifest);
        if(!JudgeJson.hash(s.manifest).equals(s.hash))throw new IllegalArgumentException("MANIFEST_HASH_MISMATCH");
        var rows=jdbc.sql("SELECT b.role,b.output_sha256,b.contract_sha256,b.public_sha256,a.payload_json,a.payload_sha256 FROM hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id WHERE b.generation_id=? AND b.revision=? AND b.status='SUCCEEDED' AND b.role<>'VALIDATION' ORDER BY b.attempt")
                .param(s.generation).param(s.revision).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)}).list();
        var out=new HashMap<String,JsonNode>();
        for(var row:rows) {
            if(!JudgeJson.hash(row[4]).equals(row[5])||!row[5].equals(row[1]))throw new IllegalArgumentException("ARTIFACT_HASH_MISMATCH");
            // Older repaired successes remain immutable but only the exact joined hashes qualify.
            if(!row[1].equals(manifest.path("artifacts").path(row[0]).asText()))continue;
            if(!row[0].equals("CONTRACT")&&!Objects.equals(row[2],manifest.path("contractHash").asText()))throw new IllegalArgumentException("CONTRACT_HASH_MISMATCH");
            if(row[0].equals("READER")&&!Objects.equals(row[3],manifest.path("publicHash").asText()))throw new IllegalArgumentException("PUBLIC_HASH_MISMATCH");
            out.put(row[0],JudgeJson.parse(row[4]));
        }
        if(out.size()!=(s.early()?2:4)||(s.early()&&!out.keySet().equals(Set.of("CONTRACT","CORE"))))throw new IllegalArgumentException("MISSING_JOINED_ARTIFACT");return out;
    }
    private String version(State s){return "hybrid-check-"+s.branch;}
    private ObjectNode test(String id,String input,String output){return JudgeJson.JSON.createObjectNode().put("id",id).put("input",input).put("output",output);}
    private void queue(State s,String role,String source,List<JsonNode> cases,boolean run) {
        var plan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy",run?"RUN_ONLY":"TOKEN_EXACT");
        var tests=plan.putArray("tests");cases.forEach(tests::add);
        queuePlan(s,role,source,plan,run);
    }
    private void queuePlan(State s,String role,String source,JsonNode plan,boolean run) {
        if(s.verifyOnly)throw new IllegalArgumentException("INCOMPLETE_PUBLICATION_EVIDENCE");
        String payload=JudgeJson.canonical(plan),hash=JudgeJson.hash(payload),sourceHash=JudgeJson.hash(source);UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO submission(id,user_id,problem_version,source_code,source_sha256,idempotency_key,runtime_image,runner_policy,run_input,run_package,run_package_sha256,hybrid_branch_id) SELECT ?,g.owner_id,?,?,?,?,p.runtime_image,?,?,?,?,? FROM hybrid_generation g JOIN problem_version p ON p.id=? WHERE g.id=?")
                .param(id).param(version(s)).param(source).param(sourceHash).param(id).param(run?"java8-run-v1":"java8-judge-v1")
                .param("hybrid-check").param(payload).param(hash).param(s.branch).param(version(s)).param(s.generation).update();
        jdbc.sql("INSERT INTO judge_job(submission_id,priority,execution_mode) VALUES (?,1,?)").param(id).param(mode(s,role)).update();
        jdbc.sql("INSERT INTO hybrid_execution_check(branch_id,role,submission_id,source_sha256,package_sha256) VALUES (?,?,?,?,?)")
                .param(s.branch).param(role).param(id).param(sourceHash).param(hash).update();
    }
    private static final Set<String> FUNCTIONAL_ROLES=Set.of("domain-valid","domain-invalid","domain-reference","domain-oracle","mutant-unbounded","mutant-strict-fit","mutant-directed","mutant-unreachable","mutant-unit-weight","mutant-first-discovery");
    // V2 adds checks whose evidence carries no timing gate. Stress references, the generator and final
    // package replays, which carry resource margins or seed provenance, stay isolated.
    private static final Set<String> PIPELINE_ROLES=Set.of("stress-valid","batch-valid","batch-reference","batch-oracle");
    static String executionMode(String scheduling,String role) {
        // Only fixed, tiny inputs; stress, generated batches and package timing remain isolated.
        if(PIPELINE.equals(scheduling))return FUNCTIONAL_ROLES.contains(role)||PIPELINE_ROLES.contains(role)||role.startsWith("mutant-")?"FUNCTIONAL":"EXCLUSIVE";
        return "FUNCTIONAL_V1".equals(scheduling)&&FUNCTIONAL_ROLES.contains(role)?"FUNCTIONAL":"EXCLUSIVE";
    }
    private String mode(State s,String role) {
        String scheduling=jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).single();
        if(!Set.of("SERIAL_V1","FUNCTIONAL_V1",PIPELINE).contains(scheduling))throw new IllegalArgumentException("UNKNOWN_SCHEDULING_POLICY");
        return executionMode(scheduling,role);
    }
    private void run(State s,String role,String source,String input) {queue(s,role,source,List.of(test("custom-input",input,"")),true);}
    private void finish(State s,String error,JsonNode report) {
        if(s.verifyOnly) {
            String saved=jdbc.sql("SELECT completion_json FROM hybrid_branch WHERE id=?").param(s.branch).query(String.class).single();
            if(!"CONTENT_REVIEW_REQUIRED".equals(error)||report==null||!JudgeJson.canonical(report).equals(saved))throw new IllegalArgumentException("EXECUTION_REPORT_FENCE");
            return;
        }
        jdbc.sql("UPDATE hybrid_branch SET status=?,error_code=?,completion_json=?,finished_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('BLOCKED','RUNNING')")
                .param(Set.of("MISSING_PUBLICATION_EVIDENCE","CONTENT_REVIEW_REQUIRED").contains(error)?"CHECKED":"FAILED").param(error).param(report==null?null:JudgeJson.canonical(report)).param(s.branch).update();
        jdbc.sql("UPDATE hybrid_generation SET status='HELD',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")
                .param(error).param(s.generation).update();
    }
    /**
     * Starts reader-independent checks for fixed-profile requests as soon as CONTRACT and CORE exist.
     * The VALIDATION branch is created early with a partial manifest; the join completes it later.
     */
    private void startEarly() {
        var ready=jdbc.sql("SELECT g.id,g.revision,g.contract_sha256,r.profile_id FROM hybrid_generation g JOIN hybrid_public_request r ON r.generation_id=g.id WHERE g.status='BUILDING' AND g.deadline_at>CURRENT_TIMESTAMP AND g.contract_sha256 IS NOT NULL"
                        +" AND EXISTS (SELECT 1 FROM hybrid_branch c WHERE c.generation_id=g.id AND c.revision=g.revision AND c.role='CORE' AND c.status='SUCCEEDED')"
                        +" AND NOT EXISTS (SELECT 1 FROM hybrid_branch v WHERE v.generation_id=g.id AND v.revision=g.revision AND v.role='VALIDATION') ORDER BY g.created_at")
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getString(3),r.getString(4)}).list();
        for(var row:ready) {
            UUID generation=(UUID)row[0];int revision=(Integer)row[1];
            var profile=HybridProfiles.byId((String)row[3]);if(!HybridProfiles.packaged(profile.policy()))continue;
            jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE").param(generation).query(UUID.class).single();
            var outputs=jdbc.sql("SELECT role,output_sha256 FROM hybrid_branch WHERE generation_id=? AND revision=? AND role IN ('CONTRACT','CORE') AND status='SUCCEEDED'")
                    .param(generation).param(revision).query((r,n)->new String[]{r.getString(1),r.getString(2)}).list();
            if(outputs.size()!=2||jdbc.sql("SELECT count(*) FROM hybrid_branch WHERE generation_id=? AND revision=? AND role='VALIDATION'").param(generation).param(revision).query(Integer.class).single()>0)continue;
            var manifest=JudgeJson.JSON.createObjectNode().put("pipelineVersion",HybridArtifacts.VERSION).put("revision",revision)
                    .put("contractHash",(String)row[2]).put("early",true);
            var hashes=manifest.putObject("artifacts");for(var o:outputs)hashes.put(o[0],o[1]);
            String raw=JudgeJson.canonical(manifest);UUID branch=UUID.randomUUID();
            jdbc.sql("INSERT INTO hybrid_branch(id,generation_id,revision,role,attempt,status,input_json,input_sha256,contract_sha256,created_at,started_at) VALUES (?,?,?,'VALIDATION',0,'EARLY',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                    .param(branch).param(generation).param(revision).param(raw).param(JudgeJson.hash(raw)).param(row[2]).update();
            var s=new State(branch,generation,revision,raw,JudgeJson.hash(raw));
            try {
                var data=artifacts(s);
                var admitted=jdbc.sql("SELECT profile_hash,contract_sha256 FROM hybrid_public_request WHERE generation_id=?").param(generation)
                        .query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
                if(!profile.hash().equals(admitted[0])||!JudgeJson.hash(JudgeJson.canonical(data.get("CONTRACT"))).equals(admitted[1]))throw new IllegalArgumentException("ADMISSION_PROFILE_FENCE");
                jdbc.sql("INSERT INTO hybrid_validation_profile(branch_id,policy,profile_hash,scheduling) VALUES (?,?,?,?)").param(branch).param(profile.policy()).param(profile.hash()).param(PIPELINE).update();
                placeholder(s);
                for(String role:List.of("domain-valid","domain-invalid","domain-reference"))queue(s,role,source(data,profile,role),profile.tests(role),false);
            } catch(IllegalArgumentException e){finish(s,e.getMessage()==null?"INVALID_RUNNER_EVIDENCE":e.getMessage(),null);}
        }
    }
    private void placeholder(State s) {
        String empty=JudgeJson.canonical(JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","TOKEN_EXACT"));
        jdbc.sql("INSERT INTO problem_version(id,package_json,package_sha256,runtime_image,runner_policy,ready,owner_id) SELECT ?,?,?,p.runtime_image,p.runner_policy,false,g.owner_id FROM problem_version p JOIN hybrid_generation g ON g.id=? WHERE p.id='total-v1'")
                .param(version(s)).param(empty).param(JudgeJson.hash(empty)).param(s.generation).update();
    }
    private static String source(Map<String,JsonNode> data,HybridProfiles.Definition profile,String role) {
        return switch(role) {
            case "domain-valid","domain-invalid","stress-valid","batch-valid" -> data.get("CORE").path("inputValidator").asText();
            case "domain-reference","stress-reference-0","stress-reference-1","batch-reference","package-final-0","package-final-1" -> data.get("CORE").path("reference").asText();
            case "domain-oracle","batch-oracle" -> data.get("READER").path("oracleSource").asText();
            case "package-generator" -> data.get("CORE").path("generator").asText();
            default -> profile.mutant(role);
        };
    }
    @Transactional
    void advance() {
        lock();
        if(pipelineEnabled())startEarly();
        // Active work remains pinned to this policy even if new starts are disabled.
        var rows=jdbc.sql("SELECT b.id,b.generation_id,b.revision,b.input_json,b.input_sha256,b.status FROM hybrid_branch b JOIN hybrid_generation g ON g.id=b.generation_id WHERE b.role='VALIDATION' AND b.revision=g.revision AND ((b.status='BLOCKED' AND g.status='HELD' AND g.error_code='VALIDATION_ADAPTER_NOT_CONNECTED') OR (b.status='RUNNING' AND g.status='VALIDATING') OR (b.status='EARLY' AND g.status='BUILDING')) AND g.deadline_at>CURRENT_TIMESTAMP ORDER BY b.created_at")
                .query((r,n)->new Object[]{new State(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getString(4),r.getString(5)),r.getString(6)}).list();
        for(var row:rows) {
            var s=(State)row[0];boolean start=row[1].equals("BLOCKED");
            if(row[1].equals("EARLY")) {
                jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE").param(s.generation).query(UUID.class).single();
                try {
                    String early=jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).single();
                    advanceResults(s,artifacts(s),early);
                } catch(IllegalArgumentException e){finish(s,e.getMessage()==null?"INVALID_RUNNER_EVIDENCE":e.getMessage(),null);}
                continue;
            }
            // A branch started early already owns its profile, placeholder and first checks.
            boolean resumed=start&&jdbc.sql("SELECT count(*) FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(Integer.class).single()>0;
            String policy=start&&!resumed?jdbc.sql("SELECT profile_id FROM hybrid_public_request WHERE generation_id=?").param(s.generation).query(String.class).optional().map(id->HybridProfiles.byId(id).policy()).orElseGet(()->settings.value("HYBRID_VALIDATION_PROFILE","")):jdbc.sql("SELECT policy FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).optional().orElse("");
            if(start&&!POLICY.equals(policy)&&!HybridProfiles.supports(policy))continue;
            jdbc.sql("SELECT id FROM hybrid_generation WHERE id=? FOR UPDATE").param(s.generation).query(UUID.class).single();
            try {
                var data=artifacts(s);var core=data.get("CORE");
                var admitted=jdbc.sql("SELECT profile_hash,contract_sha256 FROM hybrid_public_request WHERE generation_id=?").param(s.generation)
                        .query((r,n)->new String[]{r.getString(1),r.getString(2)}).optional();
                if(admitted.isPresent()&&(!HybridProfiles.byPolicy(policy).hash().equals(admitted.get()[0])||!JudgeJson.hash(JudgeJson.canonical(data.get("CONTRACT"))).equals(admitted.get()[1])))
                    throw new IllegalArgumentException("ADMISSION_PROFILE_FENCE");
                boolean finite=HybridProfiles.supports(policy);
                if(!POLICY.equals(policy)&&!HybridProfiles.supports(policy))throw new IllegalArgumentException("UNKNOWN_VALIDATION_POLICY");
                if(finite) {
                    HybridProfiles.byPolicy(policy).requireSupported(data.get("CONTRACT"),data.get("READER"));
                    if(!start&&!HybridProfiles.byPolicy(policy).hash().equals(jdbc.sql("SELECT profile_hash FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).single()))throw new IllegalArgumentException("PROFILE_HASH_MISMATCH");
                }
                if(start&&HybridProfiles.packaged(policy)) {
                    // These frozen reader inputs are already available; reject malformed cases
                    // before spending Runner work, using the same parser as final assembly.
                    // Registered packages validate reader inputs with the qualified validator in the Runner.
                    if(HybridProfiles.byPolicy(policy).pkg()==null)for(var item:data.get("READER").path("adversarialInputs")) {
                        try {HybridProfiles.byPolicy(policy).answer(item.path("input").asText());}
                        catch(IllegalArgumentException invalid) {throw new IllegalArgumentException("READER_INPUT_BOUND");}
                    }
                }
                if(resumed) {
                    if(!HybridProfiles.byPolicy(policy).hash().equals(jdbc.sql("SELECT profile_hash FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).single()))throw new IllegalArgumentException("PROFILE_HASH_MISMATCH");
                    jdbc.sql("UPDATE hybrid_branch SET status='RUNNING' WHERE id=?").param(s.branch).update();
                    jdbc.sql("UPDATE hybrid_generation SET status='VALIDATING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(s.generation).update();
                    advanceResults(s,data,policy);
                } else if(start) {
                    String scheduling=HybridProfiles.packaged(policy)&&Boolean.parseBoolean(settings.value("HYBRID_FUNCTIONAL_ENABLED","false"))?(pipelineEnabled()?PIPELINE:"FUNCTIONAL_V1"):"SERIAL_V1";
                    jdbc.sql("INSERT INTO hybrid_validation_profile(branch_id,policy,profile_hash,scheduling) VALUES (?,?,?,?)").param(s.branch).param(policy).param(finite?HybridProfiles.byPolicy(policy).hash():null).param(scheduling).update();
                    placeholder(s);
                    if(finite) {
                        queue(s,"domain-valid",core.path("inputValidator").asText(),HybridProfiles.byPolicy(policy).tests("domain-valid"),false);
                        queue(s,"domain-invalid",core.path("inputValidator").asText(),HybridProfiles.byPolicy(policy).tests("domain-invalid"),false);
                    }else run(s,"generator",core.path("generator").asText(),new java.security.SecureRandom().nextLong()+"\n");
                    jdbc.sql("UPDATE hybrid_branch SET status='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=?").param(s.branch).update();
                    jdbc.sql("UPDATE hybrid_generation SET status='VALIDATING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?").param(s.generation).update();
                } else advanceResults(s,data,policy);
            } catch(IllegalArgumentException e){finish(s,e.getMessage()==null?"INVALID_RUNNER_EVIDENCE":e.getMessage(),null);}
        }
    }
    private record Evidence(String role,String verdict,String report,String hash,JsonNode plan) {}
    private String output(Evidence e) {
        if(e==null)throw new IllegalArgumentException("MISSING_RUNNER_ROLE");
        var t=JudgeJson.parse(e.report).path("tests").path(0);
        if(t.path("stdout_truncated").asBoolean()||!t.path("stdout").isTextual()||t.path("stdout").asText().isBlank())throw new IllegalArgumentException("INVALID_RUNNER_OUTPUT");
        return t.path("stdout").asText();
    }
    private void advanceResults(State s,Map<String,JsonNode> data,String policy) {
        boolean finite=HybridProfiles.supports(policy),extended=finite&&!HybridFiniteProfile.POLICY.equals(policy);
        var rows=jdbc.sql("SELECT e.role,e.source_sha256,e.package_sha256,s.source_code,s.source_sha256,s.run_package,s.run_package_sha256,j.status,j.verdict,j.result_json,j.result_sha256,j.execution_mode FROM hybrid_execution_check e JOIN submission s ON s.id=e.submission_id JOIN judge_job j ON j.submission_id=s.id WHERE e.branch_id=? ORDER BY e.role")
                .param(s.branch).query((r,n)->{var a=new String[12];for(int i=0;i<12;i++)a[i]=r.getString(i+1);return a;}).list();
        if(s.verifyOnly&&(rows.size()!=(finite?HybridProfiles.byPolicy(policy).roles().size():15)||rows.stream().anyMatch(r->!"FINISHED".equals(r[7]))))throw new IllegalArgumentException("INCOMPLETE_PUBLICATION_EVIDENCE");
        if(rows.isEmpty())throw new IllegalArgumentException("MISSING_RUNNER_JOBS");
        var evidence=new HashMap<String,Evidence>();
        for(var row:rows) {
            String source=switch(row[0]) {
                case "generator", "package-generator" -> data.get("CORE").path("generator").asText();
                case "validator", "domain-valid", "domain-invalid", "stress-valid", "batch-valid" -> data.get("CORE").path("inputValidator").asText();
                case "domain-reference", "stress-reference-0", "stress-reference-1", "batch-reference", "package-final-0", "package-final-1" -> data.get("CORE").path("reference").asText();
                case "mutant-unbounded", "mutant-strict-fit", "mutant-directed", "mutant-unreachable", "mutant-unit-weight", "mutant-first-discovery" -> HybridProfiles.byPolicy(policy).mutant(row[0]);
                case "domain-oracle", "batch-oracle" -> data.get("READER").path("oracleSource").asText();
                default -> row[0].startsWith("mutant-")&&HybridProfiles.supports(policy)?HybridProfiles.byPolicy(policy).mutant(row[0]):row[0].matches("reference-[0-7]")?data.get("CORE").path("reference").asText():row[0].matches("oracle-[0-7]")?data.get("READER").path("oracleSource").asText():"";
            };
            if(!row[1].equals(JudgeJson.hash(source)))throw new IllegalArgumentException("RUNNER_SOURCE_FENCE");
            if(!row[1].equals(row[4])||!row[1].equals(JudgeJson.hash(row[3]))||!row[2].equals(row[6])||!row[2].equals(JudgeJson.hash(row[5])))throw new IllegalArgumentException("RUNNER_INPUT_FENCE");
            if(!mode(s,row[0]).equals(row[11]))throw new IllegalArgumentException("RUNNER_SCHEDULING_FENCE");
            if(!row[7].equals("FINISHED"))return;
            if(row[9]==null||!JudgeJson.hash(row[9]).equals(row[10]))throw new IllegalArgumentException("RUNNER_REPORT_FENCE");
            if(!mode(s,row[0]).equals(JudgeJson.parse(row[9]).path("execution_mode").asText()))throw new IllegalArgumentException("RUNNER_SCHEDULING_FENCE");
            if(finite&&!HybridProfiles.byPolicy(policy).roles().contains(row[0]))throw new IllegalArgumentException("UNKNOWN_FINITE_ROLE");
            String expected=row[0].equals("package-generator")?"OK":extended&&row[0].startsWith("mutant-")?"WA":finite||row[0].equals("validator")?"AC":"OK";
            if(!expected.equals(row[8]))throw new IllegalArgumentException(extended&&row[0].startsWith("mutant-")
                    ?row[8].equals("AC")?"MUTANT_SURVIVED":"MUTANT_"+row[8]:"RUNNER_"+row[8]);
            evidence.put(row[0],new Evidence(row[0],row[8],row[9],row[10],JudgeJson.parse(row[5])));
        }
        if(finite&&PIPELINE.equals(jdbc.sql("SELECT scheduling FROM hybrid_validation_profile WHERE branch_id=?").param(s.branch).query(String.class).single())) {
            advancePipeline(s,data,evidence,HybridProfiles.byPolicy(policy));return;
        }
        if(finite){advanceFinite(s,data,evidence,policy);return;}
        JsonNode generated;
        try {generated=JudgeJson.JSON.readTree(output(evidence.get("generator")));}catch(Exception e){throw new IllegalArgumentException("INVALID_GENERATOR_ENVELOPE");}
        if(!generated.isArray()||generated.size()!=4)throw new IllegalArgumentException("INVALID_GENERATOR_ENVELOPE");
        var inputs=new ArrayList<String>();
        for(var input:generated) {
            if(!input.isTextual()||input.asText().isBlank()||input.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("INVALID_GENERATOR_INPUT");
            inputs.add(input.asText());
        }
        // These are execution probes, not verified oracle-domain or semantics evidence.
        for(var item:data.get("READER").path("adversarialInputs")) {
            if(inputs.size()>=8)break;
            String input=item.path("input").asText();
            if(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("ADVERSARIAL_INPUT_TOO_LARGE");
            if(!inputs.contains(input))inputs.add(input);
        }
        var core=data.get("CORE");
        if(rows.size()==1) {
            var tests=new ArrayList<JsonNode>();for(int i=0;i<inputs.size();i++)tests.add(test("probe-"+i,inputs.get(i),"VALID\n"));
            queue(s,"validator",core.path("inputValidator").asText(),tests,false);return;
        }
        if(rows.size()==2) {
            var saved=evidence.get("validator").plan.path("tests");
            for(int i=0;i<inputs.size();i++)if(!saved.path(i).path("input").asText().equals(inputs.get(i)))throw new IllegalArgumentException("PROBE_INPUT_FENCE");
            for(int i=0;i<inputs.size();i++) {
                run(s,"reference-"+i,core.path("reference").asText(),inputs.get(i));
                run(s,"oracle-"+i,data.get("READER").path("oracleSource").asText(),inputs.get(i));
            }
            return;
        }
        if(rows.size()!=2+2*inputs.size())throw new IllegalArgumentException("INCOMPLETE_RUNNER_EVIDENCE");
        var report=JudgeJson.JSON.createObjectNode().put("policy",POLICY).put("publishable",false).put("manifestHash",s.hash).put("oracleDomainVerified",false);
        var samples=report.putArray("executionProbes");
        for(int i=0;i<inputs.size();i++) {
            for(String role:List.of("reference-"+i,"oracle-"+i)) {
                var e=evidence.get(role);
                if(e==null||!e.plan.path("tests").path(0).path("input").asText().equals(inputs.get(i)))throw new IllegalArgumentException("PROBE_INPUT_FENCE");
            }
            String a=output(evidence.get("reference-"+i)),b=output(evidence.get("oracle-"+i));
            if(!Arrays.equals(a.strip().split("(?U)\\s+"),b.strip().split("(?U)\\s+")))throw new IllegalArgumentException("REFERENCE_ORACLE_DISAGREEMENT");
            samples.addObject().put("input",inputs.get(i)).put("observedOutput",a);
        }
        var checks=report.putArray("results");evidence.values().stream().sorted(Comparator.comparing(Evidence::role)).forEach(e->checks.addObject().put("role",e.role).put("reportHash",e.hash));
        report.putArray("remaining").add("supported semantic profile and oracle domain enforcement").add("public prose equivalence")
                .add("invalid inputs").add("bounded exhaustive coverage").add("verified mutant witnesses").add("stress and resource limits")
                .add("teaching correctness").add("final package replay");
        finish(s,"MISSING_PUBLICATION_EVIDENCE",report);
    }
    private void advanceFinite(State s,Map<String,JsonNode> data,Map<String,Evidence> evidence,String policy) {
        var profile=HybridProfiles.byPolicy(policy);
        boolean extended=!HybridFiniteProfile.POLICY.equals(policy),packagePolicy=HybridProfiles.packaged(policy);
        for(var e:evidence.values()) {
            if(!profile.roles(extended).contains(e.role))continue;
            var expected=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","TOKEN_EXACT");
            var tests=expected.putArray("tests");profile.tests(e.role).forEach(tests::add);
            if(!expected.equals(e.plan))throw new IllegalArgumentException("FINITE_INPUT_FENCE");
        }
        if(!evidence.keySet().containsAll(Set.of("domain-valid","domain-invalid")))throw new IllegalArgumentException("MISSING_FINITE_VALIDATORS");
        if(evidence.size()==2) {
            queue(s,"domain-reference",data.get("CORE").path("reference").asText(),profile.tests("domain-reference"),false);
            queue(s,"domain-oracle",data.get("READER").path("oracleSource").asText(),profile.tests("domain-oracle"),false);return;
        }
        var base=Set.of("domain-valid","domain-invalid","domain-reference","domain-oracle");
        if(!evidence.keySet().containsAll(base))throw new IllegalArgumentException("INCOMPLETE_FINITE_EVIDENCE");
        if(extended&&evidence.size()==4) {
            queue(s,"stress-valid",data.get("CORE").path("inputValidator").asText(),profile.tests("stress-valid"),false);
            for(String role:profile.mutants())queue(s,role,profile.mutant(role),profile.tests(role),false);
            return;
        }
        if(extended&&evidence.size()==7&&evidence.keySet().contains("stress-valid")&&evidence.keySet().containsAll(profile.mutants())) {
            for(String role:List.of("stress-reference-0","stress-reference-1"))queue(s,role,data.get("CORE").path("reference").asText(),profile.tests(role),false);
            return;
        }
        if(!evidence.keySet().containsAll(profile.roles(extended))||(!packagePolicy&&!evidence.keySet().equals(profile.roles(extended))))throw new IllegalArgumentException("INCOMPLETE_FINITE_EVIDENCE");
        if(extended)for(String role:List.of("stress-reference-0","stress-reference-1")) {
            var tests=JudgeJson.parse(evidence.get(role).report).path("tests");
            if(tests.size()!=profile.stress().size())throw new IllegalArgumentException("INCOMPLETE_STRESS_EVIDENCE");
            for(var test:tests)if(!test.path("wall_ms").isIntegralNumber()||!test.path("wall_ms").canConvertToLong()
                    ||test.path("wall_ms").asLong()<0||test.path("wall_ms").asLong()>4000)throw new IllegalArgumentException("STRESS_RESOURCE_MARGIN");
        }
        JsonNode packageEvidence=packagePolicy?advancePackage(s,data,evidence,profile):null;
        if(packagePolicy&&packageEvidence==null)return;
        finish(s,packagePolicy?"CONTENT_REVIEW_REQUIRED":"MISSING_PUBLICATION_EVIDENCE",report(s,evidence,profile,policy,extended,packagePolicy,packageEvidence));
    }
    private ObjectNode report(State s,Map<String,Evidence> evidence,HybridProfiles.Definition profile,String policy,boolean extended,boolean packagePolicy,JsonNode packageEvidence) {
        var report=JudgeJson.JSON.createObjectNode().put("policy",policy).put("publishable",false)
                .put("manifestHash",s.hash).put("oracleDomainVerified",true).put("oracleDomainVerificationScope","only the "+profile.coverage().path("cases").asInt()+" server-enumerated inputs")
                .put("invalidInputsChecked",profile.invalid().size());
        report.set("finiteCoverage",profile.coverage());
        report.put("profileHash",HybridProfiles.byPolicy(policy).hash());
        if(extended) {
            var mutants=report.putArray("mutantWitnesses");
            for(String role:profile.mutants()) {
                var witness=mutants.addObject().put("role",role).put("sourceHash",JudgeJson.hash(profile.mutant(role)))
                        .put("verdict",evidence.get(role).verdict).put("reportHash",evidence.get(role).hash);
                witness.set("tests",evidence.get(role).plan.path("tests"));
            }
            report.set("maximumInputChecks",profile.maximumChecks());
        }
        var results=report.putArray("results");evidence.values().stream().sorted(Comparator.comparing(Evidence::role))
                .forEach(e->results.addObject().put("role",e.role).put("reportHash",e.hash));
        var remaining=report.putArray("remaining").add("public prose equivalence");
        if(!packagePolicy)remaining.add("generator and random/boundary coverage");
        if(!extended)remaining.add("verified mutant witnesses").add("stress and resource limits");
        remaining.add("teaching correctness");
        if(packagePolicy){report.put("executionChecksComplete",true);report.set("packageEvidence",packageEvidence);}
        else remaining.add("final package replay");
        return report;
    }

    private JsonNode advancePackage(State s,Map<String,JsonNode> data,Map<String,Evidence> evidence,HybridProfiles.Definition profile) {
        var core=data.get("CORE");
        if(evidence.size()==9) {
            var random=new java.security.SecureRandom();long generatorSeed=random.nextLong(),randomSeed=random.nextLong();
            jdbc.sql("INSERT INTO hybrid_package_evidence(branch_id,generator_seed,random_seed) VALUES (?,?,?)")
                    .param(s.branch).param(generatorSeed).param(randomSeed).update();
            run(s,"package-generator",core.path("generator").asText(),generatorSeed+"\n");return null;
        }
        var saved=jdbc.sql("SELECT generator_seed,random_seed,candidates_json,candidates_sha256,package_json,package_sha256 FROM hybrid_package_evidence WHERE branch_id=?")
                .param(s.branch).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)}).single();
        var generator=evidence.get("package-generator");
        var generatorPlan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","RUN_ONLY");
        generatorPlan.putArray("tests").add(test("custom-input",saved[0]+"\n",""));
        if(generator==null||!generatorPlan.equals(generator.plan))throw new IllegalArgumentException("GENERATOR_SEED_FENCE");
        var candidates=HybridPackagePlan.candidates(output(generator),data.get("READER"),Long.parseLong(saved[1]),profile);
        String candidateJson=JudgeJson.canonical(JudgeJson.JSON.valueToTree(candidates));
        if(evidence.size()==10) {
            jdbc.sql("UPDATE hybrid_package_evidence SET candidates_json=?,candidates_sha256=? WHERE branch_id=? AND candidates_json IS NULL")
                    .param(candidateJson).param(JudgeJson.hash(candidateJson)).param(s.branch).update();
            queue(s,"batch-valid",core.path("inputValidator").asText(),HybridPackagePlan.tests(candidates,"batch-valid",profile),false);return null;
        }
        if(!candidateJson.equals(saved[2])||!JudgeJson.hash(candidateJson).equals(saved[3]))throw new IllegalArgumentException("CANDIDATE_FENCE");
        for(String role:List.of("batch-valid","batch-reference","batch-oracle"))if(evidence.containsKey(role)) {
            var plan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","TOKEN_EXACT");
            var tests=plan.putArray("tests");HybridPackagePlan.tests(candidates,role,profile).forEach(tests::add);
            if(!plan.equals(evidence.get(role).plan))throw new IllegalArgumentException("PACKAGE_INPUT_FENCE");
        }
        if(evidence.size()==11&&evidence.containsKey("batch-valid")) {
            queue(s,"batch-reference",core.path("reference").asText(),HybridPackagePlan.tests(candidates,"batch-reference",profile),false);
            queue(s,"batch-oracle",data.get("READER").path("oracleSource").asText(),HybridPackagePlan.tests(candidates,"batch-oracle",profile),false);return null;
        }
        if(!evidence.keySet().containsAll(Set.of("batch-valid","batch-reference","batch-oracle")))throw new IllegalArgumentException("INCOMPLETE_PACKAGE_CHECKS");
        var pack=HybridPackagePlan.pack(version(s),candidates,data.get("PRESENTATION"),profile,core.path("reference").asText());String payload=JudgeJson.canonical(pack),hash=JudgeJson.hash(payload);
        var teaching=JudgeJson.JSON.createObjectNode().put("editorial",data.get("PRESENTATION").path("editorial").asText());teaching.set("hints",data.get("PRESENTATION").path("hints"));
        String teachingJson=JudgeJson.canonical(teaching);
        if(evidence.size()==13) {
            jdbc.sql("UPDATE hybrid_package_evidence SET package_json=?,package_sha256=? WHERE branch_id=? AND package_json IS NULL")
                    .param(payload).param(hash).param(s.branch).update();
            if(jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=?,teaching_json=? WHERE id=? AND ready=false")
                    .param(payload).param(hash).param(teachingJson).param(version(s)).update()!=1)throw new IllegalStateException("Missing unpublished hybrid package");
            for(String role:List.of("package-final-0","package-final-1"))queuePlan(s,role,core.path("reference").asText(),pack,false);return null;
        }
        if(!evidence.keySet().equals(profile.roles()))throw new IllegalArgumentException("INCOMPLETE_PACKAGE_CHECKS");
        var stored=jdbc.sql("SELECT package_json,package_sha256,teaching_json,ready FROM problem_version WHERE id=?").param(version(s))
                .query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),Boolean.toString(r.getBoolean(4))}).single();
        if(!payload.equals(saved[4])||!hash.equals(saved[5])||!payload.equals(stored[0])||!hash.equals(stored[1])||!teachingJson.equals(stored[2])||!stored[3].equalsIgnoreCase("false"))
            throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
        for(String role:List.of("package-final-0","package-final-1")) {
            var e=evidence.get(role);if(!pack.equals(e.plan))throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
            var tests=JudgeJson.parse(e.report).path("tests");long total=0;
            if(tests.size()!=candidates.stream().filter(c->!HybridPackagePlan.checkOnly(c)).count()+HybridPackagePlan.generatedCount(profile))throw new IllegalArgumentException("FINAL_PACKAGE_EVIDENCE");
            for(var t:tests) {
                var wall=t.path("wall_ms");if(!wall.isIntegralNumber()||!wall.canConvertToLong()||wall.asLong()<0||wall.asLong()>4000)throw new IllegalArgumentException("FINAL_PACKAGE_RESOURCE_MARGIN");
                total+=wall.asLong();
            }
            if(total>40000)throw new IllegalArgumentException("FINAL_PACKAGE_TIME_BUDGET");
        }
        return JudgeJson.JSON.createObjectNode().put("packageHash",hash).put("candidatesHash",saved[3]).put("testCount",candidates.stream().filter(c->!HybridPackagePlan.checkOnly(c)).count())
                .put("randomInputCount",4).put("generatorInputCount",4).put("boundedOracleInputCount",HybridPackagePlan.tests(candidates,"batch-oracle",profile).size())
                .put("readerInputsIncluded",data.get("READER").path("adversarialInputs").size()).put("packageExecutions",2)
                .put("generatorSeed",saved[0]).put("randomSeed",saved[1]).put("samplesMechanicallyDerived",true);
    }

    /**
     * FUNCTIONAL_V2: the same 15 checks and fences as the packaged profile, queued by readiness instead of
     * fixed counts, so reader-independent work runs while the statement and reader are still being written.
     * Each stage starts only after every queued check finished with its expected verdict.
     */
    /** Fixed-role plan; a package's stress roles also validate and time its generated large inputs. */
    private ObjectNode fixedPlan(State s,HybridProfiles.Definition profile,String role,String reference) {
        var plan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","TOKEN_EXACT");
        var tests=plan.putArray("tests");profile.tests(role).forEach(tests::add);
        if(profile.pkg()!=null&&profile.pkg().hasLarge()) {
            if(role.equals("stress-valid"))plan.set("generated",profile.pkg().generated(null,"VALID"));
            if(role.startsWith("stress-reference-"))plan.set("generated",profile.pkg().generated(reference,"REFERENCE"));
        }
        return plan;
    }
    private void advancePipeline(State s,Map<String,JsonNode> data,Map<String,Evidence> evidence,HybridProfiles.Definition profile) {
        String policy=profile.policy();boolean reader=data.containsKey("READER");String referenceSource=data.get("CORE").path("reference").asText();
        for(var e:evidence.values()) {
            if(!profile.roles(true).contains(e.role))continue;
            if(!fixedPlan(s,profile,e.role,referenceSource).equals(e.plan))throw new IllegalArgumentException("FINITE_INPUT_FENCE");
        }
        var done=evidence.keySet();
        java.util.function.Consumer<String> fixed=role->{if(!done.contains(role))queuePlan(s,role,source(data,profile,role),fixedPlan(s,profile,role,referenceSource),false);};
        var first=List.of("domain-valid","domain-invalid","domain-reference");
        if(!done.containsAll(first)){first.forEach(fixed);return;}
        var second=new ArrayList<String>(List.of("stress-valid"));second.addAll(profile.mutants());
        if(!done.containsAll(second)){second.forEach(fixed);return;}
        var stress=List.of("stress-reference-0","stress-reference-1");
        if(!done.containsAll(stress)){stress.forEach(fixed);return;}
        for(String role:stress) {
            var tests=JudgeJson.parse(evidence.get(role).report).path("tests");
            if(tests.size()!=profile.stress().size()+HybridPackagePlan.generatedCount(profile))throw new IllegalArgumentException("INCOMPLETE_STRESS_EVIDENCE");
            for(var test:tests)if(!test.path("wall_ms").isIntegralNumber()||!test.path("wall_ms").canConvertToLong()
                    ||test.path("wall_ms").asLong()<0||test.path("wall_ms").asLong()>4000)throw new IllegalArgumentException("STRESS_RESOURCE_MARGIN");
        }
        var core=data.get("CORE");
        if(!done.contains("package-generator")) {
            var random=new java.security.SecureRandom();
            jdbc.sql("INSERT INTO hybrid_package_evidence(branch_id,generator_seed,random_seed) VALUES (?,?,?)")
                    .param(s.branch).param(random.nextLong()).param(random.nextLong()).update();
            String seed=jdbc.sql("SELECT generator_seed FROM hybrid_package_evidence WHERE branch_id=?").param(s.branch).query(String.class).single();
            run(s,"package-generator",core.path("generator").asText(),seed+"\n");return;
        }
        if(!reader)return; // Remaining checks need the independent reader's oracle and inputs.
        var saved=jdbc.sql("SELECT generator_seed,random_seed,candidates_json,candidates_sha256,package_json,package_sha256 FROM hybrid_package_evidence WHERE branch_id=?")
                .param(s.branch).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)}).single();
        var generatorPlan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","RUN_ONLY");
        generatorPlan.putArray("tests").add(test("custom-input",saved[0]+"\n",""));
        if(!generatorPlan.equals(evidence.get("package-generator").plan))throw new IllegalArgumentException("GENERATOR_SEED_FENCE");
        var candidates=HybridPackagePlan.candidates(output(evidence.get("package-generator")),data.get("READER"),Long.parseLong(saved[1]),profile);
        String candidateJson=JudgeJson.canonical(JudgeJson.JSON.valueToTree(candidates));
        if(saved[2]==null) {
            jdbc.sql("UPDATE hybrid_package_evidence SET candidates_json=?,candidates_sha256=? WHERE branch_id=? AND candidates_json IS NULL")
                    .param(candidateJson).param(JudgeJson.hash(candidateJson)).param(s.branch).update();
        } else if(!candidateJson.equals(saved[2])||!JudgeJson.hash(candidateJson).equals(saved[3]))throw new IllegalArgumentException("CANDIDATE_FENCE");
        for(String role:List.of("batch-valid","batch-reference","batch-oracle"))if(done.contains(role)) {
            var plan=JudgeJson.JSON.createObjectNode().put("version",version(s)).put("output_policy","TOKEN_EXACT");
            var tests=plan.putArray("tests");HybridPackagePlan.tests(candidates,role,profile).forEach(tests::add);
            if(!plan.equals(evidence.get(role).plan))throw new IllegalArgumentException("PACKAGE_INPUT_FENCE");
        }
        if(!done.containsAll(List.of("domain-oracle","batch-valid"))) {
            fixed.accept("domain-oracle");
            if(!done.contains("batch-valid"))queue(s,"batch-valid",core.path("inputValidator").asText(),HybridPackagePlan.tests(candidates,"batch-valid",profile),false);
            return;
        }
        if(!done.containsAll(List.of("batch-reference","batch-oracle"))) {
            for(String role:List.of("batch-reference","batch-oracle"))if(!done.contains(role))
                queue(s,role,source(data,profile,role),HybridPackagePlan.tests(candidates,role,profile),false);
            return;
        }
        var pack=HybridPackagePlan.pack(version(s),candidates,data.get("PRESENTATION"),profile,core.path("reference").asText());String payload=JudgeJson.canonical(pack),hash=JudgeJson.hash(payload);
        var teaching=JudgeJson.JSON.createObjectNode().put("editorial",data.get("PRESENTATION").path("editorial").asText());teaching.set("hints",data.get("PRESENTATION").path("hints"));
        String teachingJson=JudgeJson.canonical(teaching);
        if(!done.containsAll(List.of("package-final-0","package-final-1"))) {
            if(done.contains("package-final-0")||done.contains("package-final-1"))throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
            jdbc.sql("UPDATE hybrid_package_evidence SET package_json=?,package_sha256=? WHERE branch_id=? AND package_json IS NULL")
                    .param(payload).param(hash).param(s.branch).update();
            if(jdbc.sql("UPDATE problem_version SET package_json=?,package_sha256=?,teaching_json=? WHERE id=? AND ready=false")
                    .param(payload).param(hash).param(teachingJson).param(version(s)).update()!=1)throw new IllegalStateException("Missing unpublished hybrid package");
            for(String role:List.of("package-final-0","package-final-1"))queuePlan(s,role,core.path("reference").asText(),pack,false);return;
        }
        if(!done.equals(profile.roles()))throw new IllegalArgumentException("INCOMPLETE_PACKAGE_CHECKS");
        var stored=jdbc.sql("SELECT package_json,package_sha256,teaching_json,ready FROM problem_version WHERE id=?").param(version(s))
                .query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),Boolean.toString(r.getBoolean(4))}).single();
        if(!payload.equals(saved[4])||!hash.equals(saved[5])||!payload.equals(stored[0])||!hash.equals(stored[1])||!teachingJson.equals(stored[2])||!stored[3].equalsIgnoreCase("false"))
            throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
        for(String role:List.of("package-final-0","package-final-1")) {
            var e=evidence.get(role);if(!pack.equals(e.plan))throw new IllegalArgumentException("FINAL_PACKAGE_FENCE");
            var tests=JudgeJson.parse(e.report).path("tests");long total=0;
            if(tests.size()!=candidates.stream().filter(c->!HybridPackagePlan.checkOnly(c)).count()+HybridPackagePlan.generatedCount(profile))throw new IllegalArgumentException("FINAL_PACKAGE_EVIDENCE");
            for(var t:tests) {
                var wall=t.path("wall_ms");if(!wall.isIntegralNumber()||!wall.canConvertToLong()||wall.asLong()<0||wall.asLong()>4000)throw new IllegalArgumentException("FINAL_PACKAGE_RESOURCE_MARGIN");
                total+=wall.asLong();
            }
            if(total>40000)throw new IllegalArgumentException("FINAL_PACKAGE_TIME_BUDGET");
        }
        var packageEvidence=JudgeJson.JSON.createObjectNode().put("packageHash",hash).put("candidatesHash",saved[3]).put("testCount",candidates.stream().filter(c->!HybridPackagePlan.checkOnly(c)).count())
                .put("randomInputCount",4).put("generatorInputCount",4).put("boundedOracleInputCount",HybridPackagePlan.tests(candidates,"batch-oracle",profile).size())
                .put("readerInputsIncluded",data.get("READER").path("adversarialInputs").size()).put("packageExecutions",2)
                .put("generatorSeed",saved[0]).put("randomSeed",saved[1]).put("samplesMechanicallyDerived",true);
        finish(s,"CONTENT_REVIEW_REQUIRED",report(s,evidence,profile,policy,true,true,packageEvidence));
    }

}
