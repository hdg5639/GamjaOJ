package dev.gamjaoj;

import java.security.Principal;
import org.springframework.web.bind.annotation.*;

/** A member removes a problem or rule they made. */
@RestController
class ContentDeletionController {
    private final ContentDeletion deletion;
    ContentDeletionController(ContentDeletion deletion){this.deletion=deletion;}
    @DeleteMapping("/api/problems/{version}")
    ContentDeletion.Result problem(Principal user,@PathVariable String version){return deletion.problem(user.getName(),version);}
    @DeleteMapping("/api/rules/{id}")
    ContentDeletion.Result rule(Principal user,@PathVariable String id){return deletion.rule(user.getName(),id);}
}
