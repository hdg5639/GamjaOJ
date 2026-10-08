package dev.gamjaoj.problem.service;

import dev.gamjaoj.judge.service.Submissions;
import dev.gamjaoj.problem.repository.ProblemIllustrationsRepository;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProblemIllustrations {
  public record Image(
      UUID id,
      String src,
      String alt,
      String caption,
      int width,
      int height,
      String kind,
      String afterParagraph,
      String explanation) {}

  public record Presentation(boolean canEdit, List<Image> illustrations) {}

  public record Curated(
      String version,
      String statementSha256,
      String file,
      String alt,
      String caption,
      int width,
      int height,
      String kind,
      String afterParagraph,
      String explanation) {}

  private record Access(
      UUID owner,
      boolean ready,
      boolean held,
      boolean diagnostic,
      String statement,
      String hash,
      boolean shared) {}

  private final ProblemIllustrationsRepository repository;
  private final Submissions submissions;
  private final List<Curated> curated;

  public ProblemIllustrations(ProblemIllustrationsRepository repository, Submissions submissions) {
    this.repository = repository;
    this.submissions = submissions;
    try (var stream = new ClassPathResource("problem-illustrations-v1.json").getInputStream()) {
      curated = List.of(JudgeJson.JSON.readValue(stream, Curated[].class));
    } catch (IOException e) {
      throw new IllegalStateException("Missing illustration manifest", e);
    }
    for (var c : curated)
      if (!c.file().matches("[a-f0-9]{64}\\.svg")
          || !c.statementSha256().matches("[a-f0-9]{64}")
          || !List.of("STRUCTURE", "COMMAND_EXPLANATION").contains(c.kind()))
        throw new IllegalStateException("Invalid curated illustration");
  }

  private Access access(UUID owner, String version) {
    var p =
        repository
            .accessProblemVersion(
                version,
                (r, n) ->
                    new Access(
                        r.getObject(1, UUID.class),
                        r.getBoolean(2),
                        r.getBoolean(3),
                        r.getBoolean(4),
                        JudgeJson.parse(r.getString(5)).path("statement").asText(),
                        r.getString(6),
                        r.getBoolean(7)))
            .orElseThrow(() -> new AccountException(404, "문제 그림을 찾을 수 없어요."));
    boolean allowed =
        p.ready()
            && !p.diagnostic()
            && (owner.equals(p.owner()) || !p.held() && (p.owner() == null || p.shared()));
    if (p.ready() && !p.held() && p.diagnostic())
      allowed = repository.accessDiagnosticItem(owner, version, p.hash()) > 0;
    if (!allowed) throw new AccountException(404, "문제 그림을 찾을 수 없어요.");
    return p;
  }

  public Presentation presentation(String username, String version) {
    UUID owner = submissions.owner(username, false);
    var p = access(owner, version);
    return presentation(owner, version, p);
  }

  private Presentation presentation(UUID owner, String version, Access p) {
    var images = new ArrayList<Image>();
    // Curated drawings contain only already-public rules; match the exact statement before showing
    // them.
    for (var c : curated)
      if (c.version().equals(version) && c.statementSha256().equals(JudgeJson.hash(p.statement())))
        images.add(
            new Image(
                null,
                "/problem-illustrations/" + c.file(),
                c.alt(),
                c.caption(),
                c.width(),
                c.height(),
                c.kind(),
                c.afterParagraph(),
                c.explanation()));
    images.addAll(
        repository.presentationProblemIllustration(
            version,
            p.hash(),
            (r, n) ->
                new Image(
                    r.getObject(1, UUID.class),
                    "/api/problem-images/" + r.getObject(1, UUID.class),
                    r.getString(2),
                    r.getString(3),
                    r.getInt(4),
                    r.getInt(5),
                    "AUTHOR",
                    null,
                    null)));
    return new Presentation(
        owner.equals(p.owner()) && !p.held() && !p.diagnostic(), List.copyOf(images));
  }

  public byte[] image(String username, UUID id) {
    UUID owner = submissions.owner(username, false);
    var row =
        repository
            .imageProblemIllustration(
                id, (r, n) -> new Object[] {r.getString(1), r.getString(2), r.getBytes(3)})
            .orElseThrow(() -> new AccountException(404, "문제 그림을 찾을 수 없어요."));
    if (!access(owner, (String) row[0]).hash().equals(row[1]))
      throw new AccountException(404, "문제 그림을 찾을 수 없어요.");
    return (byte[]) row[2];
  }

  @Transactional
  public Presentation upload(
      String username, String version, UUID key, byte[] bytes, String alt, String caption) {
    UUID owner = submissions.owner(username, true);
    var p = access(owner, version);
    if (!owner.equals(p.owner()) || p.held() || p.diagnostic())
      throw new AccountException(404, "내가 만든 문제에만 그림을 추가할 수 있어요.");
    if (alt == null
        || alt.isBlank()
        || alt.strip().length() > 240
        || caption == null
        || caption.strip().length() > 600)
      throw new AccountException(400, "그림 설명은 1~240자, 캡션은 600자 이내로 작성해 주세요.");
    var normalized = normalize(bytes);
    String hash = hash(normalized.bytes);
    var previous =
        repository
            .uploadProblemIllustration(
                key,
                (r, n) ->
                    new String[] {
                      r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)
                    })
            .orElse(null);
    if (previous != null) {
      if (!owner.toString().equals(previous[0])
          || !version.equals(previous[1])
          || !hash.equals(previous[2])
          || !alt.strip().equals(previous[3])
          || !caption.strip().equals(previous[4]))
        throw new AccountException(409, "같은 업로드 요청의 내용이 달라요.");
      return presentation(owner, version, p);
    }
    if (repository.uploadProblemIllustration2(version) >= 8)
      throw new AccountException(409, "한 문제에는 그림을 최대 8개까지 추가할 수 있어요.");
    long used = repository.uploadProblemIllustration3(owner);
    if (used + normalized.bytes.length > 32L * 1024 * 1024)
      throw new AccountException(409, "그림 보관 용량을 초과했어요. 사용하지 않는 그림을 정리해 주세요.");
    repository.uploadProblemIllustration4(
        key,
        version,
        owner,
        p.hash(),
        hash,
        alt.strip(),
        caption.strip(),
        normalized.bytes,
        normalized.width,
        normalized.height);
    return presentation(owner, version, p);
  }

  @Transactional
  public Presentation delete(String username, String version, UUID id) {
    UUID owner = submissions.owner(username, true);
    var p = access(owner, version);
    if (!owner.equals(p.owner()) || p.held() || p.diagnostic())
      throw new AccountException(404, "삭제할 수 있는 내 문제 그림을 찾을 수 없어요.");
    var target =
        repository
            .deleteProblemIllustration(
                id, (r, n) -> new Object[] {r.getString(1), r.getObject(2, UUID.class)})
            .orElse(null);
    if (target != null) {
      if (!version.equals(target[0]) || !owner.equals(target[1]))
        throw new AccountException(404, "삭제할 수 있는 내 문제 그림을 찾을 수 없어요.");
      repository.deleteProblemIllustration2(id);
    }
    return presentation(owner, version, p);
  }

  private record Normalized(byte[] bytes, int width, int height) {}

  private static Normalized normalize(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 3 * 1024 * 1024)
      throw new AccountException(400, "PNG·JPEG 그림을 3 MiB 이내로 올려 주세요.");
    try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new AccountException(400, "PNG·JPEG 그림만 올릴 수 있어요.");
      var reader = readers.next();
      try {
        reader.setInput(input);
        String format = reader.getFormatName();
        if (!List.of("png", "jpeg", "jpg").contains(format.toLowerCase(Locale.ROOT)))
          throw new AccountException(400, "PNG·JPEG 그림만 올릴 수 있어요.");
        int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w < 1 || h < 1 || w > 5000 || h > 5000 || (long) w * h > 8_000_000)
          throw new AccountException(400, "그림은 가로·세로 5000픽셀, 전체 800만 픽셀 이내로 올려 주세요.");
        BufferedImage decoded = reader.read(0);
        var canonical = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var graphics = canonical.createGraphics();
        try {
          graphics.drawImage(decoded, 0, 0, null);
        } finally {
          graphics.dispose();
          decoded.flush();
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(canonical, "png", output);
        canonical.flush();
        byte[] png = output.toByteArray();
        if (png.length > 4 * 1024 * 1024)
          throw new AccountException(400, "변환된 그림이 너무 커요. 크기를 줄여 주세요.");
        return new Normalized(png, w, h);
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException e) {
      throw new AccountException(400, "그림 파일을 읽지 못했어요. 다른 PNG·JPEG 파일을 선택해 주세요.");
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
