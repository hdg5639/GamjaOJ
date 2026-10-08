package dev.gamjaoj.config;

import dev.gamjaoj.exception.AccountException;
import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ExportSettings {
  final Environment env;

  public ExportSettings(Environment env) {
    this.env = env;
  }

  public String value(String key) {
    return env.getProperty(key, "").trim();
  }

  public String origin() {
    return value("PUBLIC_BASE_URL").replaceAll("/+$", "");
  }

  public String client(String provider) {
    return value("EXPORT_" + provider + "_CLIENT_ID");
  }

  public String secret(String provider) {
    return value("EXPORT_" + provider + "_CLIENT_SECRET");
  }

  public boolean enabled() {
    return Boolean.parseBoolean(value("EXPORTS_ENABLED"));
  }

  public boolean ready(String provider) {
    if (!enabled() || client(provider).isEmpty() || secret(provider).isEmpty()) return false;
    try {
      return java.util.Base64.getDecoder().decode(value("EXPORT_TOKEN_KEY")).length == 32
          && URI.create(origin()).getHost() != null
          && URI.create(origin()).getScheme().equals("https");
    } catch (Exception e) {
      return false;
    }
  }

  public String callback(String provider) {
    return origin() + "/api/integrations/" + provider.toLowerCase() + "/callback";
  }

  public String installUrl() {
    String slug = value("EXPORT_GITHUB_APP_SLUG");
    return slug.matches("[a-z0-9-]{1,100}")
        ? "https://github.com/apps/" + slug + "/installations/new"
        : null;
  }

  public String api(String provider) {
    return provider.equals("GITHUB") ? "https://api.github.com" : "https://api.notion.com/v1";
  }

  public static String provider(String p) {
    String value = p.toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("GITHUB", "NOTION").contains(value))
      throw new AccountException(404, "지원하지 않는 연결이에요.");
    return value;
  }
}
