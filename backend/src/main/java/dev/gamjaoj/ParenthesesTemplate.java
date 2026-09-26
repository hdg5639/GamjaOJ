package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Trusted admission, expected outputs and adversarial cases; never supplied by the author. */
final class ParenthesesTemplate {
    static final String STATEMENT="입력은 (와 )로만 이루어진 길이 1 이상 1000 이하의 문자열 하나입니다. 모든 접두 구간에서 여는 괄호 수가 닫는 괄호 수 이상이고 전체 개수가 같으면 YES, 아니면 NO를 출력하세요. 문자열 내부에는 공백이 없으며 앞뒤의 공백과 줄바꿈은 무시합니다.";
    static ObjectNode test(String id,String input) {
        String value=input.strip();
        if(value.isEmpty()||value.length()>1000||!value.matches("[()]+"))throw new AccountException(400,"괄호 문자열의 길이와 문자를 확인해 주세요.");
        int balance=0;boolean valid=true;
        for(char c:value.toCharArray()) {balance+=c=='('?1:-1;if(balance<0)valid=false;}
        return JudgeJson.JSON.createObjectNode().put("id",id).put("input",input).put("output",valid&&balance==0?"YES\n":"NO\n");
    }
    static List<JsonNode> cases(long seed) {
        var tests=new ArrayList<JsonNode>();
        tests.add(test("sample","(())()\n"));
        for(int n=1;n<=4;n++)for(int mask=0;mask<(1<<n);mask++) {
            var value=new StringBuilder();for(int j=0;j<n;j++)value.append((mask&(1<<j))==0?'(':')');
            tests.add(test("small-"+n+"-"+mask,value+"\n"));
        }
        tests.add(test("whitespace-valid"," \t(())() \r\n"));
        tests.add(test("whitespace-invalid","\n\t)( \n"));
        tests.add(test("reversed",")(\n"));
        tests.add(test("early-close","())(()\n"));
        tests.add(test("unclosed","(()\n"));
        tests.add(test("deep","(".repeat(500)+")".repeat(500)+"\n"));
        tests.add(test("alternating","()".repeat(500)+"\n"));
        tests.add(test("all-open","(".repeat(1000)+"\n"));
        tests.add(test("all-close",")".repeat(1000)+"\n"));
        tests.add(test("late-extra-open","()".repeat(499)+"(\n"));
        tests.add(test("late-extra-close","()".repeat(499)+")\n"));
        Random random=new Random(seed);
        for(int i=0;i<5;i++) {
            int n=1+random.nextInt(1000);var value=new StringBuilder();
            for(int j=0;j<n;j++)value.append(random.nextBoolean()?'(':')');
            tests.add(test("seed-"+i,value+"\n"));
        }
        return tests;
    }
    static String mutant(boolean countsOnly) {
        return "import java.util.*; public class Main { public static void main(String[] args) { String s=new Scanner(System.in).next(); int n=0; boolean ok=true; for(char c:s.toCharArray()){n+=c=='('?1:-1;if(n<0)ok=false;} System.out.println("
                +(countsOnly?"n==0":"ok")+"?\"YES\":\"NO\"); }}";
    }
}
