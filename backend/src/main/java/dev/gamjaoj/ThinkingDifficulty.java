package dev.gamjaoj;

import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Public, provisional reasoning ratings. Independent of judge identity and personal confidence. */
final class ThinkingDifficulty {
    static final String[] NAMES={"", "그대로", "살펴보기", "골라쓰기", "이어붙이기", "뒤집어보기", "상태 만들기", "덜어내기", "꿰뚫기", "새로 짜기"};
    static String authoringBand(Integer layer){
        if(layer==null||layer<1||layer>9)throw new AccountException(400,"생각의 겹은 1~9에서 골라 주세요.");
        return layer<=2?"EASY":layer<=5?"MEDIUM":layer<=7?"HARD":"EXPERT";
    }
    record Input(@NotNull @Min(1) @Max(9) Integer layer,
                 @NotNull @Min(1) @Max(5) Integer insight,
                 @NotNull @Min(1) @Max(5) Integer implementation,
                 @NotNull @Min(1) @Max(5) Integer edgeCases,
                 @NotBlank @Size(max=300) String rationale) {}
    record Profile(int layer,String name,int insight,int implementation,int edgeCases,String rationale,String source) {
        String label(){return layer+"겹 · "+name;}
    }
    static Profile read(ResultSet r) throws SQLException {
        int layer=r.getInt("thinking_layer");
        if(r.wasNull())return null;
        return new Profile(layer,NAMES[layer],r.getInt("thinking_insight"),r.getInt("thinking_implementation"),
                r.getInt("thinking_edge_cases"),r.getString("thinking_rationale"),r.getString("thinking_source"));
    }
    static final String COLUMNS="t.layer AS thinking_layer,t.insight AS thinking_insight,t.implementation AS thinking_implementation,t.edge_cases AS thinking_edge_cases,t.rationale AS thinking_rationale,t.source AS thinking_source";
    static final String JOIN=" LEFT JOIN problem_thinking_profile t ON t.problem_version=p.id AND t.package_sha256=p.package_sha256 ";
}
