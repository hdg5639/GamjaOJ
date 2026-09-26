package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fixed-profile prose is pinned before the writer runs, then included in the reader snapshot. */
final class HybridPresentationRules {
    static ObjectNode bundle() {return bundle(HybridProfiles.KNAPSACK);}
    static ObjectNode bundle(HybridProfiles.Definition profile) {
        var b=JudgeJson.JSON.createObjectNode().put("version",profile.rulesVersion());
        b.put("semanticsHash",JudgeJson.hash(JudgeJson.canonical(HybridArtifacts.publicSemantics(profile.contract()))));
        if(profile.pkg()!=null) {
            b.set("rules",profile.pkg().rules().deepCopy());
            b.put("rulesHash",JudgeJson.hash(JudgeJson.canonical(b.path("rules"))));return b;
        }
        b.putArray("rules").addObject().put("id",profile.graph()?"traverse":"choose").put("text",
                profile.weighted()?"모든 간선은 양방향이며, 이동할 때마다 해당 간선의 가중치 w만큼 비용이 증가합니다. 출발 정점 S에서 도착 정점 T까지 이동하는 최소 비용의 합을 출력합니다. S와 T가 같으면 0, 경로가 없으면 -1을 출력합니다. 정점 번호는 1부터 N까지이며 자기 루프와 중복된 무방향 간선은 없습니다. 가중치는 1 이상 1,000,000,000 이하의 정수이며 거리의 합은 32비트 정수 범위를 넘을 수 있습니다.":
                profile.bfs()?"모든 간선은 양방향이며, 간선 하나를 이동할 때마다 거리가 1 증가합니다. 출발 정점 S에서 도착 정점 T까지 이동하는 데 필요한 최소 간선 수를 출력합니다. S와 T가 같으면 0, 연결된 경로가 없으면 -1을 출력합니다. 정점 번호는 1부터 N까지이며 자기 자신을 잇는 간선과 중복된 무방향 간선은 없습니다.":
                "각 물건은 최대 한 번 선택할 수 있습니다. 물건의 비용이 남은 용량 이하일 때만 선택할 수 있습니다(비용 ≤ 남은 용량). 선택하면 남은 용량에서 비용을 빼고 총 가치에 해당 물건의 가치를 더합니다. 선택한 물건들의 총 비용은 W를 넘을 수 없습니다. 아무 물건도 선택하지 않아도 되며 이때 총 가치는 0입니다. 가능한 선택 중 총 가치의 최댓값을 출력합니다.");
        b.put("rulesHash",JudgeJson.hash(JudgeJson.canonical(b.path("rules"))));return b;
    }
    static void validate(JsonNode input) {
        var b=input.path("serverRules");
        HybridArtifacts.fields(b,"version","semanticsHash","rules","rulesHash");
        var profile=HybridProfiles.all().stream().filter(p->input.path("semantics").equals(HybridArtifacts.publicSemantics(p.contract()))).findFirst().orElseThrow(()->new HybridArtifacts.Invalid("PRESENTATION_RULES_FENCE"));
        HybridArtifacts.require(b.path("version").asText().equals(profile.rulesVersion())
                &&b.path("semanticsHash").asText().equals(JudgeJson.hash(JudgeJson.canonical(input.path("semantics"))))
                &&b.path("rulesHash").asText().equals(JudgeJson.hash(JudgeJson.canonical(b.path("rules")))),"PRESENTATION_RULES_FENCE");
    }
    static JsonNode assemble(JsonNode input,JsonNode payload,JsonNode contract) {
        if(!input.has("serverRules"))return HybridArtifacts.presentation(payload,contract);
        validate(input);
        HybridArtifacts.fields(payload,"schemaVersion","title","context","hints","editorial");
        var result=(ObjectNode)payload.deepCopy();
        result.set("semantics",input.path("semantics").deepCopy());
        result.set("ruleExplanations",input.path("serverRules").path("rules").deepCopy());
        return HybridArtifacts.presentation(result,contract);
    }
}
