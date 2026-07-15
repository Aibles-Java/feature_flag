package org.aibles.feature_flag.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * Fails fast — in the {@code prod} profile only — when a required datasource environment variable
 * is missing, with a message that names the variable.
 *
 * <p>Why this exists (issue #23): {@code application-prod.properties} references the datasource
 * with no-default {@code ${VAR}} placeholders, but Spring Boot's {@code @ConfigurationProperties}
 * binder resolves placeholders non-strictly — an unset variable is bound onto {@code
 * DataSourceProperties} as the literal string {@code "${SPRING_DATASOURCE_URL}"} instead of
 * failing. The app would then boot and only die later inside Hikari/Liquibase with an obscure
 * "cannot determine driver" error that does not name the missing variable. {@code APP_JWT_SECRET}
 * is already guarded by {@link JwtProperties}; this covers the datasource trio the same way.
 *
 * <p>Implemented as an {@link EnvironmentPostProcessor} (runs before any bean is created), so its
 * clear error always precedes the DataSource/Liquibase failure. Registered in {@code
 * META-INF/spring.factories}.
 */
public class RequiredDataSourceEnvPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Property key -> the environment variable that supplies it (relaxed-binding canonical name). */
  private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

  static {
    REQUIRED.put("spring.datasource.url", "SPRING_DATASOURCE_URL");
    REQUIRED.put("spring.datasource.username", "SPRING_DATASOURCE_USERNAME");
    REQUIRED.put("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!environment.acceptsProfiles(Profiles.of("prod"))) {
      return;
    }

    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> entry : REQUIRED.entrySet()) {
      if (!isResolved(environment, entry.getKey())) {
        missing.add(entry.getValue() + " (" + entry.getKey() + ")");
      }
    }

    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Missing required environment variable(s) for the prod profile: "
              + String.join(", ", missing)
              + ". Set them (see README.md \"Environment variables\") — the app must not start on "
              + "incomplete datasource configuration.");
    }
  }

  private boolean isResolved(ConfigurableEnvironment environment, String key) {
    String value;
    try {
      value = environment.getProperty(key);
    } catch (RuntimeException ex) {
      // Strict placeholder resolution threw because the referenced ${VAR} is unset.
      return false;
    }
    // Non-strict resolution passes an unset ${VAR} through as a literal; treat that as missing too.
    return StringUtils.hasText(value) && !value.startsWith("${");
  }

  @Override
  public int getOrder() {
    // Run after ConfigDataEnvironmentPostProcessor (which loads application-prod.properties and
    // activates profiles), so all config data is present when this check runs.
    return Ordered.LOWEST_PRECEDENCE;
  }
}
