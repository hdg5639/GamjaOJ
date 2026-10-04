package dev.gamjaoj;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class NativeCallableProgramsTest {
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
