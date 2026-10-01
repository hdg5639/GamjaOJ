package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProblemCategoriesTest {
    @Test void knownAutomaticCategoriesAndMixedAcronymsHaveKoreanLabels(){
        assertThat(ProblemCategories.display("graph")).isEqualTo("그래프");
        assertThat(ProblemCategories.display(" BFS ")).isEqualTo("너비 우선 탐색");
        assertThat(ProblemCategories.display("basic-data-structures")).isEqualTo("기초 자료구조");
        assertThat(ProblemCategories.display("dynamic-tree")).isEqualTo("동적 트리");
        assertThat(ProblemCategories.display("Dynamic Programming")).isEqualTo("동적 계획법");
        assertThat(ProblemCategories.display("그래프 BFS")).isEqualTo("그래프 너비 우선 탐색");
        assertThat(ProblemCategories.display("사용자가 만든 한글 분야")).isEqualTo("사용자가 만든 한글 분야");
    }
    @Test void unknownEnglishCannotLeakIntoThePublicTaxonomyOrBeManuallySaved(){
        assertThat(ProblemCategories.display("new-model-category")).isEqualTo("기타");
        assertThatThrownBy(()->ProblemCategories.forSave("new-model-category")).isInstanceOf(AccountException.class);
        assertThat(ProblemCategories.forSave("graph")).isEqualTo("그래프");
        assertThat(ProblemCategories.display(null)).isEqualTo("미분류");
    }
}
