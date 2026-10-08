package dev.gamjaoj;
import dev.gamjaoj.service.generation.HybridFiniteProfile;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HybridFiniteProfileTest {
    @Test void finiteSlicesAreCompleteDistinctAndInsideStatedBounds(){
        var cases=HybridFiniteProfile.valid();assertThat(cases).hasSize(16);
        assertThat(cases.stream().map(HybridFiniteProfile.Case::input).distinct().count()).isEqualTo(16);
        Set<String> expected=new HashSet<>();
        for(int w:List.of(1,2))for(int c:List.of(1,2))for(int v:List.of(1,2))expected.add("1 "+w+"\n"+c+" "+v+"\n");
        for(int w:List.of(1,2))for(int a:List.of(1,2))for(int b:List.of(1,2))expected.add("2 "+w+"\n"+a+" 1\n"+b+" 1\n");
        assertThat(cases.stream().map(HybridFiniteProfile.Case::input).toList()).containsExactlyInAnyOrderElementsOf(expected);
        // Independent DP cross-check of the server subset enumeration, including empty choice.
        for(var c:cases){
            var scanner=new Scanner(c.input());int n=scanner.nextInt(),w=scanner.nextInt();int[] dp=new int[w+1];
            for(int i=0;i<n;i++){int cost=scanner.nextInt(),value=scanner.nextInt();for(int j=w;j>=cost;j--)dp[j]=Math.max(dp[j],dp[j-cost]+value);}
            assertThat(c.output()).isEqualTo(dp[w]+"\n");
        }
    }
    @Test void oracleDeclarationMustMatchSupportedBounds(){
        var f=new HybridGenerationIntegrationTest();var reader=f.reader();
        ((com.fasterxml.jackson.databind.node.ObjectNode)reader.path("oracleDomain")).put("inputDomain","N = 1 only");
        assertThatThrownBy(()->HybridFiniteProfile.requireSupported(f.contract(),reader)).isInstanceOf(IllegalArgumentException.class).hasMessage("UNSUPPORTED_ORACLE_DOMAIN");
    }
    @Test void maximumInputsReachBoundsAndHaveIndependentlyCheckedAnswers(){
        assertThat(HybridFiniteProfile.stress()).hasSize(2);
        for(var c:HybridFiniteProfile.stress()) {
            var scanner=new Scanner(c.input());int n=scanner.nextInt(),w=scanner.nextInt();assertThat(n).isEqualTo(100);assertThat(w).isEqualTo(1000);
            int[] dp=new int[w+1];
            for(int i=0;i<n;i++){int cost=scanner.nextInt(),value=scanner.nextInt();assertThat(value).isEqualTo(10000);
                for(int j=w;j>=cost;j--)dp[j]=Math.max(dp[j],dp[j-cost]+value);}
            assertThat(scanner.hasNext()).isFalse();assertThat(c.output()).isEqualTo(dp[w]+"\n");
        }
        assertThat(HybridFiniteProfile.hash(HybridFiniteProfile.EXTENDED_POLICY)).isNotEqualTo(HybridFiniteProfile.HASH);
        for(String role:List.of("mutant-unbounded","mutant-strict-fit")) {
            var witness=HybridFiniteProfile.tests(role).get(0);
            assertThat(HybridFiniteProfile.tests("domain-reference")).contains(witness);
            assertThat(HybridFiniteProfile.tests("domain-oracle")).contains(witness);
        }
    }

}
