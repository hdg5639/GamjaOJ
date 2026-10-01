package dev.gamjaoj;

import java.util.*;
import java.util.regex.Pattern;

/** Display taxonomy is separate from immutable model artifacts and judge contracts. */
final class ProblemCategories {
    private static final Map<String,String> ALIASES=Map.ofEntries(
        Map.entry("graph","그래프"),Map.entry("graphs","그래프"),Map.entry("bfs","너비 우선 탐색"),
        Map.entry("dfs","깊이 우선 탐색"),Map.entry("graphsearch","그래프 탐색"),Map.entry("graphtraversal","그래프 탐색"),
        Map.entry("basicdatastructures","기초 자료구조"),Map.entry("datastructures","자료구조"),Map.entry("dynamictree","동적 트리"),
        Map.entry("tree","트리"),Map.entry("trees","트리"),Map.entry("dp","동적 계획법"),Map.entry("dynamicprogramming","동적 계획법"),
        Map.entry("shortestpath","최단 경로"),Map.entry("dijkstra","다익스트라"),Map.entry("greedy","탐욕법"),
        Map.entry("math","수학"),Map.entry("mathematics","수학"),Map.entry("numbertheory","정수론"),Map.entry("geometry","기하"),
        Map.entry("string","문자열"),Map.entry("strings","문자열"),Map.entry("sequence","수열"),Map.entry("sequences","수열"),
        Map.entry("sorting","정렬"),Map.entry("search","탐색"),Map.entry("binarysearch","이분 탐색"),
        Map.entry("implementation","구현"),Map.entry("simulation","시뮬레이션"),Map.entry("bruteforce","완전 탐색"),
        Map.entry("backtracking","백트래킹"),Map.entry("prefixsum","누적 합"),Map.entry("twopointers","두 포인터"),
        Map.entry("stack","스택"),Map.entry("queue","큐"),Map.entry("heap","힙"),Map.entry("unionfind","서로소 집합"),
        Map.entry("unrated","미분류"),Map.entry("uncategorized","미분류"));
    private static final Pattern LATIN=Pattern.compile("[A-Za-z]");
    static String display(String raw){
        if(raw==null||raw.isBlank())return "미분류";
        String value=raw.strip().replaceAll("\\s+"," ");
        String key=value.toLowerCase(Locale.ROOT).replaceAll("[\\s_/-]+","");
        if(ALIASES.containsKey(key))return ALIASES.get(key);
        var matcher=Pattern.compile("[A-Za-z][A-Za-z_-]*").matcher(value);var out=new StringBuilder();
        while(matcher.find()){
            String token=matcher.group().toLowerCase(Locale.ROOT).replaceAll("[_-]","");
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(ALIASES.getOrDefault(token,matcher.group())));
        }
        matcher.appendTail(out);
        return LATIN.matcher(out).find()?"기타":out.toString();
    }
    static String forSave(String raw){
        String value=display(raw);
        if(value.equals("기타")&&LATIN.matcher(raw).find())throw new AccountException(400,"분야명은 한글로 입력해 주세요. 예: 그래프, 동적 계획법, 자료구조");
        return value;
    }
}
