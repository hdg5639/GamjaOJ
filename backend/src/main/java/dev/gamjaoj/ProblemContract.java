package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Versioned declarations supply data and trusted rules; Runner executes all generated code. */
interface ProblemContract {
    String id();
    String title();
    String statement();
    String categoryId();
    String categoryLabel();
    List<String> operationTags();
    ObjectNode spec();
    ObjectNode test(String id,String input);
    List<JsonNode> cases(long seed);
    List<String> invalidInputs();
    String mutant(boolean first);
    String mutantRole(boolean first);
    List<JsonNode> mutantCases(List<JsonNode> cases);
    String smallDomain();
    default JsonNode sample(){return cases(0).get(0);}
}
