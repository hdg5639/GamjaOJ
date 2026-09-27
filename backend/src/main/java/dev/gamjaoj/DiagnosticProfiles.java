package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-category view of one evaluation. Categories come from the cited submissions' diagnostic items
 * (server data, never model text); rule matches are keyword matches on registered rule catalogs, shown as
 * name matches rather than verified recommendations.
 */
@Service
class DiagnosticProfiles {
    /** Diagnostic category id -> words that identify the same algorithm family in rule catalogs. */
    static final Map<String,List<String>> RULE_KEYWORDS=Map.ofEntries(
            Map.entry("implementation",List.of("구현","시뮬레이션")),
            Map.entry("arrays-strings",List.of("배열","문자열","누적 합","누적합","투 포인터","슬라이딩")),
            Map.entry("basic-data-structures",List.of("자료구조","스택","큐","덱","괄호","해시")),
            Map.entry("basic-search",List.of("탐색","BFS","DFS")),
            Map.entry("bfs",List.of("BFS","너비 우선")),
            Map.entry("dfs",List.of("DFS","깊이 우선","트리","연결 요소")),
            Map.entry("backtracking",List.of("백트래킹","순열","조합","부분집합")),
            Map.entry("dp",List.of("동적 계획법","DP","배낭","냅색")),
            Map.entry("binary-search",List.of("이분 탐색","이진 탐색","파라메트릭","lower bound","upper bound")),
            Map.entry("greedy",List.of("그리디","탐욕","위상 정렬")),
            Map.entry("graph",List.of("최단 경로","다익스트라","플로이드","벨만","그래프")),
            Map.entry("mst",List.of("최소 신장","MST","크루스칼","프림","유니온 파인드")));
    record Observation(int index,String tone,String pattern,String risk,String quote,boolean repeated) {}
    record Category(String id,boolean selected,List<JsonNode> items,List<Observation> observations,List<Integer> alsoSeen,List<String> ruleIds) {}
    record Profile(UUID evaluationId,String status,List<Category> categories,List<HybridAdmission.Profile> rules,boolean ruleOnboardingEnabled) {}
    private final JdbcClient jdbc;private final DiagnosticEvaluations evaluations;private final HybridAdmission admission;private final HybridRuleOnboarding onboarding;
    DiagnosticProfiles(JdbcClient jdbc,DiagnosticEvaluations evaluations,HybridAdmission admission,HybridRuleOnboarding onboarding) {
        this.jdbc=jdbc;this.evaluations=evaluations;this.admission=admission;this.onboarding=onboarding;
    }
    static boolean matches(String category,HybridAdmission.Profile rule) {
        String text=(rule.label()+" "+rule.category()+" "+String.join(" ",rule.tags())).toLowerCase(Locale.ROOT);
        return RULE_KEYWORDS.getOrDefault(category,List.of()).stream().anyMatch(k->text.contains(k.toLowerCase(Locale.ROOT)));
    }
    static List<String> matchingRules(String category,List<HybridAdmission.Profile> rules) {
        return rules.stream().filter(r->matches(category,r)).map(HybridAdmission.Profile::id).toList();
    }
    /** Category of the diagnostic item behind a submission of this session, if any. */
    Optional<String> category(UUID session,String submission) {
        try {
            return jdbc.sql("SELECT i.category FROM submission s JOIN diagnostic_item i ON i.id=s.diagnostic_item_id WHERE s.id=? AND i.session_id=?")
                    .param(UUID.fromString(submission)).param(session).query(String.class).optional();
        }catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    @Transactional
    public Profile profile(String username,UUID session,UUID evaluation) {
        var view=evaluations.detail(username,evaluation);
        if(!view.sessionId().equals(session))throw new AccountException(404,"진단 평가를 찾을 수 없어요.");
        String bank=jdbc.sql("SELECT bank_id FROM diagnostic_session WHERE id=?").param(session).query(String.class).single();
        var bankCategories=jdbc.sql("SELECT DISTINCT category FROM diagnostic_bank_item WHERE bank_id=? ORDER BY category").param(bank).query(String.class).list();
        var items=new LinkedHashMap<String,List<JsonNode>>();
        for(var item:view.facts().path("items"))items.computeIfAbsent(item.path("category").asText(),k->new ArrayList<>()).add(item);
        var observed=new HashMap<String,List<Observation>>();var alsoSeen=new HashMap<String,List<Integer>>();
        var interpretation=view.interpretation();
        if(interpretation!=null) {
            int index=0;
            for(var o:interpretation.path("observations")) {
                final int at=index++;
                var home=category(session,o.path("submissionId").asText());
                if(home.isEmpty())continue;
                var others=new TreeSet<String>();
                for(var id:o.path("alsoSeenIn"))category(session,id.asText()).filter(c->!c.equals(home.get())).ifPresent(others::add);
                observed.computeIfAbsent(home.get(),k->new ArrayList<>()).add(new Observation(at,o.path("tone").asText(null),o.path("pattern").asText(null),
                        o.path("risk").asText(null),o.path("quote").asText(),!others.isEmpty()));
                for(String other:others)alsoSeen.computeIfAbsent(other,k->new ArrayList<>()).add(at);
            }
        }
        var rules=admission.selectable(username);
        var categories=new ArrayList<Category>();
        var order=new LinkedHashSet<String>(items.keySet());order.addAll(bankCategories);
        for(String id:order)categories.add(new Category(id,items.containsKey(id),items.getOrDefault(id,List.of()),observed.getOrDefault(id,List.of()),
                alsoSeen.getOrDefault(id,List.of()),matchingRules(id,rules)));
        return new Profile(evaluation,view.status(),categories,rules,onboarding.enabled());
    }
}
