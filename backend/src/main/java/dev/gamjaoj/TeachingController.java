package dev.gamjaoj;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
@RestController
class TeachingController {
    private final JdbcClient jdbc;
    TeachingController(JdbcClient jdbc) { this.jdbc=jdbc; }
    @GetMapping("/api/problems/{version}/teaching") JsonNode teaching(Principal user,@PathVariable String version) {
        return jdbc.sql("SELECT teaching_json FROM problem_version WHERE id=? AND ready=true AND diagnostic_only=false AND review_hold=false AND (owner_id IS NULL OR shared=true OR owner_id=(SELECT id FROM app_user WHERE username=?))").param(version).param(user.getName())
                .query((r,n)->r.getString(1)==null?JudgeJson.parse("{\"hints\":[],\"editorial\":\"\"}"):JudgeJson.parse(r.getString(1)))
                .optional().orElseThrow(()->new AccountException(404,"게시된 문제를 찾을 수 없어요."));
    }
}
