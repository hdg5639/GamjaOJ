package dev.gamjaoj;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.generation.GenerationType;
import dev.gamjaoj.service.generation.ParenthesesTemplate;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import static org.assertj.core.api.Assertions.*;

class GenerationTypeTest {
    @Test void trustedParenthesesAnswersAgreeWithIndependentStackForAllShortStrings() {
        for(int n=1;n<=8;n++)for(int mask=0;mask<(1<<n);mask++) {
            var input=new StringBuilder();var stack=new ArrayDeque<Integer>();boolean valid=true;
            for(int i=0;i<n;i++) {
                boolean open=(mask&(1<<i))==0;input.append(open?'(':')');
                if(open)stack.push(i);else if(stack.isEmpty())valid=false;else stack.pop();
            }
            assertThat(ParenthesesTemplate.test("test",input+"\n").path("output").asText()).isEqualTo(valid&&stack.isEmpty()?"YES\n":"NO\n");
        }
    }
    @Test void contractsRejectInvalidInputAndKeepBoundariesAndDeterministicCases() {
        var type=GenerationType.PARENTHESES;
        for(String input:type.invalidInputs())assertThatThrownBy(()->type.test("bad",input)).isInstanceOf(AccountException.class);
        assertThat(type.test("space"," \t(())() \r\n").path("output").asText()).isEqualTo("YES\n");
        assertThat(type.test("space-bad","\n\t)( \n").path("output").asText()).isEqualTo("NO\n");
        assertThat(type.test("single","(\n").path("output").asText()).isEqualTo("NO\n");
        assertThat(type.test("max","()".repeat(500)+"\n").path("output").asText()).isEqualTo("YES\n");
        assertThat(type.cases(42)).isEqualTo(type.cases(42));
        assertThat(type.cases(42)).isNotEqualTo(type.cases(43));
        assertThat(type.cases(42).stream().filter(t->t.path("id").asText().startsWith("small-")).count()).isEqualTo(30);
        assertThat(type.executions()).isEqualTo(15);
        assertThat(GenerationType.SUM.executions()).isEqualTo(15);
        assertThatThrownBy(()->GenerationType.of("free-form")).isInstanceOf(AccountException.class);
    }
}
