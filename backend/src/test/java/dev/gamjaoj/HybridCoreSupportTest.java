package dev.gamjaoj;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.service.generation.HybridCoreSupport;
import dev.gamjaoj.service.generation.HybridFiniteProfile;
import dev.gamjaoj.service.generation.HybridPackagePlan;
import dev.gamjaoj.support.JudgeJson;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HybridCoreSupportTest {
    @TempDir Path temp;
    Path compile(String role) throws Exception {
        Path dir=Files.createDirectory(temp.resolve(role));
        Path file=dir.resolve("Main.java");Files.writeString(file,HybridCoreSupport.bundle().path(role).asText());
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8",file.toString())).isZero();
        return dir;
    }
    String run(Path dir,String input) throws Exception {
        Path out=dir.resolve("output.txt");
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",dir.toString(),"Main")
                .redirectErrorStream(true).redirectOutput(out.toFile()).start();
        try {
            try(var in=p.getOutputStream()){in.write(input.getBytes(StandardCharsets.UTF_8));}
            assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
            return Files.readString(out);
        }finally{p.destroyForcibly();}
    }
    @Test void compiledSupportProducesDistinctValidDeterministicCasesAndRejectsMalformedInput() throws Exception {
        Path gen=compile("generator"),validator=compile("inputValidator");
        for(long seed:new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
            String first=run(gen,seed+"\n");assertThat(run(gen,seed+"\n")).isEqualTo(first);
            var cases=JudgeJson.parse(first);assertThat(cases.size()).isEqualTo(4);
            var unique=new HashSet<String>();
            for(var c:cases) {unique.add(c.asText());HybridPackagePlan.parse(c.asText());assertThat(run(validator,c.asText())).isEqualTo("VALID\n");}
            assertThat(unique).hasSize(4);
        }
        for(var c:HybridFiniteProfile.valid())assertThat(run(validator,c.input())).isEqualTo("VALID\n");
        for(var c:HybridFiniteProfile.stress())assertThat(run(validator,c.input())).isEqualTo("VALID\n");
        for(String input:List.of("100 1000\n"+"1 1\n".repeat(98),"1 1\n1 1\n9\n","1 1\n0 1\n","1 1\n1 10001\n","1 1\n1.0 1\n","1 1\n999999999999999 1\n","0 1\n"))
            assertThat(run(validator,input)).isEqualTo("INVALID\n");
    }
    @Test void assemblyUsesPinnedSourcesRejectsOverridesAndRejectsChangedContract() {
        var f=new HybridGenerationIntegrationTest();
        var input=JudgeJson.JSON.createObjectNode();input.set("contract",HybridFiniteProfile.contract());input.set("serverSupport",HybridCoreSupport.bundle());
        var reduced=f.core();reduced.remove(List.of("generator","inputValidator"));
        assertThat(HybridCoreSupport.assemble(input,reduced).path("inputValidator")).isEqualTo(input.path("serverSupport").path("inputValidator"));
        assertThatThrownBy(()->HybridCoreSupport.assemble(input,f.core())).isInstanceOf(ArtifactValidation.Invalid.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("contract")).put("termination","changed");
        assertThatThrownBy(()->HybridCoreSupport.assemble(input,reduced)).isInstanceOf(ArtifactValidation.Invalid.class);
        assertThat(HybridCoreSupport.assemble(JudgeJson.JSON.createObjectNode().set("contract",f.contract()),f.core())).isEqualTo(f.core());
    }
}
