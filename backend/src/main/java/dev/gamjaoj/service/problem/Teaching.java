package dev.gamjaoj.service.problem;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.problem.TeachingRepository;
import dev.gamjaoj.support.JudgeJson;

@org.springframework.stereotype.Service
public class Teaching {
  private final TeachingRepository repository;

  public Teaching(TeachingRepository repository) {
    this.repository = repository;
  }

  public JsonNode teaching(String username, String version) {
    return repository
        .teachingProblemVersion(
            version,
            username,
            (r, n) ->
                r.getString(1) == null
                    ? JudgeJson.parse("{\"hints\":[],\"editorial\":\"\"}")
                    : JudgeJson.parse(r.getString(1)))
        .orElseThrow(() -> new AccountException(404, "게시된 문제를 찾을 수 없어요."));
  }
}
