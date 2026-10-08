package dev.gamjaoj.problem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Versioned declarations supply data and trusted rules; Runner executes all generated code. */
public interface ProblemContract {
  public String id();

  public String title();

  public String statement();

  public String categoryId();

  public String categoryLabel();

  public List<String> operationTags();

  public ObjectNode spec();

  public ObjectNode test(String id, String input);

  public List<JsonNode> cases(long seed);

  public List<String> invalidInputs();

  public String mutant(boolean first);

  public String mutantRole(boolean first);

  public List<JsonNode> mutantCases(List<JsonNode> cases);

  public String smallDomain();

  public default JsonNode sample() {
    return cases(0).get(0);
  }
}
