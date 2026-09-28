package dev.gamjaoj;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * After a rule qualifies, continue into one problem generation from it when the request asked to publish.
 * The generation id is derived from the onboarding id, so retries admit the same request exactly once.
 */
@Service
class HybridRuleFollowup {
    private final JdbcClient jdbc;private final HybridAdmission admission;
    HybridRuleFollowup(JdbcClient jdbc,HybridAdmission admission){this.jdbc=jdbc;this.admission=admission;}
    static UUID generationId(UUID onboarding){return UUID.nameUUIDFromBytes(("rule-followup:"+onboarding).getBytes(StandardCharsets.UTF_8));}
    void advance() {
        var rows=jdbc.sql("SELECT o.id,o.version_id,o.request_json,u.username FROM hybrid_rule_onboarding o JOIN app_user u ON u.id=o.owner_id "
                        +"WHERE o.status='ACTIVE' AND o.version_id IS NOT NULL AND o.followup_generation_id IS NULL AND o.followup_error IS NULL ORDER BY o.updated_at")
                .query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),JudgeJson.parse(r.getString(3)),r.getString(4)}).list();
        for(var row:rows) {
            var request=(com.fasterxml.jackson.databind.JsonNode)row[2];
            if(!request.path("publish").asBoolean(false))continue;
            UUID id=(UUID)row[0],generation=generationId(id);
            var body=JudgeJson.JSON.createObjectNode().put("profileId",(String)row[1]).put("shared",request.path("shared").asBoolean(false)).put("publishOnSuccess",true);
            try {
                admission.create((String)row[3],generation,body);
                jdbc.sql("UPDATE hybrid_rule_onboarding SET followup_generation_id=? WHERE id=?").param(generation).param(id).update();
            } catch(AccountException refused) {
                // Another generation of this member is still running: try again on a later tick.
                if(refused.status==409&&refused.getMessage().contains("진행 중"))continue;
                jdbc.sql("UPDATE hybrid_rule_onboarding SET followup_error=? WHERE id=?")
                        .param(refused.getMessage().length()>160?refused.getMessage().substring(0,160):refused.getMessage()).param(id).update();
            }
        }
    }
}
