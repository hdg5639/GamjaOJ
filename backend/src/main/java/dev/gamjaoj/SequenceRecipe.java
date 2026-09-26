package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Bounded, non-executable declaration language. No Java/script supplied by users runs here. */
final class SequenceRecipe implements ProblemContract {
    public String categoryId(){return "sequences";}
    public String categoryLabel(){return "수열·수치";}
    public String smallDomain(){return "all N=1..2 over {-2,-1,0,1,2}";}
    public String mutantRole(boolean first){return first?"mutant-filter":"mutant-last";}
    public List<JsonNode> mutantCases(List<JsonNode> cases){return cases.stream().filter(t->List.of("sample","positive-boundary","negative-boundary","singleton-positive","singleton-negative").contains(t.path("id").asText())).toList();}
    static final String PREFIX="sequence-recipe-v1-";
    static final long LIMIT=10_000_000L;
    enum Filter { ALL, POSITIVE, NEGATIVE, EVEN, ODD }
    enum Transform { IDENTITY, ABS, SQUARE }
    enum Reduction { SUM, COUNT }
    final Filter filter; final Transform transform; final Reduction reduction;
    SequenceRecipe(Filter filter,Transform transform,Reduction reduction) {
        if(reduction==Reduction.COUNT&&transform!=Transform.IDENTITY)
            throw new AccountException(400,"개수 세기에는 값 변환 태그를 함께 사용할 수 없어요.");
        this.filter=filter;this.transform=transform;this.reduction=reduction;
    }
    static SequenceRecipe parse(String id) {
        try {
            String[] parts=id.substring(PREFIX.length()).split("-",-1);
            if(parts.length!=3)throw new IllegalArgumentException();
            return new SequenceRecipe(Filter.valueOf(parts[0]),Transform.valueOf(parts[1]),Reduction.valueOf(parts[2]));
        } catch(RuntimeException e) {throw new AccountException(400,"지원하지 않는 수열 계약이에요.");}
    }
    public String id(){return PREFIX+filter+"-"+transform+"-"+reduction;}
    boolean accepts(long x){return switch(filter){case ALL->true;case POSITIVE->x>0;case NEGATIVE->x<0;case EVEN->x%2==0;case ODD->x%2!=0;};}
    long value(long x){return reduction==Reduction.COUNT?1:switch(transform){case IDENTITY->x;case ABS->Math.abs(x);case SQUARE->Math.multiplyExact(x,x);};}
    String selected(){return switch(filter){case ALL->"모든 정수";case POSITIVE->"양수인 정수";case NEGATIVE->"음수인 정수";case EVEN->"짝수인 정수(0 포함)";case ODD->"홀수인 정수(음수 포함)";};}
    public String title(){return selected()+"의 "+(reduction==Reduction.COUNT?"개수":switch(transform){case IDENTITY->"합";case ABS->"절댓값 합";case SQUARE->"제곱 합";});}
    public String statement(){return "첫째 줄에 정수 N(1 ≤ N ≤ 1000)이 주어진다. 다음 줄에 N개의 정수가 주어진다. 각 정수는 -10000000 이상 10000000 이하이다. 원래 값에서 "+selected()+"를 선택한다. "+
        (reduction==Reduction.COUNT?"선택한 정수의 개수를 출력한다.":"선택한 각 정수의 "+switch(transform){case IDENTITY->"원래 값";case ABS->"절댓값";case SQUARE->"제곱";}+"을 모두 더해 출력한다. 곱셈과 합계는 64비트 정수로 계산한다.")+" 선택된 정수가 없으면 0을 출력한다.";}
    public List<String> operationTags(){var tags=new ArrayList<String>();if(filter!=Filter.ALL)tags.add("filter-"+filter.name().toLowerCase(Locale.ROOT));if(transform!=Transform.IDENTITY)tags.add(transform==Transform.ABS?"absolute-values":"squares");if(reduction==Reduction.COUNT)tags.add("count");return tags;}
    public ObjectNode spec(){var spec=GenerationTemplate.spec().put("templateId",id()).put("statement",statement()).put("contractFamily","sequence-recipe-v1");
        spec.put("inputLayoutPolicy","canonical-lines-v1: server restores N on line 1 and N values on line 2 from generator transport records");
        spec.put("generatorContract","Main reads one long seed; outputs exactly four lines. Each line is N followed by exactly N integers, 1<=N<=10, each value between -10000000 and 10000000. No answers. Cover mixed signs, zero, boundary values and seeded randomness; deterministic for seed.");
        spec.put("validatorContract","Print VALID only for exactly N integer values after N, 1<=N<=1000, each value in [-10000000,10000000]. INVALID otherwise, including extra/missing/malformed/huge tokens. Do not reject valid inputs whose selected set is empty.");
        spec.putObject("recipe").put("filter",filter.name()).put("transform",transform.name()).put("reduction",reduction.name()).put("valueLimit",LIMIT).put("maxLength",1000);
        spec.set("sample",test("sample",GenerationTemplate.input(1,-2,3)));return spec;}
    public ObjectNode test(String id,String input){try {
        var words=input.strip().split("\\s+");int n=Integer.parseInt(words[0]);if(n<1||n>1000||words.length!=n+1)throw new IllegalArgumentException();
        long result=0;for(int i=1;i<=n;i++){long x=Long.parseLong(words[i]);if(x < -LIMIT||x>LIMIT)throw new IllegalArgumentException();if(accepts(x))result=Math.addExact(result,value(x));}
        return JudgeJson.JSON.createObjectNode().put("id",id).put("input",InputLayout.sequence(input)).put("output",result+"\n");
    }catch(RuntimeException e){throw new AccountException(400,"입력이 선언된 수열 계약 범위를 벗어났어요.");}}
    public List<JsonNode> cases(long seed){var result=new ArrayList<JsonNode>();
        for(var test:GenerationTemplate.cases(seed)) {
            String id=test.path("id").asText();if(id.startsWith("seed-")||id.startsWith("small-"))continue;
            String[] words=test.path("input").asText().strip().split("\\s+");long[] values=new long[words.length-1];
            for(int i=1;i<words.length;i++)values[i-1]=Math.max(-LIMIT,Math.min(LIMIT,Long.parseLong(words[i])));
            result.add(test(id,id.equals("sample")?GenerationTemplate.input(1,-2,3):GenerationTemplate.input(values)));
        }
        for(int n=1;n<=2;n++)for(int mask=0;mask<(n==1?5:25);mask++){
            long[] values=new long[n];int code=mask;for(int i=0;i<n;i++){values[i]=code%5-2;code/=5;}
            result.add(test("small-"+n+"-"+mask,GenerationTemplate.input(values)));
        }
        var random=new Random(seed);for(int i=0;i<5;i++){long[] values=new long[1+random.nextInt(1000)];for(int j=0;j<values.length;j++)values[j]=random.nextInt((int)(2*LIMIT+1))-LIMIT;result.add(test("seed-"+i,GenerationTemplate.input(values)));}
        return result;
    }
    public List<String> invalidInputs(){return List.of("", "0\n", "1001\n", "2\n1\n", "1\n10000001\n", "1\n-10000001\n", "1\n0 1\n", "x\n", "1\n9223372036854775808\n", "1\n1.5\n");}
    public String mutant(boolean invertFilter){String condition=switch(filter){case ALL->"true";case POSITIVE->"x>0";case NEGATIVE->"x<0";case EVEN->"x%2==0";case ODD->"x%2!=0";};
        String value=reduction==Reduction.COUNT?"1":switch(transform){case IDENTITY->"x";case ABS->"Math.abs(x)";case SQUARE->"x*x";};
        return "import java.util.*; public class Main {public static void main(String[] args){Scanner s=new Scanner(System.in);int n=s.nextInt();long answer=0;for(int i=0;i<"+(invertFilter?"n":"n-1")+";i++){long x=s.nextLong();if("+(invertFilter?"!("+condition+")":condition)+")answer+="+value+";}System.out.println(answer);}}";}
}
