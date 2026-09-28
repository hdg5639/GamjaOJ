package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class ProblemCatalog {
    private final JdbcClient jdbc;
    private final Submissions submissions;
    ProblemCatalog(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}
    record Settings(@NotNull Boolean shared, @NotBlank @Size(max=80) String category,
                    @NotNull @Size(max=6) List<@NotBlank @Size(max=80) String> tags,
                    @NotNull @Pattern(regexp="UNRATED|EASY|MEDIUM|HARD|EXPERT") String difficulty) {}
    @PutMapping("/api/problems/{version}/catalog-settings")
    @Transactional
    Submissions.Problem save(Principal user,@PathVariable String version,@Valid @RequestBody Settings request) {
        var owner=submissions.owner(user.getName(),true);
        if(request.tags().stream().anyMatch(t->t.contains(",")))throw new AccountException(400,"태그 안에는 쉼표를 사용할 수 없어요.");
        int changed=jdbc.sql("UPDATE problem_version SET shared=?,catalog_category=?,catalog_tags=?,catalog_difficulty=? WHERE id=? AND owner_id=? AND ready=true AND diagnostic_only=false AND (review_hold=false OR ?=false)")
                .param(request.shared()).param(request.category().strip()).param(String.join(",",request.tags().stream().map(String::strip).distinct().toList()))
                .param(request.difficulty()).param(version).param(owner).param(request.shared()).update();
        if(changed!=1)throw new AccountException(404,"공개 설정을 변경할 수 있는 내 문제를 찾을 수 없어요. 검토 중인 문제는 공개할 수 없습니다.");
        return submissions.problems(user.getName()).stream().filter(p->p.version().equals(version)).findFirst().orElseThrow();
    }
}
