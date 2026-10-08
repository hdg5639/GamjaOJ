package dev.gamjaoj;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.generation.service.CallablePrograms;
import dev.gamjaoj.generation.service.HybridArtifacts;
import dev.gamjaoj.generation.service.HybridGeneration;
import dev.gamjaoj.generation.service.HybridModels;
import dev.gamjaoj.generation.service.HybridPackagePlan;
import dev.gamjaoj.generation.service.HybridRuleAuthorStages;
import dev.gamjaoj.generation.service.HybridRulePackage;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.file.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CallableProgramsTest {
  @TempDir Path temp;

  static JsonNode multi() {
    return JudgeJson.parse(
        """
{"mode":"MULTI_API","methods":[
{"name":"init","returns":"void","parameters":[{"name":"n","type":"int"}],"description":"상태 초기화"},
{"name":"add","returns":"void","parameters":[{"name":"value","type":"long"}],"description":"값 추가"},
{"name":"query","returns":"long","parameters":[],"description":"합계 반환"}]}
""");
  }

  String run(String source, String input) throws Exception {
    Files.writeString(temp.resolve("Main.java"), source);
    int exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "--release",
                "8",
                "-d",
                temp.toString(),
                temp.resolve("Main.java").toString());
    assertThat(exit).isZero();
    var p =
        new ProcessBuilder("java", "-cp", temp.toString(), "Main")
            .redirectErrorStream(true)
            .start();
    p.getOutputStream().write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    p.getOutputStream().close();
    String output =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(p.waitFor()).as(output).isZero();
    return output;
  }

  @Test
  void twentyFiveCasesEachWithHundredsOfCallsShareStateAndReset() throws Exception {
    var cases = JudgeJson.JSON.createArrayNode();
    StringBuilder expected = new StringBuilder();
    for (int c = 0; c < 25; c++) {
      var calls = cases.addArray();
      calls.addArray().add("init").add(c);
      long sum = c;
      for (int j = 0; j < 150; j++) {
        calls.addArray().add("add").add(j);
        sum += j;
        calls.addArray().add("query");
        expected.append(sum).append('\n');
      }
      calls.addArray().add("init").add(0);
      calls.addArray().add("query");
      expected.append("0\n");
    }
    String source =
        "public class UserSolution {static long total;public void init(int n){total=n;}public void"
            + " add(long value){total+=value;}public long query(){return total;}}";
    assertThat(run(CallablePrograms.executable(multi(), source), cases.toString()))
        .isEqualTo(expected.toString());
    // Export the exact deterministic driver for real sandbox checks, never production data.
    Path out = Path.of("target/callable-fixtures");
    Files.createDirectories(out);
    Files.writeString(out.resolve("api.json"), CallablePrograms.bundle(multi()).toString());
    Files.writeString(out.resolve("UserSolution.java"), source);
    Files.writeString(out.resolve("input.json"), cases.toString());
    Files.writeString(out.resolve("output.txt"), expected.toString());
  }

  @Test
  void singleFunctionHasTypedArraysAndExactStringValues() throws Exception {
    var api =
        JudgeJson.parse(
            """
{"mode":"SINGLE_FUNCTION","methods":[{"name":"solution","returns":"String[]","parameters":[{"name":"values","type":"int[]"},{"name":"labels","type":"String[]"},{"name":"enabled","type":"boolean"}],"description":"문자열 반환"}]}
""");
    String source =
        "public class UserSolution {public String[] solution(int[] values,String[] labels,boolean"
            + " enabled){return new"
            + " String[]{labels[0],String.valueOf(values[0]),String.valueOf(enabled)};}}";
    assertThat(
            run(
                CallablePrograms.executable(api, source),
                "[[[\"solution\",[7],[\"a b\\n한글\"],true]],[[\"solution\",[-2],[\"\"],false]]]"))
        .isEqualTo("[\"a\\u0020b\\u000a한글\",\"7\",\"true\"]\n[\"\",\"-2\",\"false\"]\n");
    Path out = Path.of("target/callable-fixtures");
    Files.createDirectories(out);
    Files.writeString(out.resolve("single-api.json"), CallablePrograms.bundle(api).toString());
    Files.writeString(out.resolve("SingleSolution.java"), source);
  }

  @Test
  void callableSemanticsReachTheIndependentReaderAndFinalPackage() {
    var f = new HybridGenerationIntegrationTest();
    var presentation = f.presentation();
    ((com.fasterxml.jackson.databind.node.ObjectNode) presentation.path("semantics"))
        .set("callable", multi());
    var snapshot = HybridArtifacts.publicSnapshot(presentation);
    String hash = JudgeJson.hash(JudgeJson.canonical(snapshot));
    var assignment =
        new HybridGeneration.Assignment(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            0,
            HybridGeneration.Role.READER,
            java.util.UUID.randomUUID(),
            hash,
            "contract",
            hash,
            snapshot);
    assertThat(HybridModels.checkedInput(assignment).path("semantics").path("callable"))
        .isEqualTo(multi());
    String generated =
        JudgeJson.JSON
            .valueToTree(java.util.List.of("1 1\n1 1\n", "1 2\n1 1\n", "1 3\n1 1\n", "1 4\n1 1\n"))
            .toString();
    var candidates = HybridPackagePlan.candidates(generated, f.reader(), 123);
    var pack = HybridPackagePlan.pack("callable-package", candidates, presentation);
    assertThat(pack.path("api")).isEqualTo(CallablePrograms.bundle(multi()));
    assertThat(pack.path("statement").asText())
        .contains(
            "public class UserSolution",
            "public void init(int n)",
            "public long query()",
            "구동 코드가 그 반환값을 출력합니다",
            "첫 호출은 init");
    var single =
        JudgeJson.parse(
            "{\"mode\":\"SINGLE_FUNCTION\",\"methods\":[{\"name\":\"solution\",\"returns\":\"int[]\",\"parameters\":[{\"name\":\"values\",\"type\":\"int[]\"}],\"description\":\"결과"
                + " 반환\"}]}");
    assertThat(CallablePrograms.submissionGuide(single))
        .contains("public int[] solution(int[] values)", "정확히 한 번")
        .doesNotContain("첫 호출은 init");
  }

  @Test
  void rejectsArbitraryTypesNamesAndInvalidSingleSignature() {
    var api = (com.fasterxml.jackson.databind.node.ObjectNode) multi().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) api.path("methods").get(0))
        .put("name", "class");
    assertThatThrownBy(() -> CallablePrograms.validate(api)).hasMessage("INVALID_API_IDENTIFIER");
    var single = (com.fasterxml.jackson.databind.node.ObjectNode) multi().deepCopy();
    single.put("mode", "SINGLE_FUNCTION");
    assertThatThrownBy(() -> CallablePrograms.validate(single))
        .hasMessage("INVALID_SINGLE_FUNCTION");
    assertThat(HybridRuleAuthorStages.instructions("CODE"))
        .doesNotContain("CALLABLE JAVA CONTRACT");
    assertThat(HybridRuleAuthorStages.instructions("CODE", "COMMAND_MULTI"))
        .contains("CALLABLE JAVA CONTRACT");
    assertThat(CallablePrograms.schema().path("properties").path("methods").isObject()).isTrue();
  }

  @Test
  void reviewerUnderstandsInternalAssemblyWithoutAuthorOnlyRequirements() {
    var input = JudgeJson.JSON.createObjectNode();
    input.putObject("publicSnapshot").putObject("semantics").set("callable", multi());
    var a =
        new HybridGeneration.Assignment(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            0,
            HybridGeneration.Role.CONTENT_REVIEW,
            java.util.UUID.randomUUID(),
            "input",
            "contract",
            "public",
            input);
    assertThat(HybridModels.apiInstructions(a, input))
        .contains(
            "server-assembled INTERNAL executable",
            "canonical JSON",
            "not the hidden qualification corpus")
        .doesNotContain(
            "Reference, slowSolution and mutants must be UserSolution implementations WITHOUT"
                + " Main");
    assertThat(CallablePrograms.INSTRUCTIONS)
        .contains("WITHOUT Main", "two independent singleton cases are VALID");
    assertThat(CallablePrograms.publicInstructions(false))
        .doesNotContain("server-assembled INTERNAL executable");
  }

  @Test
  void typedReaderCasesSerializeWithoutDroppingIndependentCaseWrappers() {
    var api =
        JudgeJson.parse(
            "{\"mode\":\"SINGLE_FUNCTION\",\"methods\":[{\"name\":\"solution\",\"returns\":\"int[]\",\"parameters\":[{\"name\":\"start\",\"type\":\"int\"},{\"name\":\"deltas\",\"type\":\"int[]\"}],\"description\":\"결과\"}]}");
    var cases =
        JudgeJson.parse(
            "[[{\"method\":\"solution\",\"arguments\":{\"start\":500,\"deltas\":[]}}],[{\"method\":\"solution\",\"arguments\":{\"deltas\":[2,-1000,1],\"start\":999}}]]");
    assertThat(CallablePrograms.serializeReaderInput(api, cases))
        .isEqualTo("[[[\"solution\",500,[]]],[[\"solution\",999,[2,-1000,1]]]]");
    var semantics = JudgeJson.JSON.createObjectNode();
    semantics.set("callable", api);
    var reader = new HybridGenerationIntegrationTest().reader();
    ((com.fasterxml.jackson.databind.node.ObjectNode) reader.path("adversarialInputs").get(0))
        .set("input", cases);
    var normalized = HybridArtifacts.reader(reader, semantics);
    assertThat(normalized.path("adversarialInputs").get(0).path("input").asText())
        .isEqualTo(CallablePrograms.serializeReaderInput(api, cases));
    assertThat(reader.path("adversarialInputs").get(0).path("input").isArray()).isTrue();
    var bad =
        JudgeJson.parse(
            "[[{\"method\":\"solution\",\"arguments\":{\"start\":0,\"deltas\":[]}},"
                + " {\"method\":\"solution\",\"arguments\":{\"start\":1,\"deltas\":[]}}]]");
    assertThatThrownBy(() -> CallablePrograms.serializeReaderInput(api, bad))
        .hasMessage("INVALID_CALLABLE_READER_INPUT");
    assertThatThrownBy(
            () ->
                CallablePrograms.serializeReaderInput(
                    api, JudgeJson.parse("[[{\"method\":\"init\",\"arguments\":{}}]]")))
        .hasMessage("INVALID_CALLABLE_READER_METHOD");
    assertThat(CallablePrograms.readerInputSchema(api).path("items").path("maxItems").asInt())
        .isEqualTo(1);
  }

  @Test
  void emptyOutputIsAllowedOnlyWhenEveryInvokedMethodIsVoid() {
    var pack = HybridAdmissionIntegrationTest.fixturePackage();
    var contract = (com.fasterxml.jackson.databind.node.ObjectNode) pack.path("contract");
    contract.set("callable", multi());
    String input = "[[[\"init\",3],[\"add\",4]],[[\"init\",0]]]";
    assertThat(CallablePrograms.emptyOutputAllowed(contract, input)).isTrue();
    for (String bad :
        java.util.List.of("[[[\"init\",0],[\"query\"]]]", "[[[\"missing\"]]]", "[]", "invalid"))
      assertThat(CallablePrograms.emptyOutputAllowed(contract, bad)).isFalse();
    var first = (com.fasterxml.jackson.databind.node.ObjectNode) pack.path("tiny").get(0);
    String previous = first.path("input").asText();
    first.put("input", input).put("output", "");
    for (var mutant : pack.path("mutants"))
      if (mutant.path("witness").asText().equals(previous))
        ((com.fasterxml.jackson.databind.node.ObjectNode) mutant).put("witness", input);
    assertThat(HybridRulePackage.parse("void-only", pack).tiny().get(0).output()).isEmpty();
    first.put("input", "[[[\"init\",0],[\"query\"]]]");
    assertThatThrownBy(() -> HybridRulePackage.parse("missing-answer", pack))
        .hasMessage("RULE_PACKAGE_FIELD");
  }

  @Test
  void generatedDriverRejectsUnknownMethodsAndWrongTypes() throws Exception {
    // Parser/type enforcement is exercised with valid compilation and a failing process.
    String source = CallablePrograms.executable(multi(), CallablePrograms.template(multi()));
    assertThat(run(source, "[[[\"init\",0],[\"query\"]]]")).isEqualTo("0\n");
    for (String input :
        new String[] {
          "[[[\"query\"]]]",
          "[[[\"init\",2147483648]]]",
          "[[[\"init\",1.5]]]",
          "[[[\"init\",0],[\"unknown\"]]]"
        }) {
      var p =
          new ProcessBuilder("java", "-cp", temp.toString(), "Main")
              .redirectErrorStream(true)
              .start();
      p.getOutputStream().write(input.getBytes());
      p.getOutputStream().close();
      p.getInputStream().readAllBytes();
      assertThat(p.waitFor()).isNotZero();
    }
  }
}
