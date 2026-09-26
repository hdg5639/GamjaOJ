package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InputLayoutTest {
    @Test void transportedSequencesWorkWithStatementFollowingLineReaders() {
        var types=java.util.List.of(GenerationType.SUM,GenerationType.of("sequence-recipe-v1-ALL-IDENTITY-SUM"));
        for(var type:types)for(String input:java.util.List.of("1 -7\n","3\t1  2\r\n3","3\n1 2 3\n")) {
            var test=type.test("generated",input);var lines=test.path("input").asText().lines().toList();
            assertThat(lines).hasSize(2);int n=Integer.parseInt(lines.get(0));
            var tokens=new java.util.StringTokenizer(lines.get(1));assertThat(tokens.countTokens()).isEqualTo(n);
            long sum=0;while(tokens.hasMoreTokens())sum+=Long.parseLong(tokens.nextToken());
            assertThat(sum).isEqualTo(Long.parseLong(test.path("output").asText().trim()));
            assertThat(type.test("generated",test.path("input").asText())).isEqualTo(test);
        }
    }
    @Test void transportedGraphsHaveOneHeaderAndExactlyOneLinePerEdge() {
        var type=GenerationType.of("graph-recipe-v1-D-UNIT-DISTANCE");
        for(String input:java.util.List.of("3 2 1 3 1 2 1 2 3 1\n","1 0 1 1\n")) {
            var test=type.test("generated",input);var lines=test.path("input").asText().lines().toList();
            assertThat(lines.getFirst().split(" ")).hasSize(4);
            assertThat(lines).hasSize(Integer.parseInt(lines.getFirst().split(" ")[1])+1);
            for(String line:lines.subList(1,lines.size()))assertThat(line.split(" ")).hasSize(3);
            assertThat(type.test("generated",test.path("input").asText())).isEqualTo(test);
        }
        assertThatThrownBy(()->type.test("bad","3 2 1 3 1 2 1")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->GenerationType.SUM.test("bad","2 1")).isInstanceOf(AccountException.class);
    }
}
