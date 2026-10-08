package dev.gamjaoj.service.generation;

import dev.gamjaoj.exception.AccountException;
import dev.gamjaoj.repository.generation.GenerationRecommendationsRepository;
import dev.gamjaoj.service.judge.Submissions;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only suggestions from supported contracts, never model-created execution rules. */
@Service
public class GenerationRecommendations {
  public record Suggestion(
      String template, String title, String statement, List<String> tags, int recentCount) {}

  public record Result(List<Suggestion> suggestions, boolean alternativeAvailable) {}

  private final GenerationRecommendationsRepository repository;
  private final Submissions submissions;

  public GenerationRecommendations(
      GenerationRecommendationsRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
  }

  public static List<GenerationChoices.Selection> candidates(
      String category, List<String> required) {
    // Validate the user's exact constraints before enumerating optional tags.
    GenerationChoices.resolve(category, required);
    var catalog =
        GenerationChoices.catalog().stream()
            .filter(c -> c.id().equals(category))
            .findFirst()
            .orElseThrow();
    var optional =
        catalog.tags().stream()
            .filter(t -> !t.group().equals("연습 목표") && !required.contains(t.id()))
            .map(GenerationChoices.Tag::id)
            .toList();
    var result = new LinkedHashMap<String, GenerationChoices.Selection>();
    // Current families have at most seven operation tags; keep enumeration bounded as the catalog
    // grows.
    if (optional.size() > 12) throw new AccountException(503, "추천 조합 설정을 확인해 주세요.");
    for (int mask = 0; mask < (1 << optional.size()); mask++) {
      var tags = new ArrayList<>(new LinkedHashSet<>(required));
      for (int bit = 0; bit < optional.size(); bit++)
        if ((mask & (1 << bit)) != 0) tags.add(optional.get(bit));
      if (tags.size() > 6) continue;
      try {
        var selection = GenerationChoices.resolve(category, tags);
        result.putIfAbsent(selection.template(), selection);
      } catch (AccountException incompatible) {
        if (incompatible.status != 400) throw incompatible;
        // Mutually exclusive operations are not candidates; creation uses the same validator.
      }
    }
    return List.copyOf(result.values());
  }

  public Result recommend(String username, String category, List<String> required) {
    UUID owner = submissions.owner(username, false);
    var candidates = new ArrayList<>(candidates(category, required));
    String current = GenerationChoices.resolve(category, required).template();
    boolean alternative = candidates.stream().anyMatch(c -> !c.template().equals(current));
    if (alternative) candidates.removeIf(c -> c.template().equals(current));
    var recent = repository.recommendGenerationJob(owner);
    // Shuffle ties so a fresh account does not always receive the first catalog combination.
    Collections.shuffle(candidates, new java.security.SecureRandom());
    candidates.sort(Comparator.comparingInt(c -> Collections.frequency(recent, c.template())));
    return new Result(
        candidates.stream()
            .limit(3)
            .map(
                c ->
                    new Suggestion(
                        c.template(),
                        c.title(),
                        c.statement(),
                        List.of(c.focus().split(",")),
                        Collections.frequency(recent, c.template())))
            .toList(),
        alternative);
  }
}
