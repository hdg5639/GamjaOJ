package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DiagnosticExamDriverTest {
 @Test void exportTrustedBundlesWhenPrivateAuthoringPackageIsProvided() throws Exception {
  String source=System.getenv("GAMJAOJ_EXAM_PACKAGE");if(source==null)return;
  Path root=Path.of(source),output=root.resolve("integration/callable");Files.createDirectories(output);
  int count=0;
  try(var folders=Files.list(root.resolve("problems"))) {
   for(Path folder:folders.sorted().toList())if(folder.getFileName().toString().startsWith("B")) {
    var api=JudgeJson.parse(Files.readString(folder.resolve("api-contract.json")));var bundle=CallablePrograms.bundle(api);
    assertThat(bundle.path("sourceFile").asText()).isEqualTo("UserSolution.java");
    assertThat(bundle.path("driver").asText()).contains("UserSolution user=new UserSolution()");
    Files.writeString(output.resolve(folder.getFileName()+".json"),JudgeJson.canonical(bundle));count++;
   }
  }
  assertThat(count).isEqualTo(32);
 }
}
