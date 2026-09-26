package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner opt-in for explicit fixed semantics. The creative-contract compatibility route is separate. */
@Service
class HybridAdmission {
    static final String PROFILE="zero-one-items-v1";
    private final JdbcClient jdbc;private final HybridExecution execution;private final HybridGeneration jobs;
    private final AiSettings settings;private final Submissions submissions;private final GenerationSpecDrafts drafts;
    HybridAdmission(JdbcClient jdbc,HybridExecution execution,HybridGeneration jobs,AiSettings settings,Submissions submissions,GenerationSpecDrafts drafts) {
        this.jdbc=jdbc;this.execution=execution;this.jobs=jobs;this.settings=settings;this.submissions=submissions;this.drafts=drafts;
    }
    record Profile(String id,String label,String description,List<String> rules) {}
    record Options(boolean enabled,String message,List<Profile> profiles) {}
    private static final Profile SUPPORTED=new Profile(PROFILE,"0/1 배낭 · 물건 선택",
            "규칙은 고정하고 새 본문·코드·힌트·해설과 테스트를 만듭니다. 새로운 알고리즘 규칙을 만드는 방식은 아닙니다.",
            List.of("각 물건은 최대 한 번 선택하며, 총 비용이 한도를 넘지 않도록 가치의 합을 최대화합니다.",
                    "물건 1~100개 · 한도 1~1,000 · 비용 1~1,000 · 가치 1~10,000",
                    "선택할 수 있는 물건이 없으면 0을 출력합니다. 같은 최댓값의 조합은 구분하지 않습니다."));
    private static final Profile BFS=new Profile(HybridBfsProfile.ID,"BFS · 무방향 그래프 최단 거리",
            "가중치 없는 그래프에서 두 정점 사이의 최소 이동 횟수를 구합니다. 기본 BFS 연습 유형이며 난이도 등급은 검토 전입니다.",
            List.of("간선은 양방향이며 하나를 이동할 때마다 거리가 1 증가합니다.","정점 1~100개 · 간선 최대 200개 · 자기 루프와 중복 간선 없음",
                    "출발점과 도착점이 같으면 0, 경로가 없으면 -1을 출력합니다."));
    private static final Profile DIJKSTRA=new Profile(HybridDijkstraProfile.ID,"다익스트라 · 가중치 최단 거리",
            "이동 비용이 다른 양방향 그래프에서 최소 비용을 구합니다. 난이도 등급은 검토 전입니다.",
            List.of("간선은 양방향이며 비용은 1~1,000,000,000입니다.","정점 1~100개 · 간선 최대 200개 · 자기 루프와 중복 간선 없음",
                    "출발점과 도착점이 같으면 0, 경로가 없으면 -1입니다. 거리의 합은 32비트 정수 범위를 넘을 수 있습니다."));
    Options options(String user) {
        boolean enabled=Boolean.parseBoolean(settings.value("HYBRID_PUBLIC_ADMISSION_ENABLED","false"))
                &&Arrays.stream(settings.value("HYBRID_ALLOWED_USERS","").split(",")).map(String::trim).anyMatch(user::equals)
                &&Boolean.parseBoolean(settings.value("HYBRID_ADMISSION_ENABLED","false"))
                &&Boolean.parseBoolean(settings.value("HYBRID_CONTENT_REVIEW_ENABLED","false"))
                &&Boolean.parseBoolean(settings.value("HYBRID_API_WORKER_ENABLED","false"))
                &&settings.enabled()&&!settings.key().isBlank()
                &&HybridFiniteProfile.PACKAGE_POLICY.equals(settings.value("HYBRID_VALIDATION_PROFILE",""));
        if(enabled)try {for(var role:List.of(HybridGeneration.Role.PRESENTATION,HybridGeneration.Role.READER,HybridGeneration.Role.CONTENT_REVIEW))HybridModels.slot(settings,role);}
        catch(AccountException unavailable){enabled=false;}
        return new Options(enabled,enabled?"검증을 통과한 문제만 게시합니다. 실패한 요청은 자동으로 다시 생성하지 않습니다.":"아직 이 계정에서는 실험 출제를 시작할 수 없어요. 기존 출제 방식은 계속 이용할 수 있습니다.",List.of(SUPPORTED,BFS,DIJKSTRA));
    }
    static boolean active(JdbcClient jdbc,UUID owner) {
        return jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE owner_id=? AND EXISTS (SELECT 1 FROM hybrid_api_reservation r WHERE r.generation_id=hybrid_generation.id) AND deadline_at>CURRENT_TIMESTAMP AND (status IN ('QUEUED','DESIGNING','BUILDING','VALIDATING','REVIEWING') OR (status='HELD' AND error_code IN ('VALIDATION_ADAPTER_NOT_CONNECTED','CONTENT_REVIEW_REQUIRED')))")
                .param(owner).query(Integer.class).single()>0;
    }
    @Transactional
    HybridGeneration.Progress create(String user,UUID id,JsonNode request) {
        try {
            HybridArtifacts.fields(request,"profileId","shared","publishOnSuccess");
            HybridArtifacts.require(request.path("profileId").isTextual()&&HybridProfiles.all().stream().anyMatch(p->p.id().equals(request.path("profileId").asText())),"UNSUPPORTED_PROFILE");
            HybridArtifacts.require(request.path("shared").isBoolean()&&request.path("publishOnSuccess").isBoolean()&&request.path("publishOnSuccess").asBoolean(),"EXPLICIT_PUBLICATION_REQUIRED");
        }catch(HybridArtifacts.Invalid invalid){throw new AccountException(400,"지원되는 규칙과 검증 후 게시 여부를 선택해 주세요. 자유 요청은 직접 요청하기를 이용해 주세요.");}
        jdbc.sql("SELECT id FROM ai_budget_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        String canonical=JudgeJson.canonical(request);boolean shared=request.path("shared").asBoolean();
        // An acknowledged or uncertain request can be recovered even after new admission is disabled.
        if(jdbc.sql("SELECT count(*) FROM hybrid_public_request WHERE generation_id=?").param(id).query(Integer.class).single()>0)
            return jobs.start(user,id,canonical,shared);
        if(!options(user).enabled())throw new AccountException(503,options(user).message());
        if(jdbc.sql("SELECT count(*) FROM hybrid_generation WHERE id=?").param(id).query(Integer.class).single()>0)
            throw new AccountException(409,"다른 출제 경로에서 사용한 요청 키예요.");
        UUID owner=submissions.owner(user,false);
        if(drafts.active(owner)||jdbc.sql("SELECT count(*) FROM generation_job WHERE owner_id=? AND status IN ('QUEUED','GENERATING','AWAITING_REVIEW','VALIDATING')").param(owner).query(Integer.class).single()>0)
            throw new AccountException(409,"진행 중인 출제를 먼저 마쳐 주세요.");
        execution.admit(user,id,canonical,shared);
        var selected=HybridProfiles.byId(request.path("profileId").asText());
        var contract=selected.contract();
        jdbc.sql("INSERT INTO hybrid_public_request(generation_id,profile_id,profile_hash,contract_sha256,handoff_mode) VALUES (?,?,?,?,'SERVER_FIXED_CONTRACT_V1')")
                .param(id).param(selected.id()).param(selected.hash()).param(JudgeJson.hash(JudgeJson.canonical(contract))).update();
        // The selected semantics are already complete. Freeze server data through the same DAG acceptance.
        // There is no model call that merely copies this contract, and no claim of novel semantics.
        var a=jobs.claim(id,HybridGeneration.Role.CONTRACT);
        jobs.complete(new HybridGeneration.Completion(a.branchId(),a.revision(),a.role(),a.token(),a.inputHash(),a.contractHash(),a.publicHash(),contract,
                JudgeJson.JSON.createObjectNode().put("executor","SERVER_FIXED_CONTRACT_V1").put("billingMode","NONE"),null));
        return jobs.view(user,id);
    }
    List<HybridGeneration.Progress> list(String user) {
        UUID owner=submissions.owner(user,false);
        return jdbc.sql("SELECT g.id FROM hybrid_generation g JOIN hybrid_public_request r ON r.generation_id=g.id WHERE g.owner_id=? ORDER BY g.created_at DESC,g.id LIMIT 30")
                .param(owner).query(UUID.class).list().stream().map(id->jobs.view(user,id)).toList();
    }
}
