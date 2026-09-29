package dev.gamjaoj;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server copy of in-progress code so a member can continue on another browser or device. Latest write wins. */
@Service
class CodeDrafts {
    static final int MAX_DRAFTS=300;
    private static final Pattern SCOPE=Pattern.compile("(p|d):[A-Za-z0-9._:-]{1,110}");
    record Draft(String scope,String language,String source,OffsetDateTime updatedAt) {}
    private final JdbcClient jdbc;private final Submissions submissions;
    CodeDrafts(JdbcClient jdbc,Submissions submissions){this.jdbc=jdbc;this.submissions=submissions;}

    private static void check(String scope,String language) {
        if(scope==null||!SCOPE.matcher(scope).matches())throw new AccountException(400,"초안 위치가 올바르지 않아요.");
        if(language==null||!java.util.List.of("JAVA","CPP","PYTHON").contains(language))throw new AccountException(400,"지원하지 않는 언어예요.");
    }
    Optional<Draft> get(String username,String scope,String language) {
        check(scope,language);UUID user=submissions.owner(username,false);
        return jdbc.sql("SELECT source,updated_at FROM code_draft WHERE user_id=? AND scope=? AND language=?").param(user).param(scope).param(language)
                .query((r,n)->new Draft(scope,language,r.getString(1),r.getObject(2,OffsetDateTime.class))).optional();
    }
    @Transactional
    public Draft save(String username,String scope,String language,String source) {
        check(scope,language);
        if(source==null||source.getBytes(StandardCharsets.UTF_8).length>65536)throw new AccountException(400,"코드는 UTF-8 기준 64 KiB 이내로 저장할 수 있어요.");
        UUID user=submissions.owner(username,true);var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(jdbc.sql("UPDATE code_draft SET source=?,updated_at=? WHERE user_id=? AND scope=? AND language=?").param(source).param(now).param(user).param(scope).param(language).update()==0) {
            jdbc.sql("INSERT INTO code_draft(user_id,scope,language,source,updated_at) VALUES (?,?,?,?,?)").param(user).param(scope).param(language).param(source).param(now).update();
            // Bounded per member: the least recently edited drafts go first.
            jdbc.sql("SELECT updated_at FROM code_draft WHERE user_id=? ORDER BY updated_at DESC LIMIT 1 OFFSET "+MAX_DRAFTS).param(user)
                    .query(OffsetDateTime.class).optional().ifPresent(cutoff->jdbc.sql("DELETE FROM code_draft WHERE user_id=? AND updated_at<=?").param(user).param(cutoff).update());
        }
        return new Draft(scope,language,source,now);
    }
}
