package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import dev.gamjaoj.diagnostic.service.Diagnostics;
import dev.gamjaoj.shared.support.JudgeJson;
import org.junit.jupiter.api.Test;

class DiagnosticExamplesTest {
  @Test
  void onlyTheFirstTestAndFollowingExTestsArePublic() {
    var tests =
        JudgeJson.parse(
            "[{\"id\":\"T01\",\"input\":\"1\",\"output\":\"1\"},{\"id\":\"EX2\",\"input\":\"2\",\"output\":\"2\"},"
                + "{\"id\":\"EX3\",\"input\":\"3\",\"output\":\"3\"},{\"id\":\"T02\",\"input\":\"h\",\"output\":\"h\"},{\"id\":\"EX9\",\"input\":\"late\",\"output\":\"x\"}]");
    assertThat(Diagnostics.examples(tests))
        .extracting(Diagnostics.Example::input)
        .containsExactly("1", "2", "3");
    assertThat(
            Diagnostics.examples(
                JudgeJson.parse(
                    "[{\"id\":\"T01\",\"input\":\"a\",\"output\":\"b\"},{\"id\":\"T02\",\"input\":\"h\",\"output\":\"h\"}]")))
        .containsExactly(new Diagnostics.Example("a", "b"));
  }
}
