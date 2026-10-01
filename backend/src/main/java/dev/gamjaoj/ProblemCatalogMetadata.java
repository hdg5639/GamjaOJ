package dev.gamjaoj;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Display metadata is separate from immutable judge packages and never represents calibrated ratings. */
final class ProblemCatalogMetadata {
    record Metadata(String category, List<String> tags, String difficulty, String difficultySource) {}
    static Metadata read(ResultSet r) throws SQLException {return read(r,r.getString("id"));}
    static Metadata read(ResultSet r,String version) throws SQLException {
        String category="미분류", difficulty="UNRATED", source="UNRATED";
        List<String> tags=List.of();
        String template=r.getString("template_id"),spec=r.getString("spec_json");
        if(template!=null) {
            var type=GenerationType.of(template);
            category=GenerationStructures.category(type);
            tags=Arrays.stream(r.getString("focus").split(",")).map(GenerationChoices::label).distinct().toList();
            difficulty=type.recipe instanceof GraphRecipe ? "MEDIUM" : "EASY";
            source="TEMPLATE_ESTIMATE";
        } else if(spec!=null) {
            var data=JudgeJson.parse(spec);
            category=data.path("category").asText("미분류");
            var list=new ArrayList<String>();data.path("tags").forEach(t->list.add(t.asText()));tags=List.copyOf(list);
        } else if(List.of("sum-v1","total-v1","valid-parentheses-v1").contains(version)) {
            category=version.equals("valid-parentheses-v1")?"문자열":"수열";
            tags=version.equals("valid-parentheses-v1")?List.of("괄호","균형 검사"):List.of("구현","합계");
            difficulty="EASY";source="TEMPLATE_ESTIMATE";
        }
        if(r.getString("catalog_category")!=null)category=r.getString("catalog_category");
        if(r.getString("catalog_tags")!=null)tags=r.getString("catalog_tags").isBlank()?List.of():Arrays.asList(r.getString("catalog_tags").split(","));
        if(r.getString("catalog_difficulty")!=null){difficulty=r.getString("catalog_difficulty");source=difficulty.equals("UNRATED")?"UNRATED":"AUTHOR_ESTIMATE";}
        return new Metadata(ProblemCategories.display(category),tags,difficulty,source);
    }
}
