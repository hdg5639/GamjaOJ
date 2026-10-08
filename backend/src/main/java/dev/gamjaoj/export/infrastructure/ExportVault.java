package dev.gamjaoj.export.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamjaoj.export.config.ExportSettings;
import dev.gamjaoj.shared.exception.AccountException;
import dev.gamjaoj.shared.support.JudgeJson;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

@Component
public final class ExportVault {
  final ExportSettings settings;
  final SecureRandom random = new SecureRandom();

  public ExportVault(ExportSettings settings) {
    this.settings = settings;
  }

  public String seal(String binding, JsonNode value) {
    try {
      byte[] nonce = new byte[12];
      random.nextBytes(nonce);
      var cipher = cipher(Cipher.ENCRYPT_MODE, binding, nonce);
      byte[] encrypted =
          cipher.doFinal(JudgeJson.canonical(value).getBytes(StandardCharsets.UTF_8));
      return "v1."
          + Base64.getEncoder().encodeToString(nonce)
          + "."
          + Base64.getEncoder().encodeToString(encrypted);
    } catch (Exception e) {
      throw new AccountException(503, "연결 정보를 안전하게 저장할 수 없어요.");
    }
  }

  public JsonNode open(String binding, String value) {
    try {
      String[] parts = value.split("\\.");
      if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
      byte[] raw =
          cipher(Cipher.DECRYPT_MODE, binding, Base64.getDecoder().decode(parts[1]))
              .doFinal(Base64.getDecoder().decode(parts[2]));
      return JudgeJson.parse(new String(raw, StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new ExportRemote.Failure("RECONNECT_REQUIRED", false);
    }
  }

  public Cipher cipher(int mode, String binding, byte[] nonce) throws Exception {
    var c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(
        mode,
        new SecretKeySpec(Base64.getDecoder().decode(settings.value("EXPORT_TOKEN_KEY")), "AES"),
        new GCMParameterSpec(128, nonce));
    c.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
    return c;
  }

  public String random() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String challenge(String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
