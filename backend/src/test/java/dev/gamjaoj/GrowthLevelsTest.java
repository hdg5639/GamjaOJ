package dev.gamjaoj;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GrowthLevelsTest {
 @Test void repeatedSubmissionsCannotRaiseTierAndHarderProblemsCountForTheNextGoal(){
  var solved=new ArrayList<GrowthLevels.Solved>();
  for(int i=0;i<4;i++)solved.add(new GrowthLevels.Solved("p"+i,"그래프",6,"CURATED_ESTIMATE"));
  solved.add(solved.getFirst());var before=GrowthLevels.calculate(solved);
  assertThat(before.layer()).isZero();assertThat(before.nextSolved()).isEqualTo(4);assertThat(before.solvedProblems()).isEqualTo(4);
  solved.add(new GrowthLevels.Solved("p4","문자열",4,"CURATED_ESTIMATE"));var after=GrowthLevels.calculate(solved);
  assertThat(after.layer()).isEqualTo(4);assertThat(after.nextSolved()).isEqualTo(4);assertThat(after.categories()).isEqualTo(2);
 }
 @Test void unreviewedAndAuthorAssignedLevelsStayOutsideTierEvidence(){
  var growth=GrowthLevels.calculate(List.of(new GrowthLevels.Solved("a","구현",9,"AUTHOR_ESTIMATE"),new GrowthLevels.Solved("b","구현",null,null)));
  assertThat(growth.eligibleProblems()).isZero();assertThat(growth.excludedProblems()).isEqualTo(2);assertThat(growth.evidence()).containsOnly(0);
 }
 @Test void emptyAndTopLevelStatesHaveExplicitProgress(){
  assertThat(GrowthLevels.calculate(List.of()).nextLayer()).isEqualTo(1);
  var growth=GrowthLevels.calculate(java.util.stream.IntStream.range(0,5).mapToObj(i->new GrowthLevels.Solved("p"+i,"그래프",9,"CURATED_ESTIMATE")).toList());
  assertThat(growth.layer()).isEqualTo(9);assertThat(growth.nextLayer()).isNull();
 }
}
