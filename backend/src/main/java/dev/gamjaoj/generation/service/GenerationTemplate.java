package dev.gamjaoj.generation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** First reviewed template. Variations change the story, never the trusted I/O or answer rule. */
public final class GenerationTemplate {
  public static final String ID = "sequence-sum-v1";
  public static final String STATEMENT =
      "첫째 줄에 정수 N(1 ≤ N ≤ 1000)이 주어진다. 다음 줄에 N개의 정수가 주어진다. 각 정수는 -1000000000 이상 1000000000 이하이다. 모든"
          + " 정수의 합을 출력한다. 합은 64비트 정수로 계산해야 한다.";

  public static ObjectNode spec() {
    return JudgeJson.JSON
        .createObjectNode()
        .put("templateId", ID)
        .put("statement", STATEMENT)
        .put("runtime", "Java 8 / Main / STDIO")
        .put("sourceKind", "ORIGINAL_TEMPLATE")
        .put(
            "permissionNote",
            "GamjaOJ original reviewed template; do not copy external problem text")
        .put(
            "inputLayoutPolicy",
            "canonical-lines-v1: generator lines are transport records only; server restores the"
                + " exact statement line layout before execution and publication")
        .put(
            "generatorContract",
            "Main reads one long seed; prints exactly four lines. Each line is one valid input: N"
                + " followed by N integers; N <= 10. No expected answers.")
        .put(
            "validatorContract",
            "Main reads one entire problem input; prints VALID only for valid N/count/range;"
                + " INVALID otherwise. No exception for malformed input.");
  }

  public static String input(long... values) {
    return values.length
        + "\n"
        + String.join(" ", Arrays.stream(values).mapToObj(Long::toString).toList())
        + "\n";
  }

  public static ObjectNode test(String id, String input) {
    String[] words = input.trim().split("\\s+");
    try {
      int n = Integer.parseInt(words[0]);
      if (n < 1 || n > 1000 || words.length != n + 1) throw new IllegalArgumentException();
      long sum = 0;
      for (int i = 1; i < words.length; i++) {
        long value = Long.parseLong(words[i]);
        if (value < -1_000_000_000L || value > 1_000_000_000L) throw new IllegalArgumentException();
        sum += value;
      }
      return JudgeJson.JSON
          .createObjectNode()
          .put("id", id)
          .put("input", InputLayout.sequence(input))
          .put("output", sum + "\n");
    } catch (RuntimeException e) {
      throw new AccountException(400, "생성된 입력이 템플릿 범위를 벗어났어요.");
    }
  }

  public static List<JsonNode> cases(long seed) {
    List<JsonNode> cases = new ArrayList<>();
    cases.add(test("sample", input(1, 2, 3)));
    // Complete small domain: all length 1..3 sequences over {-1,0,1}.
    for (int n = 1; n <= 3; n++)
      for (int mask = 0; mask < (int) Math.pow(3, n); mask++) {
        long[] values = new long[n];
        int m = mask;
        for (int i = 0; i < n; i++) {
          values[i] = m % 3 - 1;
          m /= 3;
        }
        cases.add(test("small-" + n + "-" + mask, input(values)));
      }
    long[] maximum = new long[1000];
    Arrays.fill(maximum, 1_000_000_000L);
    cases.add(test("positive-boundary", input(maximum)));
    Arrays.fill(maximum, -1_000_000_000L);
    cases.add(test("negative-boundary", input(maximum)));
    Arrays.fill(maximum, 0);
    cases.add(test("zero-boundary", input(maximum)));
    cases.add(test("singleton-positive", input(1_000_000_000L)));
    cases.add(test("singleton-negative", input(-1_000_000_000L)));
    cases.add(test("cancellation", input(1_000_000_000L, -1_000_000_000L, 7, -7)));
    cases.add(test("mixed-sign", input(-9, 0, 4, -2, 1)));
    Random random = new Random(seed);
    for (int i = 0; i < 5; i++) {
      long[] values = new long[1 + random.nextInt(1000)];
      for (int j = 0; j < values.length; j++)
        values[j] = random.nextInt(2_000_000_001) - 1_000_000_000L;
      cases.add(test("seed-" + i, input(values)));
    }
    return cases;
  }

  public static String mutant(boolean overflow) {
    return "import java.util.*; public class Main {public static void main(String[] args){Scanner"
        + " s=new Scanner(System.in);int n=s.nextInt();"
        + (overflow ? "int" : "long")
        + " v=0;for(int i=0;i<"
        + (overflow ? "n" : "n-1")
        + ";i++) v+=s.nextInt();System.out.println(v);}}";
  }
}
