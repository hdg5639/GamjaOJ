package dev.gamjaoj;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HybridPackagePlanTest {
    @Test void trustedParserChecksCompleteGrammarAndBounds(){
        for(String input:List.of("","0 1\n","101 1\n","1 0\n1 1\n","1 1001\n1 1\n","1 1\n0 1\n","1 1\n1 10001\n","1 1\n1 1\n99","1 1\n1","NaN 1","1 1\n999999999999999 1"))
            assertThatThrownBy(()->HybridPackagePlan.parse(input)).isInstanceOf(IllegalArgumentException.class).hasMessage("PROFILE_INPUT_BOUND");
        assertThat(HybridPackagePlan.parse("1\t2\n1 3\n").answer()).isEqualTo("3\n");
        assertThat(HybridPackagePlan.parse("5 8\n"+"1 1\n".repeat(5)).tiny()).isFalse();
    }
    @Test void seededRandomCasesAreStableBoundedAndAnswersMatchSubsetEnumeration(){
        for(long seed:List.of(1L,-1L,0L,Long.MIN_VALUE,Long.MAX_VALUE)) {
            var inputs=HybridPackagePlan.random(seed);assertThat(inputs).hasSize(4).doesNotHaveDuplicates();
            assertThat(HybridPackagePlan.random(seed)).isEqualTo(inputs);
            for(String input:inputs) {
                var p=HybridPackagePlan.parse(input);assertThat(p.tiny()).isTrue();int best=0;
                for(int mask=0;mask<(1<<p.costs().length);mask++) {
                    int cost=0,value=0;for(int i=0;i<p.costs().length;i++)if((mask&(1<<i))!=0){cost+=p.costs()[i];value+=p.values()[i];}
                    if(cost<=p.capacity())best=Math.max(best,value);
                }
                assertThat(p.answer()).isEqualTo(best+"\n");
            }
        }
    }
    @Test void samplesAreDerivedAndLargeInputsNeverReachOracle(){
        var f=new HybridGenerationIntegrationTest();String generated=JudgeJson.JSON.valueToTree(List.of("5 8\n"+"1 1\n".repeat(5),"1 4\n2 3\n","1 3\n2 3\n","1 1\n2 3\n")).toString();
        var candidates=HybridPackagePlan.candidates(generated,f.reader(),123);
        var oracle=HybridPackagePlan.tests(candidates,"batch-oracle");assertThat(oracle).allMatch(c->HybridPackagePlan.parse(c.path("input").asText()).tiny());
        assertThat(candidates.size()).isGreaterThan(oracle.size()).isLessThanOrEqualTo(20);
        var pack=HybridPackagePlan.pack("version",candidates,f.presentation());
        assertThat(pack.path("samples").size()).isEqualTo(2);
        for(var sample:pack.path("samples"))assertThat(sample.path("output").asText()).isEqualTo(HybridPackagePlan.parse(sample.path("input").asText()).answer());
        assertThat(pack.path("statement").asText()).contains("total chosen value","at most once per item","0..1000000");
    }
    @Test void duplicateGeneratorAndOversizedFinalPackagesAreHeldRatherThanSilentlyTruncated(){
        var f=new HybridGenerationIntegrationTest();
        assertThatThrownBy(()->HybridPackagePlan.candidates("[\"1 1\\n1 1\\n\",\"1 1\\n1 1\\n\",\"1 1\\n1 1\\n\",\"1 1\\n1 1\\n\"]",f.reader(),1)).hasMessage("INVALID_GENERATOR_DIVERSITY");
        var reader=f.reader();var inputs=reader.putArray("adversarialInputs");
        for(int i=10;i<26;i++)inputs.addObject().put("input","1 8\n1 "+i+"\n").put("reason","boundary");
        String generated=JudgeJson.JSON.valueToTree(List.of("1 1\n1 1\n","1 2\n1 1\n","1 3\n1 1\n","1 4\n1 1\n")).toString();
        assertThatThrownBy(()->HybridPackagePlan.candidates(generated,reader,1)).hasMessage("FINAL_PACKAGE_TEST_CAP");
    }
}
