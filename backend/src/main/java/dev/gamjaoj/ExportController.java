package dev.gamjaoj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations")
class ExportController {
    final SolutionExports exports;
    ExportController(SolutionExports exports){this.exports=exports;}
    record Destination(@NotBlank @Size(max=300) String targetId,@Size(max=200) String branch,@Size(max=120) String prefix,boolean autoEnabled) {}
    record Request(@NotBlank String provider,@NotNull UUID submissionId) {}
    @GetMapping Object connections(Principal user){return exports.connections(user.getName());}
    @PostMapping("/{provider}/connect") Object connect(Principal user,@PathVariable String provider){return Map.of("url",exports.start(user.getName(),ExportSettings.provider(provider)));}
    @GetMapping("/{provider}/callback") ResponseEntity<Void> callback(Principal user,@PathVariable String provider,@RequestParam(required=false) String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error){
        provider=ExportSettings.provider(provider);boolean success=false;
        try {if(error==null){exports.callback(user.getName(),provider,state,code);success=true;}}
        catch(AccountException|ExportRemote.Failure ignored){} // Never echo authorization codes, tokens or provider responses.
        return ResponseEntity.status(303).location(URI.create("/?settings=integrations&"+(success?"connected=":"connectionError=")+provider)).build();
    }
    @GetMapping("/{provider}/targets") Object targets(Principal user,@PathVariable String provider,@RequestParam(defaultValue="") String search){return exports.targets(user.getName(),ExportSettings.provider(provider),search);}
    @PutMapping("/{provider}/target") ResponseEntity<Void> target(Principal user,@PathVariable String provider,@Valid @RequestBody Destination request){
        exports.save(user.getName(),ExportSettings.provider(provider),request.targetId,request.branch,request.prefix,request.autoEnabled);return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/{provider}") ResponseEntity<Void> disconnect(Principal user,@PathVariable String provider){exports.disconnect(user.getName(),ExportSettings.provider(provider));return ResponseEntity.noContent().build();}
    @GetMapping("/deliveries") Object deliveries(Principal user,@RequestParam(required=false) UUID submissionId){return exports.deliveries(user.getName(),submissionId);}
    @PostMapping("/exports") Object request(Principal user,@Valid @RequestBody Request request){return Map.of("id",exports.request(user.getName(),ExportSettings.provider(request.provider),request.submissionId));}
    @PostMapping("/deliveries/{id}/retry") ResponseEntity<Void> retry(Principal user,@PathVariable UUID id){exports.retry(user.getName(),id);return ResponseEntity.noContent().build();}
    @ExceptionHandler(ExportRemote.Failure.class) ResponseEntity<?> remote(ExportRemote.Failure failure){
        String message=switch(failure.code){
            case "RECONNECT_REQUIRED" -> "계정 연결이 만료됐어요. 다시 연결해 주세요.";
            case "PERMISSION_REQUIRED" -> "저장 위치의 쓰기 권한을 확인해 주세요.";
            case "TARGET_NOT_FOUND","INVALID_TARGET" -> "저장 위치를 확인해 주세요.";
            case "RATE_LIMITED","CONNECTION_BUSY" -> "잠시 후 다시 시도해 주세요.";
            default -> "외부 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
        };return ResponseEntity.status(failure.retryable?503:409).body(Map.of("message",message));
    }
}
