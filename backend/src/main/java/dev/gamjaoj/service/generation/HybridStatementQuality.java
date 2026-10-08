package dev.gamjaoj.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.domain.ArtifactValidation;
import java.math.BigInteger;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Deterministic checks on learner-facing prose written by the presentation model. The content
 * review still judges meaning; these catch the mechanical failures seen in production: English
 * contract text leaking into the statement and bounds that silently differ from the validator's
 * limits.
 */
public final class HybridStatementQuality {
  private HybridStatementQuality() {}

  private static final Pattern QUOTED = Pattern.compile("`[^`]*`|\"[^\"]*\"|'[^']*'");
  private static final Pattern WORD = Pattern.compile("[A-Za-z]{4,}");
  // Short acronyms, variable names and 3-letter words (max, min, sum) pass; prose words of four or
  // more letters do not.
  private static final Set<String> ALLOWED =
      Set.of("YES", "NO", "MOD", "NULL", "true", "false", "Java", "long", "char", "byte");

  /**
   * Korean prose only: an ASCII word of four or more letters must be quoted (a literal token) or a
   * short acronym.
   */
  public static void korean(String field, String text) {
    String bare = QUOTED.matcher(text).replaceAll(" ");
    var m = WORD.matcher(bare);
    while (m.find()) {
      String word = m.group();
      if (ALLOWED.contains(word) || (word.length() <= 5 && word.equals(word.toUpperCase())))
        continue;
      throw new ArtifactValidation.Invalid("STATEMENT_NOT_KOREAN_" + field.toUpperCase());
    }
    HybridArtifacts.require(
        text.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3),
        "STATEMENT_NOT_KOREAN_" + field.toUpperCase());
  }

  /** Declared API identifiers are learner-visible names, not leaked English prose. */
  public static void korean(String field, String text, JsonNode semantics) {
    var api = semantics.path("callable");
    if (api.isMissingNode()) {
      korean(field, text);
      return;
    }
    var identifiers =
        new TreeSet<String>(
            Set.of("UserSolution", "Main", "String", "boolean", "void", "int", "long"));
    for (var method : api.path("methods")) {
      identifiers.add(method.path("name").asText());
      for (var parameter : method.path("parameters"))
        identifiers.add(parameter.path("name").asText());
    }
    String prose = text;
    for (String name : identifiers)
      if (!name.isEmpty())
        prose =
            prose.replaceAll("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])", " ");
    korean(field, prose);
  }

  private static final Pattern POWER =
      Pattern.compile(
          "(\\d+(?:\\.\\d+)?)\\s*[×x*·]\\s*10\\s*\\^\\s*(\\d+)|10\\s*\\^\\s*(\\d+)|10([⁰¹²³⁴⁵⁶⁷⁸⁹]+)|(\\d+)e(\\d+)");
  private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");
  private static final String SUPERSCRIPT = "⁰¹²³⁴⁵⁶⁷⁸⁹";

  /**
   * Significant bounds (≥ 10) named in the contract limits; small values like 0, 1 or 2 carry no
   * signal.
   */
  public static Set<BigInteger> contractBounds(JsonNode semantics) {
    var out = new TreeSet<BigInteger>();
    for (String key : new String[] {"maxInputSize", "executionConstraints"})
      numbers(semantics.path("limits").path(key).asText(""), out, false);
    out.removeIf(v -> v.compareTo(BigInteger.TEN) < 0);
    return out;
  }

  /**
   * Every contract bound must appear (as any equivalent notation) in the learner's input and limits
   * prose.
   */
  public static void bounds(JsonNode semantics, String prose) {
    var written = new TreeSet<BigInteger>();
    numbers(prose, written, true);
    for (var bound : contractBounds(semantics))
      if (!written.contains(bound)) throw new ArtifactValidation.Invalid("STATEMENT_BOUND_MISSING");
  }

  private static void numbers(String text, Set<BigInteger> out, boolean powers) {
    String rest = text;
    if (powers) {
      var p = POWER.matcher(text);
      var sb = new StringBuilder();
      while (p.find()) {
        BigInteger value = null;
        if (p.group(1) != null && !p.group(1).contains("."))
          value =
              new BigInteger(p.group(1)).multiply(BigInteger.TEN.pow(Integer.parseInt(p.group(2))));
        else if (p.group(3) != null) value = BigInteger.TEN.pow(Integer.parseInt(p.group(3)));
        else if (p.group(4) != null) {
          var digits = new StringBuilder();
          for (char c : p.group(4).toCharArray()) digits.append(SUPERSCRIPT.indexOf(c));
          value = BigInteger.TEN.pow(Integer.parseInt(digits.toString()));
        } else if (p.group(5) != null)
          value =
              new BigInteger(p.group(5)).multiply(BigInteger.TEN.pow(Integer.parseInt(p.group(6))));
        if (value != null) out.add(value);
        p.appendReplacement(sb, " ");
      }
      p.appendTail(sb);
      rest = sb.toString();
    }
    var m = NUMBER.matcher(rest);
    while (m.find()) {
      String digits = m.group().replace(",", "");
      if (digits.length() <= 19) out.add(new BigInteger(digits));
    }
  }
}
