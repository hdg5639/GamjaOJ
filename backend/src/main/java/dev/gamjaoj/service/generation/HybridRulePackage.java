package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.domain.ArtifactValidation;
import dev.gamjaoj.domain.ProblemTimeLimits;
import dev.gamjaoj.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A registered rule as data (engine PACKAGE_V1). Every field was produced during onboarding and
 * qualified in the Runner: tiny answers agree between an independent brute-force oracle and the
 * reference; stress answers are reference outputs within the resource margin; each mutant fails its
 * recorded witness. Nothing here is executed in the application process.
 */
public record HybridRulePackage(
    String versionId,
    JsonNode contract,
    JsonNode rules,
    JsonNode catalog,
    String generator,
    String validator,
    List<HybridFiniteProfile.Case> tiny,
    List<HybridFiniteProfile.Case> invalid,
    List<HybridFiniteProfile.Case> stress,
    Map<String, String> mutantSources,
    Map<String, HybridFiniteProfile.Case> witnesses,
    String oracleDomain,
    String enumeration,
    String authorGuidance,
    String teachingGuidance,
    String readerGuidance,
    String largeGenerator,
    List<String> largeSeeds,
    int qualifiedJavaSeconds,
    long referenceMaxWallMs) {
  public static final String ENGINE = "PACKAGE_V1";
  public static final int MIN_TINY = 6, MAX_TINY = 24, MAX_STRESS = 3, MAX_INVALID = 10;

  public String policy() {
    return "pkg-" + versionId;
  }

  public String supportVersion() {
    return "pkg-support:" + versionId;
  }

  public String rulesVersion() {
    return "pkg-rules:" + versionId;
  }

  public List<String> mutants() {
    return List.copyOf(new TreeSet<>(mutantSources.keySet()));
  }

  private static String text(JsonNode n, int max) {
    if (n == null
        || !n.isTextual()
        || n.asText().isBlank()
        || n.asText().getBytes(StandardCharsets.UTF_8).length > max)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_FIELD");
    return n.asText();
  }

  private static String raw(JsonNode n, int max) {
    if (n == null || !n.isTextual() || n.asText().getBytes(StandardCharsets.UTF_8).length > max)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_FIELD");
    return n.asText();
  }

  private static List<HybridFiniteProfile.Case> cases(
      JsonNode n, String prefix, int min, int max, int bytes, boolean answers, JsonNode contract) {
    if (n == null || !n.isArray() || n.size() < min || n.size() > max)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_CASES");
    var out = new ArrayList<HybridFiniteProfile.Case>();
    var seen = new HashSet<String>();
    for (var c : n) {
      // Invalid inputs may legitimately be empty or whitespace (for example an empty string).
      String input = answers ? text(c.path("input"), bytes) : raw(c.path("input"), bytes);
      if (!seen.add(input)) throw new ArtifactValidation.Invalid("RULE_PACKAGE_DUPLICATE_INPUT");
      out.add(
          new HybridFiniteProfile.Case(
              prefix + out.size(),
              input,
              answers
                  ? (CallablePrograms.emptyOutputAllowed(contract, input)
                      ? raw(c.path("output"), 4096)
                      : text(c.path("output"), 4096))
                  : "INVALID\n"));
    }
    return List.copyOf(out);
  }

  /**
   * Parses a stored package; any structural deviation rejects the version rather than repairing it.
   */
  public static HybridRulePackage parse(String versionId, JsonNode p) {
    var names = new HashSet<String>();
    p.fieldNames().forEachRemaining(names::add);
    names.remove("large");
    names.remove("timing");
    HybridArtifacts.require(
        names.equals(
            Set.of(
                "contract",
                "rules",
                "catalog",
                "generator",
                "validator",
                "tiny",
                "invalid",
                "stress",
                "mutants",
                "oracleDomain",
                "enumeration",
                "guidance")),
        "RULE_PACKAGE_FIELDS");
    // Optional generated large tests: a qualified generator and seeds; answers come from the
    // reference in the Runner.
    String largeGenerator = null;
    var seeds = new ArrayList<String>();
    if (p.has("large")) {
      HybridArtifacts.fields(p.path("large"), "generator", "seeds");
      largeGenerator = text(p.path("large").path("generator"), 65536);
      var s = p.path("large").path("seeds");
      if (!s.isArray() || s.size() < 1 || s.size() > 3)
        throw new ArtifactValidation.Invalid("RULE_PACKAGE_LARGE");
      for (var seed : s) {
        if (!seed.isTextual()
            || !seed.asText().matches("-?[0-9]{1,18}")
            || seeds.contains(seed.asText()))
          throw new ArtifactValidation.Invalid("RULE_PACKAGE_LARGE");
        seeds.add(seed.asText());
      }
    }
    int qualifiedSeconds = 0;
    long referenceMs = 0;
    if (p.has("timing")) {
      var timing = p.path("timing");
      if (timing.has("calibrationPolicy")) {
        HybridArtifacts.fields(timing, "javaSeconds", "referenceMaxWallMs", "calibrationPolicy");
        HybridArtifacts.require(
            timing.path("calibrationPolicy").asText().equals("REPLAY_HEADROOM_V1"),
            "RULE_TIMING_EVIDENCE");
      } else HybridArtifacts.fields(timing, "javaSeconds", "referenceMaxWallMs");
      HybridArtifacts.require(
          timing.path("javaSeconds").isInt()
              && timing.path("referenceMaxWallMs").isIntegralNumber()
              && timing.path("referenceMaxWallMs").canConvertToLong(),
          "RULE_TIMING_EVIDENCE");
      qualifiedSeconds = timing.path("javaSeconds").asInt();
      referenceMs = timing.path("referenceMaxWallMs").asLong();
      HybridArtifacts.require(
          referenceMs > 0
              && referenceMs <= 10000
              && qualifiedSeconds
                  == (timing.has("calibrationPolicy")
                      ? ProblemTimeLimits.calibratedJavaSeconds(referenceMs)
                      : ProblemTimeLimits.legacyCalibratedJavaSeconds(referenceMs)),
          "RULE_TIMING_EVIDENCE");
    }
    var contract = HybridArtifacts.contract(p.path("contract"));
    // Exactly one Korean normative explanation per contract action, as the public snapshot
    // requires.
    var rules = p.path("rules");
    var actions = new HashSet<String>();
    contract.path("actions").forEach(a -> actions.add(a.path("id").asText()));
    if (!rules.isArray() || rules.size() != actions.size())
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_RULES");
    for (var r : rules) {
      HybridArtifacts.fields(r, "id", "text");
      text(r.path("text"), 4000);
      if (!actions.remove(r.path("id").asText()))
        throw new ArtifactValidation.Invalid("RULE_PACKAGE_RULES");
    }
    var tiny = cases(p.path("tiny"), "tiny-", MIN_TINY, MAX_TINY, 1024, true, contract);
    var stress = cases(p.path("stress"), "stress-", 1, MAX_STRESS, 16384, true, contract);
    var invalid = cases(p.path("invalid"), "invalid-", 2, MAX_INVALID, 1024, false, contract);
    var tinyAnswers = new HashMap<String, String>();
    tiny.forEach(c -> tinyAnswers.put(c.input(), c.output()));
    var sources = new TreeMap<String, String>();
    var witnesses = new TreeMap<String, HybridFiniteProfile.Case>();
    // Exactly two, like every built-in profile, so both scheduling paths keep their fixed check
    // count.
    if (!p.path("mutants").isArray() || p.path("mutants").size() != 2)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_MUTANTS");
    for (var m : p.path("mutants")) {
      HybridArtifacts.fields(m, "id", "source", "witness");
      String id = text(m.path("id"), 40);
      if (!id.matches("mutant-[a-z0-9-]{1,32}") || sources.containsKey(id))
        throw new ArtifactValidation.Invalid("RULE_PACKAGE_MUTANTS");
      String witness = text(m.path("witness"), 1024);
      // A witness is always a tiny input whose answer came from the independent oracle.
      if (!tinyAnswers.containsKey(witness))
        throw new ArtifactValidation.Invalid("RULE_PACKAGE_WITNESS");
      sources.put(id, text(m.path("source"), 65536));
      witnesses.put(
          id, new HybridFiniteProfile.Case("witness-" + id, witness, tinyAnswers.get(witness)));
    }
    var catalog = p.path("catalog");
    var g = p.path("guidance");
    metadata(catalog, g, p.path("oracleDomain"), p.path("enumeration"));
    return new HybridRulePackage(
        versionId,
        contract,
        rules.deepCopy(),
        catalog.deepCopy(),
        text(p.path("generator"), 65536),
        text(p.path("validator"), 65536),
        tiny,
        invalid,
        stress,
        Collections.unmodifiableMap(sources),
        Collections.unmodifiableMap(witnesses),
        p.path("oracleDomain").asText(),
        p.path("enumeration").asText(),
        g.path("author").asText(),
        g.path("teaching").asText(),
        g.path("reader").asText(),
        largeGenerator,
        List.copyOf(seeds),
        qualifiedSeconds,
        referenceMs);
  }

  /** Display and guidance limits in UTF-8 bytes (Korean is three bytes per character). */
  public static void metadata(
      JsonNode catalog, JsonNode guidance, JsonNode oracleDomain, JsonNode enumeration) {
    HybridArtifacts.fields(catalog, "label", "description", "category", "tags", "rules");
    text(catalog.path("label"), 240);
    text(catalog.path("description"), 2000);
    text(catalog.path("category"), 120);
    if (!catalog.path("tags").isArray()
        || catalog.path("tags").size() < 1
        || catalog.path("tags").size() > 6)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_CATALOG");
    for (var t : catalog.path("tags")) {
      text(t, 90);
      if (t.asText().contains(",")) throw new ArtifactValidation.Invalid("RULE_PACKAGE_CATALOG");
    }
    if (!catalog.path("rules").isArray()
        || catalog.path("rules").size() < 1
        || catalog.path("rules").size() > 5)
      throw new ArtifactValidation.Invalid("RULE_PACKAGE_CATALOG");
    for (var r : catalog.path("rules")) text(r, 900);
    HybridArtifacts.fields(guidance, "author", "teaching", "reader");
    for (String k : List.of("author", "teaching", "reader")) text(guidance.path(k), 6000);
    text(oracleDomain, 1500);
    text(enumeration, 1500);
  }

  public String hash(JsonNode stored) {
    return JudgeJson.hash(
        ENGINE
            + "\n"
            + versionId
            + "\n"
            + JudgeJson.canonical(stored)
            + "\nwall<=4000;repeat=2;package<=20;answers=oracle-tiny,reference-stress");
  }

  public String answer(String input) {
    for (var list : List.of(tiny, stress))
      for (var c : list) if (c.input().equals(input)) return c.output();
    throw new IllegalArgumentException("PROFILE_INPUT_BOUND");
  }

  public boolean hasLarge() {
    return largeGenerator != null;
  }

  /**
   * Runner-side generated tests; expectation REFERENCE needs the qualified reference, VALID the
   * validator as program.
   */
  public static ObjectNode generated(
      String generator, List<String> seeds, String reference, String expected) {
    var g = JudgeJson.JSON.createObjectNode().put("generator", generator);
    if (expected.equals("REFERENCE")) g.put("reference", reference);
    var t = g.putArray("tests");
    for (int i = 0; i < seeds.size(); i++)
      t.addObject().put("id", "large-" + i).put("seed", seeds.get(i)).put("expected", expected);
    return g;
  }

  public ObjectNode generated(String reference, String expected) {
    return generated(largeGenerator, largeSeeds, reference, expected);
  }

  public boolean tiny(String input) {
    return tiny.stream().anyMatch(c -> c.input().equals(input));
  }

  /** Four distinct qualified tiny inputs chosen by seed; no unqualified answers enter a package. */
  public List<String> random(long seed) {
    var pool = new ArrayList<String>();
    tiny.forEach(c -> pool.add(c.input()));
    var r = new SplittableRandom(seed);
    for (int i = pool.size() - 1; i > 0; i--) {
      int j = r.nextInt(i + 1);
      var t = pool.get(i);
      pool.set(i, pool.get(j));
      pool.set(j, t);
    }
    return List.copyOf(pool.subList(0, 4));
  }

  public List<JsonNode> tests(String role) {
    boolean validator = role.equals("domain-valid") || role.equals("stress-valid");
    List<HybridFiniteProfile.Case> cs;
    if (role.startsWith("mutant-")) {
      var w = witnesses.get(role);
      if (w == null) throw new IllegalArgumentException("UNKNOWN_MUTANT");
      cs = List.of(w);
    } else
      cs =
          switch (role) {
            case "domain-invalid" -> invalid;
            case "stress-valid", "stress-reference-0", "stress-reference-1" -> stress;
            case "domain-valid", "domain-reference", "domain-oracle" -> tiny;
            default -> throw new IllegalArgumentException("UNKNOWN_FINITE_ROLE");
          };
    return cs.stream()
        .map(
            c ->
                (JsonNode)
                    JudgeJson.JSON
                        .createObjectNode()
                        .put("id", c.id())
                        .put("input", c.input())
                        .put("output", validator ? "VALID\n" : c.output()))
        .toList();
  }

  public ObjectNode coverage(String profileHash) {
    return JudgeJson.JSON
        .createObjectNode()
        .put("profileHash", profileHash)
        .put("cases", tiny.size())
        .put("description", "Qualified tiny inputs for the registered rule: " + oracleDomain)
        .put("entireContractExhausted", false)
        .put("entireDeclaredOracleDomainExhausted", false)
        .put(
            "expectedAnswers",
            "independent onboarding oracle agreed with the qualified reference; stress answers are"
                + " reference outputs");
  }

  public ObjectNode maximumChecks() {
    return JudgeJson.JSON
        .createObjectNode()
        .put("cases", stress.size())
        .put("repetitions", 2)
        .put("perTestMarginMs", 4000)
        .put("executionMode", "EXCLUSIVE")
        .put("worstCaseForEveryAlgorithm", false);
  }

  public String readerInstructions() {
    return " The server supports this registered rule. Use exactly inputDomain=\""
        + oracleDomain
        + "\" and enumeration=\""
        + enumeration
        + "\". Independently implement a Java 8 brute-force oracle for that small domain from the"
        + " public snapshot only. Supply 1 to 8 small valid inputs inside that domain, exactly"
        + " matching the public input format. No expected outputs, malformed inputs, ellipses or"
        + " expanded maximum-size inputs. "
        + readerGuidance;
  }
}
