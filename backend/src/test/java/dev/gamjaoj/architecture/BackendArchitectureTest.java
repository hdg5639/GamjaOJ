package dev.gamjaoj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Checks production source contracts, including fully qualified references and SQL literals. */
class BackendArchitectureTest {
  private static final Path SOURCE = Path.of("src/main/java/dev/gamjaoj");

  @Test
  void productionSourcesFollowLayerBoundaries() throws Exception {
    var violations = new ArrayList<String>();
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("Architecture checks require the project JDK").isNotNull();
    try (var files = Files.walk(SOURCE);
        var manager = compiler.getStandardFileManager(null, null, null)) {
      var paths = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  manager,
                  null,
                  List.of("-proc:none"),
                  null,
                  manager.getJavaFileObjectsFromPaths(paths));
      for (var unit : task.parse()) inspect(unit, violations);
    }
    assertThat(violations).as("Backend architecture violations").isEmpty();
  }

  @Test
  void jdbcIsOwnedByRepositoriesAndControllersAreTransportOnly() throws Exception {
    try (var files = Files.walk(SOURCE)) {
      for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        if (!file.toString().contains("/repository/")) {
          assertThat(source)
              .as(file + ": JDBC belongs to repositories")
              .doesNotContain("JdbcClient", "JdbcTemplate");
        }
        if (file.toString().contains("/api/")) {
          assertThat(source)
              .as(file + ": controllers do not own database transactions")
              .doesNotContain("@Transactional", "TransactionTemplate", ".repository.");
        }
      }
    }
  }

  private static void inspect(CompilationUnitTree unit, List<String> violations) {
    String pkg = unit.getPackageName().toString();
    String file = Path.of(unit.getSourceFile().toUri()).getFileName().toString();
    List<? extends com.sun.source.tree.Tree> types = unit.getTypeDecls();
    if (!pkg.equals("dev.gamjaoj")) {
      if (!Set.of(
              "account",
              "judge",
              "problem",
              "diagnostic",
              "learning",
              "generation",
              "ai",
              "export",
              "editor",
              "shared")
          .contains(feature(pkg))) {
        violations.add(file + ": unknown feature package " + pkg);
      }
      if (!file.equals("package-info.java")
          && !Set.of(
                  "api",
                  "dto",
                  "service",
                  "repository",
                  "domain",
                  "infrastructure",
                  "config",
                  "exception",
                  "support")
              .contains(layer(pkg))) {
        violations.add(file + ": feature classes require a layer package " + pkg);
      }
    }

    if (!file.equals("package-info.java")) {
      if (types.size() != 1
          || !(types.getFirst() instanceof ClassTree type)
          || !file.equals(type.getSimpleName() + ".java")) {
        violations.add(file + ": one top-level type per matching file is required");
      }
      if (pkg.equals("dev.gamjaoj") && !file.equals("GamjaApplication.java")) {
        violations.add(file + ": the root package is reserved for the application entry point");
      }
    }
    for (var imported : unit.getImports()) {
      dependency(pkg, file, imported.getQualifiedIdentifier().toString(), violations);
    }
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        dependency(pkg, file, node.toString(), violations);
        return super.visitMemberSelect(node, unused);
      }

      @Override
      public Void visitLiteral(LiteralTree node, Void unused) {
        if (!layer(pkg).equals("repository")
            && node.getValue() instanceof String value
            && value
                .stripLeading()
                .matches("(?is)^(SELECT\\s|INSERT\\s+INTO\\s|UPDATE\\s|DELETE\\s+FROM\\s).*")) {
          violations.add(file + ": SQL statements belong to repositories");
        }
        return super.visitLiteral(node, unused);
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        if (!layer(pkg).equals("repository")
            && node.getMethodSelect() instanceof MemberSelectTree select
            && select.getIdentifier().contentEquals("sql")) {
          violations.add(file + ": SQL execution belongs to repositories");
        }
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitIdentifier(IdentifierTree node, Void unused) {
        String name = node.getName().toString();
        if (Set.of("RestController", "RestControllerAdvice").contains(name)
            && !layer(pkg).equals("api")) {
          violations.add(file + ": HTTP endpoints belong to controllers");
        }
        if (name.equals("Repository") && !layer(pkg).equals("repository")) {
          violations.add(file + ": persistence beans belong to repositories");
        }
        if (name.equals("Service") && !layer(pkg).equals("service")) {
          violations.add(file + ": application services belong to the service layer");
        }
        if (name.equals("Configuration") && !layer(pkg).equals("config")) {
          violations.add(file + ": Spring configuration belongs to config");
        }
        return super.visitIdentifier(node, unused);
      }
    }.scan(types, null);
  }

  private static void dependency(
      String pkg, String file, String reference, List<String> violations) {
    String sourceLayer = layer(pkg);
    String targetLayer = layer(reference);
    boolean invalid =
        switch (sourceLayer) {
          case "service" ->
              targetLayer.equals("api")
                  || reference.startsWith("org.springframework.web.bind.annotation");
          case "repository" -> Set.of("service", "api", "infrastructure").contains(targetLayer);
          case "domain", "dto" ->
              Set.of("service", "api", "repository", "infrastructure").contains(targetLayer)
                  || reference.startsWith("org.springframework.web");
          default -> false;
        };
    if (reference.startsWith("dev.gamjaoj.")) {
      if (targetLayer.equals("repository")
          && !feature(reference).equals("shared")
          && !feature(pkg).equals(feature(reference))) {
        violations.add(
            file + ": a feature may not access another feature's repository: " + reference);
      }
      if (feature(pkg).equals("shared") && !feature(reference).equals("shared")) {
        violations.add(file + ": shared must not depend on a feature: " + reference);
      }
    }
    if (invalid) violations.add(file + ": forbidden dependency " + reference);
  }

  private static String feature(String pkg) {
    if (!pkg.startsWith("dev.gamjaoj.")) return "";
    return pkg.substring("dev.gamjaoj.".length()).split("\\.")[0];
  }

  private static String layer(String pkg) {
    if (!pkg.startsWith("dev.gamjaoj.")) return "";
    String[] parts = pkg.substring("dev.gamjaoj.".length()).split("\\.");
    return parts.length < 2 ? "" : parts[1];
  }
}
