package dev.gamjaoj;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuTimeProfilesTest {
 @Test void cpuBudgetIsFrozenSeparatelyFromWallGuardAndMemory() {
  var limits="{\"JAVA\":5,\"CPP\":3,\"PYTHON\":8,\"cpu\":{\"JAVA\":2.65},\"analysis\":\"sample calibration\"}";
  var p=LanguageProfiles.profile("JAVA",limits);
  assertEquals(5,p.path("testWallSeconds").asInt());assertEquals(2.65,p.path("testCpuSeconds").asDouble());
  assertEquals(2650,LanguageProfiles.option(p).timeLimitMs());assertEquals("CPU",LanguageProfiles.option(p).timeMetric());
  var cpp=LanguageProfiles.profile("CPP",limits);assertFalse(cpp.has("testCpuSeconds"));assertEquals("WALL",LanguageProfiles.option(cpp).timeMetric());
 }
 @Test void invalidCpuBudgetsAndUnexpectedKeysAreRejected() {
  for(String cpu:new String[]{"{\"JAVA\":0}","{\"JAVA\":181}","{\"JAVA\":0.1234}","{\"RUBY\":1}","{}"})
   assertThrows(HybridArtifacts.Invalid.class,()->LanguageProfiles.profile("JAVA","{\"JAVA\":5,\"CPP\":3,\"PYTHON\":8,\"cpu\":"+cpu+",\"analysis\":\"test\"}"));
 }
}
