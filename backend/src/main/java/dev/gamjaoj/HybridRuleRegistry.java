package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-backed rule catalog. A version is selectable only while ACTIVE and its stored execution identity
 * still matches the engine that runs it. Catalog text is cosmetic; hashes are the identity.
 */
@Service
class HybridRuleRegistry {
    private static final Logger log=LoggerFactory.getLogger(HybridRuleRegistry.class);
    static final String BUILTIN="BUILTIN_V1",REUSED_EXECUTOR="REGISTRY_ARTIFACT_V1";
    record Version(String id,String familyId,String engine,String policy,String profileHash,String contractHash,
                   String status,JsonNode catalog,String packageJson,String visibility,UUID owner,boolean shared) {
        String label(){return catalog.path("label").asText();}
        String category(){return ProblemCategories.display(catalog.path("category").asText());}
        String tags(){var out=new ArrayList<String>();catalog.path("tags").forEach(t->out.add(t.asText()));return String.join(",",out);}
    }
    record Reference(UUID id,JsonNode payload) {}
    private final JdbcClient jdbc;
    HybridRuleRegistry(JdbcClient jdbc){this.jdbc=jdbc;}

    private static JsonNode catalog(String label,String description,String category,List<String> tags,String... rules) {
        var c=JudgeJson.JSON.createObjectNode().put("label",label).put("description",description).put("category",category);
        var t=c.putArray("tags");tags.forEach(t::add);var r=c.putArray("rules");for(String rule:rules)r.add(rule);return c;
    }
    /** Built-in catalog text, previously hard-coded in admission and publication. */
    static final Map<String,JsonNode> BUILTIN_CATALOG=Map.of(
            HybridAdmission.PROFILE,catalog("0/1 배낭 · 물건 선택",
                    "규칙은 고정하고 새 본문·코드·힌트·해설과 테스트를 만듭니다. 새로운 알고리즘 규칙을 만드는 방식은 아닙니다.",
                    "동적 계획법",List.of("0/1 배낭","물건 선택"),
                    "각 물건은 최대 한 번 선택하며, 총 비용이 한도를 넘지 않도록 가치의 합을 최대화합니다.",
                    "물건 1~100개 · 한도 1~1,000 · 비용 1~1,000 · 가치 1~10,000",
                    "선택할 수 있는 물건이 없으면 0을 출력합니다. 같은 최댓값의 조합은 구분하지 않습니다."),
            HybridBfsProfile.ID,catalog("BFS · 무방향 그래프 최단 거리",
                    "가중치 없는 그래프에서 두 정점 사이의 최소 이동 횟수를 구합니다. 기본 BFS 연습 유형이며 난이도 등급은 검토 전입니다.",
                    "그래프 탐색",List.of("BFS","최단 거리","무방향 그래프"),
                    "간선은 양방향이며 하나를 이동할 때마다 거리가 1 증가합니다.","정점 1~100개 · 간선 최대 200개 · 자기 루프와 중복 간선 없음",
                    "출발점과 도착점이 같으면 0, 경로가 없으면 -1을 출력합니다."),
            HybridDijkstraProfile.ID,catalog("다익스트라 · 가중치 최단 거리",
                    "이동 비용이 다른 양방향 그래프에서 최소 비용을 구합니다. 난이도 등급은 검토 전입니다.",
                    "최단 경로",List.of("다익스트라","최단 거리","가중치 그래프"),
                    "간선은 양방향이며 비용은 1~1,000,000,000입니다.","정점 1~100개 · 간선 최대 200개 · 자기 루프와 중복 간선 없음",
                    "출발점과 도착점이 같으면 0, 경로가 없으면 -1입니다. 거리의 합은 32비트 정수 범위를 넘을 수 있습니다."));

    private static String contractHash(HybridProfiles.Definition d){return JudgeJson.hash(JudgeJson.canonical(d.contract()));}
    private Optional<Version> row(String id) {
        return jdbc.sql("SELECT v.id,v.family_id,v.engine,v.validation_policy,v.profile_sha256,v.contract_sha256,v.status,v.catalog_json,v.package_json,f.visibility,f.owner_id,f.shared FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE v.id=?")
                .param(id).query((r,n)->new Version(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),
                        r.getString(8)==null?JudgeJson.JSON.createObjectNode():JudgeJson.parse(r.getString(8)),r.getString(9),r.getString(10),
                        r.getObject(11,UUID.class),r.getBoolean(12))).optional();
    }
    /** Built-in execution definition for a stored version, or empty when identity no longer matches. */
    private Optional<HybridProfiles.Definition> engine(Version v) {
        if(HybridRulePackage.ENGINE.equals(v.engine)) {
            // Data package: stored bytes are the identity; a stored hash mismatch is never repaired.
            try {
                var stored=JudgeJson.parse(v.packageJson);var pkg=HybridRulePackage.parse(v.id,stored);
                var d=HybridProfiles.Definition.of(pkg,pkg.hash(stored));
                if(!d.policy().equals(v.policy)||!d.hash().equals(v.profileHash)||!contractHash(d).equals(v.contractHash))return Optional.empty();
                HybridProfiles.register(d);return Optional.of(d);
            }catch(RuntimeException invalid){return Optional.empty();}
        }
        if(!BUILTIN.equals(v.engine))return Optional.empty();
        try {
            var d=HybridProfiles.byId(v.id);
            return d.policy().equals(v.policy)&&d.hash().equals(v.profileHash)&&contractHash(d).equals(v.contractHash)?Optional.of(d):Optional.empty();
        }catch(IllegalArgumentException unknown){return Optional.empty();}
    }

    /** Fills imported built-in hashes once and quarantines any version whose executing code drifted. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    void sync() {
        for(String id:jdbc.sql("SELECT id FROM hybrid_rule_version WHERE engine=? AND status='ACTIVE'").param(HybridRulePackage.ENGINE).query(String.class).list()) {
            if(engine(row(id).orElseThrow()).isPresent())continue;
            log.error("Rule package {} failed identity verification; quarantined",id);HybridProfiles.unregister(id);
            jdbc.sql("UPDATE hybrid_rule_version SET status='QUARANTINED',status_reason='EXECUTION_IDENTITY_MISMATCH',updated_at=? WHERE id=?")
                    .param(OffsetDateTime.now(ZoneOffset.UTC)).param(id).update();
        }
        for(var d:HybridProfiles.builtins()) {
            var v=row(d.id());
            if(v.isEmpty()){log.error("Built-in rule version {} missing from registry; not selectable",d.id());continue;}
            jdbc.sql("UPDATE hybrid_rule_version SET profile_sha256=?,contract_sha256=?,updated_at=? WHERE id=? AND profile_sha256 IS NULL AND contract_sha256 IS NULL")
                    .param(d.hash()).param(contractHash(d)).param(OffsetDateTime.now(ZoneOffset.UTC)).param(d.id()).update();
            jdbc.sql("UPDATE hybrid_rule_version SET catalog_json=? WHERE id=?").param(JudgeJson.canonical(BUILTIN_CATALOG.get(d.id()))).param(d.id()).update();
            var stored=row(d.id()).orElseThrow();
            if(engine(stored).isEmpty()&&stored.status.equals("ACTIVE")) {
                log.error("Rule version {} execution identity changed; quarantined",d.id());
                jdbc.sql("UPDATE hybrid_rule_version SET status='QUARANTINED',status_reason='EXECUTION_IDENTITY_MISMATCH',updated_at=? WHERE id=?")
                        .param(OffsetDateTime.now(ZoneOffset.UTC)).param(d.id()).update();
            }
        }
        // Earlier publications become reusable only when their exact rule identity still matches.
        var published=jdbc.sql("SELECT g.id FROM hybrid_generation g JOIN hybrid_public_request r ON r.generation_id=g.id WHERE g.status='PUBLISHED' AND r.reference_artifact_id IS NULL ORDER BY g.updated_at")
                .query(UUID.class).list();
        for(UUID id:published)qualify(id);
    }
    private static boolean visible(Version v,UUID viewer){return "BUILTIN".equals(v.visibility)||v.shared||(viewer!=null&&viewer.equals(v.owner));}
    /** Built-ins, the viewer's own registered rules and rules their owners explicitly shared. */
    List<Version> selectable(UUID viewer) {
        return jdbc.sql("SELECT id FROM hybrid_rule_version WHERE status='ACTIVE' ORDER BY sort_order,created_at,id").query(String.class).list()
                .stream().map(id->row(id).orElseThrow()).filter(v->visible(v,viewer)).filter(v->engine(v).isPresent()).toList();
    }
    List<Version> selectable(){return selectable(null);}
    Optional<Version> version(String id){return row(id);}
    /** Admission-time resolution. Unknown, invisible, retired, quarantined or drifted versions are rejected. */
    Optional<HybridProfiles.Definition> resolve(String versionId,UUID viewer) {
        return row(versionId).filter(v->v.status.equals("ACTIVE")&&visible(v,viewer)).flatMap(this::engine);
    }
    Optional<HybridProfiles.Definition> resolve(String versionId){return resolve(versionId,null);}
    /** Owner's registered rules, including inactive ones, for the registration screen. */
    List<Version> owned(UUID owner) {
        return jdbc.sql("SELECT v.id FROM hybrid_rule_version v JOIN hybrid_rule_family f ON f.id=v.family_id WHERE f.owner_id=? ORDER BY v.created_at DESC,v.id")
                .param(owner).query(String.class).list().stream().map(id->row(id).orElseThrow()).toList();
    }
    /** Desired-state sharing; safe to repeat after response loss. Only the owner may change it. */
    @Transactional
    Version share(UUID owner,String versionId,boolean shared) {
        var v=row(versionId).filter(x->owner.equals(x.owner)).orElseThrow(()->new AccountException(404,"등록한 규칙을 찾을 수 없어요."));
        if(shared&&!v.status.equals("ACTIVE"))throw new AccountException(409,"사용할 수 있는 규칙만 공개할 수 있어요.");
        jdbc.sql("UPDATE hybrid_rule_family SET shared=? WHERE id=? AND owner_id=?").param(shared).param(v.familyId).param(owner).update();
        return row(versionId).orElseThrow();
    }
    /**
     * Activates a qualified package and its qualified reference atomically. The caller has verified every
     * qualification obligation in the Runner; this method only persists that exact evidence.
     */
    HybridProfiles.Definition activate(UUID owner,UUID onboarding,String versionId,JsonNode stored,JsonNode reference) {
        var pkg=HybridRulePackage.parse(versionId,stored);var d=HybridProfiles.Definition.of(pkg,pkg.hash(stored));
        String contract=contractHash(d);
        if(jdbc.sql("SELECT count(*) FROM hybrid_rule_version WHERE contract_sha256=? AND status='ACTIVE'").param(contract).query(Integer.class).single()>0)
            throw new HybridArtifacts.Invalid("DUPLICATE_RULE_CONTRACT");
        var now=OffsetDateTime.now(ZoneOffset.UTC);String family="family-"+versionId;
        jdbc.sql("INSERT INTO hybrid_rule_family(id,visibility,owner_id,shared) VALUES (?,'MEMBER',?,false)").param(family).param(owner).update();
        jdbc.sql("INSERT INTO hybrid_rule_version(id,family_id,engine,validation_policy,profile_sha256,contract_sha256,status,catalog_json,sort_order,package_json,created_at,updated_at) VALUES (?,?,?,?,?,?,'ACTIVE',?,1000,?,?,?)")
                .param(versionId).param(family).param(HybridRulePackage.ENGINE).param(d.policy()).param(d.hash()).param(contract)
                .param(JudgeJson.canonical(pkg.catalog())).param(JudgeJson.canonical(stored)).param(now).param(now).update();
        String raw=JudgeJson.canonical(reference);
        jdbc.sql("INSERT INTO hybrid_rule_artifact(id,rule_version_id,kind,payload_json,payload_sha256,source_onboarding_id,status,qualified_at) VALUES (?,?,'REFERENCE',?,?,?,'QUALIFIED',?)")
                .param(UUID.randomUUID()).param(versionId).param(raw).param(JudgeJson.hash(raw)).param(onboarding).param(now).update();
        HybridProfiles.register(d);return d;
    }
    /** Latest qualified reference whose source problem is still published and not held. */
    Optional<Reference> qualifiedReference(String versionId) {
        var rows=jdbc.sql("SELECT a.id,a.payload_json,a.payload_sha256 FROM hybrid_rule_artifact a LEFT JOIN problem_version p ON p.id=a.source_version_id WHERE a.rule_version_id=? AND a.kind='REFERENCE' AND a.status='QUALIFIED' AND (a.source_onboarding_id IS NOT NULL OR (p.ready=true AND p.review_hold=false)) ORDER BY a.qualified_at DESC,a.id")
                .param(versionId).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getString(3)}).list();
        for(var row:rows)if(JudgeJson.hash((String)row[1]).equals(row[2]))return Optional.of(new Reference((UUID)row[0],JudgeJson.parse((String)row[1])));
        return Optional.empty();
    }
    boolean stillQualified(UUID artifact) {
        return jdbc.sql("SELECT count(*) FROM hybrid_rule_artifact a LEFT JOIN problem_version p ON p.id=a.source_version_id JOIN hybrid_rule_version v ON v.id=a.rule_version_id WHERE a.id=? AND a.status='QUALIFIED' AND v.status='ACTIVE' AND (a.source_onboarding_id IS NOT NULL OR (p.ready=true AND p.review_hold=false))")
                .param(artifact).query(Integer.class).single()==1;
    }
    /**
     * Records the reference of a freshly authored, fully validated and reviewed publication.
     * Reused instances do not re-qualify their own source. Idempotent per version and payload.
     */
    void qualify(UUID generation) {
        var request=jdbc.sql("SELECT r.rule_version_id,r.profile_hash,r.contract_sha256,r.reference_artifact_id,g.published_version_id,g.revision FROM hybrid_public_request r JOIN hybrid_generation g ON g.id=r.generation_id WHERE g.id=? AND g.status='PUBLISHED'")
                .param(generation).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getObject(4,UUID.class),r.getString(5),r.getInt(6)}).optional();
        if(request.isEmpty()||request.get()[0]==null||request.get()[3]!=null||request.get()[4]==null)return;
        var v=row((String)request.get()[0]).flatMap(this::engine);
        if(v.isEmpty()||!v.get().hash().equals(request.get()[1])||!contractHash(v.get()).equals(request.get()[2]))return;
        var core=jdbc.sql("SELECT b.id,b.completion_json,a.payload_json,a.payload_sha256,b.output_sha256 FROM hybrid_branch b JOIN hybrid_artifact a ON a.branch_id=b.id WHERE b.generation_id=? AND b.revision=? AND b.role='CORE' AND b.status='SUCCEEDED'")
                .param(generation).param((Integer)request.get()[5]).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).optional();
        if(core.isEmpty()||!JudgeJson.hash((String)core.get()[2]).equals(core.get()[3])||!core.get()[3].equals(core.get()[4]))return;
        if(REUSED_EXECUTOR.equals(JudgeJson.parse((String)core.get()[1]).path("usage").path("executor").asText()))return;
        var assembled=JudgeJson.parse((String)core.get()[2]);
        var payload=JudgeJson.JSON.createObjectNode().put("schemaVersion",assembled.path("schemaVersion").asText())
                .put("reference",assembled.path("reference").asText());
        payload.set("authorNotes",assembled.path("authorNotes"));
        String raw=JudgeJson.canonical(payload),hash=JudgeJson.hash(raw);
        if(jdbc.sql("SELECT count(*) FROM hybrid_rule_artifact WHERE rule_version_id=? AND kind='REFERENCE' AND payload_sha256=?")
                .param(request.get()[0]).param(hash).query(Integer.class).single()>0)return;
        jdbc.sql("INSERT INTO hybrid_rule_artifact(id,rule_version_id,kind,payload_json,payload_sha256,source_generation_id,source_branch_id,source_version_id,status,qualified_at) VALUES (?,?,'REFERENCE',?,?,?,?,?,'QUALIFIED',?)")
                .param(UUID.randomUUID()).param(request.get()[0]).param(raw).param(hash).param(generation).param(core.get()[0])
                .param(request.get()[4]).param(OffsetDateTime.now(ZoneOffset.UTC)).update();
    }
}
