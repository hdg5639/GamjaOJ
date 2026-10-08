package dev.gamjaoj;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.service.generation.GenerationChoices;
import dev.gamjaoj.service.generation.GenerationType;
import dev.gamjaoj.service.generation.GraphRecipe;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GraphRecipeTest {
    // Independent all-pairs dynamic programming, not the contract evaluator's single-source search.
    long floyd(GraphRecipe recipe,String input) {
        var scanner=new Scanner(input);int n=scanner.nextInt(),m=scanner.nextInt(),s=scanner.nextInt()-1,t=scanner.nextInt()-1;
        long inf=1_000_000_000_000L;long[][] d=new long[n][n];for(int i=0;i<n;i++){Arrays.fill(d[i],inf);d[i][i]=0;}
        for(int i=0;i<m;i++){int a=scanner.nextInt()-1,b=scanner.nextInt()-1;long w=scanner.nextLong();d[a][b]=Math.min(d[a][b],w);if(!recipe.directed)d[b][a]=Math.min(d[b][a],w);}
        for(int k=0;k<n;k++)for(int a=0;a<n;a++)for(int b=0;b<n;b++)d[a][b]=Math.min(d[a][b],d[a][k]+d[k][b]);
        return switch(recipe.query){case DISTANCE->d[s][t]==inf?-1:d[s][t];case COUNT->Arrays.stream(d[s]).filter(v->v<inf).count();case MAX->Arrays.stream(d[s]).filter(v->v<inf).max().orElseThrow();};
    }
    @Test void allTwelveContractsAgreeWithIndependentAllPairsOracleOnExhaustiveAndBoundaryCases() {
        for(boolean directed:List.of(false,true))for(boolean weighted:List.of(false,true))for(var query:GraphRecipe.Query.values()) {
            var recipe=new GraphRecipe(directed,weighted,query);var type=GenerationType.of(recipe.id());
            var cases=recipe.cases(723);
            assertThat(cases).isEqualTo(recipe.cases(723));assertThat(cases).isNotEqualTo(recipe.cases(724));
            for(var test:cases)assertThat(test.path("output").asText()).as(recipe.id()+" / "+test.path("id").asText())
                    .isEqualTo(floyd(recipe,test.path("input").asText())+"\n");
            long small=cases.stream().filter(t->t.path("id").asText().startsWith("small-")).count();
            assertThat(small).isEqualTo(weighted?(directed?10:4):(directed?69:11));
            assertThat(type.executions()).isEqualTo(!weighted&&directed?21:12);
            for(String bad:recipe.invalidInputs())assertThatThrownBy(()->recipe.test("invalid",bad)).isInstanceOf(AccountException.class);
            // The direction mutant has a verified witness, rather than assuming every code change is wrong.
            var reversed=new GraphRecipe(!directed,weighted,query);
            assertThat(type.mutantCases(cases).stream().anyMatch(t->!t.path("output").asText().equals(reversed.test("witness",t.path("input").asText()).path("output").asText()))).isTrue();
        }
    }
    @Test void boundariesPreserveZeroWeightsParallelEdgesInfinityAndLongDistances() {
        var graph=new GraphRecipe(true,true,GraphRecipe.Query.DISTANCE);
        assertThat(graph.test("zero",GraphRecipe.input(3,1,3,new long[]{1,2,0},new long[]{1,2,99},new long[]{2,3,0})).path("output").asText()).isEqualTo("0\n");
        assertThat(graph.test("missing",GraphRecipe.input(2,2,1,new long[]{1,2,3})).path("output").asText()).isEqualTo("-1\n");
        assertThat(graph.test("self",GraphRecipe.input(2,2,2)).path("output").asText()).isEqualTo("0\n");
        assertThat(graph.cases(1).stream().filter(t->t.path("id").asText().equals("long-distance")).findFirst().orElseThrow().path("output").asText()).isEqualTo("59000000000\n");
        assertThat(new GraphRecipe(true,true,GraphRecipe.Query.COUNT).test("count",GraphRecipe.input(60,1,60)).path("output").asText()).isEqualTo("1\n");
        assertThat(new GraphRecipe(true,true,GraphRecipe.Query.MAX).test("max",GraphRecipe.input(60,1,60)).path("output").asText()).isEqualTo("0\n");
        assertThatThrownBy(()->GenerationType.of("graph-recipe-v1-D-NEGATIVE-DISTANCE")).isInstanceOf(AccountException.class);
        assertThatThrownBy(()->GenerationChoices.resolve("graphs",List.of("reachable-count","max-distance"))).isInstanceOf(AccountException.class);
        assertThat(GenerationChoices.resolve("graphs",List.of("directed","weighted","edge-cases")).template()).isEqualTo(graph.id());
    }
}
