package dev.gamjaoj;
import dev.gamjaoj.service.generation.CallablePrograms;
import dev.gamjaoj.support.JudgeJson;
import dev.gamjaoj.service.generation.NativeCallablePrograms;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class NativeCallableProgramsTest {
 @Test void exportPrivateAuditAdaptersWhenOperatorInventoryIsProvided() throws Exception {
  String inventory=System.getenv("GAMJAOJ_RESOURCE_INVENTORY");if(inventory==null)return;
  Path path=Path.of(inventory),out=path.getParent().resolve("drivers");Files.createDirectories(out);
  try(var lines=Files.lines(path)) {
   for(String line:lines.toList()) {
    var row=JudgeJson.parse(line);if(!row.path("package").has("api"))continue;
    var bundle=CallablePrograms.publicBundle(row.path("package").path("api"));
    for(String language:java.util.List.of("JAVA","CPP","PYTHON"))
     ((com.fasterxml.jackson.databind.node.ObjectNode)bundle.path("languages")).set(language,NativeCallablePrograms.bundleForProblem(row.path("package"),language,false));
    Files.writeString(out.resolve(row.path("version").asText()+".json"),bundle.toString());
   }
  }
 }
 @Test void onlyTrustedGeneratedFormalPlansSelectLargerTypedInputReaders() throws Exception {
  var problem=JudgeJson.JSON.createObjectNode();var api=CallableProgramsTest.multi();problem.set("api",CallablePrograms.bundle(api));
  Path out=Path.of("target/native-callable-large-fixtures");Files.createDirectories(out);
  for(String language:java.util.List.of("JAVA","CPP","PYTHON")) {
   var old=NativeCallablePrograms.bundle(api,language);
   assertThat(NativeCallablePrograms.bundleForProblem(problem,language,false)).isEqualTo(old);
   problem.putObject("generated");
   assertThat(NativeCallablePrograms.bundleForProblem(problem,language,false).path("driver").asText()).contains("8388608");
   problem.withObject("generated").put("inputLimit",16777216);
   var large=NativeCallablePrograms.bundleForProblem(problem,language,false);
   assertThat(large.path("driver").asText()).contains("16777216").doesNotContain("6291456");
   assertThat(large.path("template")).isEqualTo(old.path("template"));
   assertThat(NativeCallablePrograms.bundleForProblem(problem,language,true)).isEqualTo(old);
   Files.writeString(out.resolve(language+".json"),large.toString());
   problem.remove("generated");
  }
  for(String invalid:java.util.List.of("null","true","\"16777216\"","16777216.0","6291456","8388607","134217729","9223372036854775807")) {
   problem.set("generated",JudgeJson.parse("{\"inputLimit\":"+invalid+"}"));
   assertThatThrownBy(()->NativeCallablePrograms.bundleForProblem(problem,"JAVA",false)).isInstanceOf(IllegalArgumentException.class);
   assertThat(NativeCallablePrograms.bundleForProblem(problem,"JAVA",true)).isEqualTo(CallablePrograms.bundle(api));
  }
  for(int limit:new int[]{8388608,16777216,134217728})for(String language:java.util.List.of("JAVA","CPP","PYTHON"))
   assertThat(NativeCallablePrograms.bundle(api,language,limit).path("driver").asText()).contains(String.valueOf(limit));
 }
 @Test void allTypedAdaptersAreRebuiltForOldPackages() throws Exception {
  var api=JudgeJson.parse("""
  {"mode":"MULTI_API","methods":[
   {"name":"init","returns":"void","parameters":[],"description":"초기화"},
   {"name":"number","returns":"long","parameters":[{"name":"value","type":"long"}],"description":"반환"},
   {"name":"text","returns":"String","parameters":[{"name":"value","type":"String"}],"description":"반환"},
   {"name":"ints","returns":"int[]","parameters":[{"name":"value","type":"int[]"}],"description":"반환"},
   {"name":"longs","returns":"long[]","parameters":[{"name":"value","type":"long[]"}],"description":"반환"},
   {"name":"flags","returns":"boolean[]","parameters":[{"name":"value","type":"boolean[]"}],"description":"반환"},
   {"name":"texts","returns":"String[]","parameters":[{"name":"value","type":"String[]"}],"description":"반환"},
   {"name":"flag","returns":"boolean","parameters":[{"name":"value","type":"boolean"}],"description":"반환"}]}
  """);
  var publicBundle=CallablePrograms.publicBundle(CallablePrograms.bundle(api));
  assertThat(publicBundle.path("languages").size()).isEqualTo(3);
  Path out=Path.of("target/native-callable-fixtures");Files.createDirectories(out);
  for(String language:java.util.List.of("JAVA","CPP","PYTHON")) {
   var bundle=NativeCallablePrograms.bundle(api,language);
   assertThat(bundle.path("format").asText()).isEqualTo(language+"_CALLABLE_V1");
   assertThat(bundle.path("driver").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(65536);
   Files.writeString(out.resolve(language+".json"),bundle.toString());
  }
  var single=JudgeJson.parse("{\"mode\":\"SINGLE_FUNCTION\",\"methods\":[{\"name\":\"solution\",\"returns\":\"int\",\"parameters\":[],\"description\":\"호출\"}]}");
  for(String language:java.util.List.of("JAVA","CPP","PYTHON"))Files.writeString(out.resolve("single-"+language+".json"),NativeCallablePrograms.bundle(single,language).toString());
 }
}
