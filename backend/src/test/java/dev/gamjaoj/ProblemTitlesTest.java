package dev.gamjaoj;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.domain.ProblemTitles;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ProblemTitlesTest {
 @Test void displayFallbackNeverChangesFrozenPackage(){
  var p=JudgeJson.parse("{\"version\":\"ordinary-v1\",\"title\":\"   \"}");String before=JudgeJson.canonical(p);
  assertThat(ProblemTitles.display(p)).isEqualTo("연습 문제 · ordinary-v1");assertThat(JudgeJson.canonical(p)).isEqualTo(before);
  assertThat(ProblemTitles.display(JudgeJson.parse("{\"title\":\"정상 제목\"}"))).isEqualTo("정상 제목");
  var different=JudgeJson.parse("{\"version\":\"iamywl-v1-1a31c6e982393f08\",\"title\":\"\"}");
  assertThat(ProblemTitles.display(different)).isEqualTo("연습 문제 · iamywl-v1-1a31c6e982393f08");
 }
}
