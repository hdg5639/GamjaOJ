package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Explicit registry; compatibility never depends on a model-provided tag or label. */
final class HybridProfiles {
    static final Definition KNAPSACK=new Definition(HybridAdmission.PROFILE,HybridFiniteProfile.PACKAGE_POLICY);
    static final Definition BFS=new Definition(HybridBfsProfile.ID,HybridBfsProfile.POLICY);
    static final Definition DIJKSTRA=new Definition(HybridDijkstraProfile.ID,HybridDijkstraProfile.POLICY);
    static List<Definition> all(){return List.of(KNAPSACK,BFS,DIJKSTRA);}
    static Definition byId(String id){return all().stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("UNSUPPORTED_PROFILE"));}
    static Definition byPolicy(String policy) {
        if(HybridFiniteProfile.supports(policy))return new Definition(HybridAdmission.PROFILE,policy);
        if(BFS.policy.equals(policy))return BFS;
        if(DIJKSTRA.policy.equals(policy))return DIJKSTRA;
        throw new IllegalArgumentException("UNKNOWN_VALIDATION_POLICY");
    }
    static boolean supports(String policy){return HybridFiniteProfile.supports(policy)||BFS.policy.equals(policy)||DIJKSTRA.policy.equals(policy);}
    static boolean packaged(String policy){return HybridFiniteProfile.PACKAGE_POLICY.equals(policy)||BFS.policy.equals(policy)||DIJKSTRA.policy.equals(policy);}
    static Definition byContract(JsonNode contract){return all().stream().filter(p->p.contract().equals(contract)).findFirst().orElseThrow(()->new HybridArtifacts.Invalid("UNSUPPORTED_FINITE_CONTRACT"));}
    record Definition(String id,String policy) {
        boolean bfs(){return id.equals(HybridBfsProfile.ID);}
        boolean weighted(){return id.equals(HybridDijkstraProfile.ID);}
        boolean graph(){return bfs()||weighted();}
        JsonNode contract(){return weighted()?HybridDijkstraProfile.contract():bfs()?HybridBfsProfile.contract():HybridFiniteProfile.contract();}
        String hash(){return weighted()?HybridDijkstraProfile.hash():bfs()?HybridBfsProfile.hash():HybridFiniteProfile.hash(policy);}
        List<String> mutants(){return weighted()?HybridDijkstraProfile.mutants():bfs()?HybridBfsProfile.mutants():List.of("mutant-unbounded","mutant-strict-fit");}
        String mutant(String role){return weighted()?HybridDijkstraProfile.mutant(role):bfs()?HybridBfsProfile.mutant(role):HybridFiniteProfile.mutant(role);}
        List<JsonNode> tests(String role){return weighted()?HybridDijkstraProfile.tests(role):bfs()?HybridBfsProfile.tests(role):HybridFiniteProfile.tests(role);}
        List<HybridFiniteProfile.Case> stress(){return weighted()?HybridDijkstraProfile.stress():bfs()?HybridBfsProfile.stress():HybridFiniteProfile.stress();}
        List<HybridFiniteProfile.Case> invalid(){return weighted()?HybridDijkstraProfile.invalid():bfs()?HybridBfsProfile.invalid():HybridFiniteProfile.invalid();}
        Set<String> roles(boolean extended) {
            var r=new HashSet<>(Set.of("domain-valid","domain-invalid","domain-reference","domain-oracle"));
            if(extended){r.addAll(mutants());r.addAll(Set.of("stress-valid","stress-reference-0","stress-reference-1"));}return r;
        }
        Set<String> roles(){var r=roles(!HybridFiniteProfile.POLICY.equals(policy));if(packaged(policy))r.addAll(Set.of("package-generator","batch-valid","batch-reference","batch-oracle","package-final-0","package-final-1"));return r;}
        String answer(String input){return weighted()?HybridDijkstraProfile.parse(input).answer():bfs()?HybridBfsProfile.parse(input).answer():HybridPackagePlan.parse(input).answer();}
        boolean tiny(String input){return weighted()?HybridDijkstraProfile.parse(input).n()<=4:bfs()?HybridBfsProfile.parse(input).n()<=4:HybridPackagePlan.parse(input).tiny();}
        List<String> random(long seed){return weighted()?HybridDijkstraProfile.random(seed):bfs()?HybridBfsProfile.random(seed):HybridPackagePlan.random(seed);}
        void requireSupported(JsonNode c,JsonNode reader) {
            if(!graph()){HybridFiniteProfile.requireSupported(c,reader);return;}
            if(!c.equals(contract()))throw new IllegalArgumentException("UNSUPPORTED_FINITE_CONTRACT");
            if(!reader.path("oracleDomain").path("inputDomain").asText().equals("N <= 4")||!reader.path("oracleDomain").path("enumeration").asText().equals("Floyd-Warshall"))throw new IllegalArgumentException("UNSUPPORTED_ORACLE_DOMAIN");
        }
        JsonNode coverage(){return graph()?JudgeJson.JSON.createObjectNode().put("profileHash",hash()).put("cases",weighted()?18:16).put("description",weighted()?"8 three-vertex topologies with fixed positive weights and two sources, plus relaxation and 64-bit-distance witnesses.":"All 8 simple undirected graphs on 3 vertices; S=1 or 3, T=3.").put("entireContractExhausted",false).put("entireDeclaredOracleDomainExhausted",false).put("expectedAnswers","server Floyd-Warshall; not model-provided answers"):HybridFiniteProfile.coverage();}
        JsonNode maximumChecks(){var n=JudgeJson.JSON.createObjectNode().put("cases",2).put("repetitions",2).put("maxN",100).put("perTestMarginMs",4000).put("executionMode","EXCLUSIVE").put("worstCaseForEveryAlgorithm",false);if(graph())n.put("maxM",200);else n.put("maxW",1000);return n;}
        String supportVersion(){return weighted()?"dijkstra-support-v1":bfs()?"bfs-support-v1":"zero-one-support-v1";}
        String rulesVersion(){return weighted()?"dijkstra-ko-rules-v1":bfs()?"bfs-ko-rules-v1":"zero-one-ko-rules-v1";}
        String category(){return weighted()?"최단 경로":bfs()?"그래프 탐색":"동적 계획법";}
        String tags(){return weighted()?"다익스트라,최단 거리,가중치 그래프":bfs()?"BFS,최단 거리,무방향 그래프":"0/1 배낭,물건 선택";}
        String readerInstructions(){return weighted()?
            " The server supports the simple positive-weight undirected graph profile. Use exactly inputDomain=\"N <= 4\" and enumeration=\"Floyd-Warshall\". Independently implement a Java 8 Floyd-Warshall oracle with long distance arithmetic, not Dijkstra. Supply 1 to 8 valid small inputs with N <= 4, exactly M triples u v w, distinct unordered edges, no self-loops and weights 1..1000000000. Include S=T, unreachable, reverse traversal, a cheaper path with more edges, and a distance exceeding signed 32-bit range. No expected outputs, malformed inputs, ellipses or expanded stress cases. Server stress separately covers N=100/M=200. Derive semantics only from the public snapshot.":bfs()?
            " The server supports the simple unweighted undirected graph profile. Use exactly inputDomain=\"N <= 4\" and enumeration=\"Floyd-Warshall\". Independently implement a Java 8 Floyd-Warshall oracle for N <= 4, not the author's BFS. Supply 1 to 8 valid small cases with N <= 4, exactly M edge pairs, distinct unordered edges and no self-loops. Include S=T, unreachable and reverse-edge traversal. No expected outputs, malformed inputs, ellipses or hand-expanded stress cases. Server stress cases separately cover N=100/M=200. Derive all semantics only from the public snapshot.":
            " The server supports this public item-selection profile with an oracle limited to N <= 4, W <= 8, enumerating all subsets. Use exactly inputDomain=\"N <= 4, W <= 8\" and enumeration=\"all subsets\". Supply 1 to 8 small valid adversarial inputs, each with N <= 4 and W <= 8 and item costs/values within the public constraints. Write exactly N cost/value pairs for each input and verify the count. Do not emit malformed inputs, placeholders, ellipses or expanded maximum-size inputs: the server separately constructs and validates maximum-size stress cases. These are test-generation bounds, not changes to the public problem constraints. Derive the oracle independently from the public snapshot; no author implementation is supplied.";}
    }
}
