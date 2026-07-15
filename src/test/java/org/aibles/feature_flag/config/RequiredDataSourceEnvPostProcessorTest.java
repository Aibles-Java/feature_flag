package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Unit tests for {@link RequiredDataSourceEnvPostProcessor}. Exercises the post-processor directly
 * against a hand-built {@link StandardEnvironment}, covering both placeholder-resolution behaviors
 * (strict throw and non-strict literal pass-through) since the whole point is that an unset {@code
 * SPRING_DATASOURCE_*} variable must abort startup with a message naming it (issue #23).
 */
class RequiredDataSourceEnvPostProcessorTest {

  private final RequiredDataSourceEnvPostProcessor processor =
      new RequiredDataSourceEnvPostProcessor();
  private final SpringApplication application = new SpringApplication();

  private static StandardEnvironment environment(String... activeProfiles) {
    StandardEnvironment environment = new StandardEnvironment();
    if (activeProfiles.length > 0) {
      environment.setActiveProfiles(activeProfiles);
    }
    return environment;
  }

  private static void putDatasource(StandardEnvironment environment, Map<String, Object> values) {
    environment.getPropertySources().addFirst(new MapPropertySource("test-datasource", values));
  }

  private static Map<String, Object> allResolved() {
    Map<String, Object> values = new HashMap<>();
    values.put("spring.datasource.url", "jdbc:postgresql://db:5432/feature_flag_db");
    values.put("spring.datasource.username", "ff_user");
    values.put("spring.datasource.password", "s3cret");
    return values;
  }

  @Test
  void allDatasourceVarsResolvedInProd_doesNotThrow() {
    StandardEnvironment environment = environment("prod");
    putDatasource(environment, allResolved());

    assertThatCode(() -> processor.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }

  @Test
  void missingVarWithStrictResolution_throwsNamingTheVariable() {
    // Default StandardEnvironment resolves placeholders strictly: an unset ${VAR} throws.
    StandardEnvironment environment = environment("prod");
    Map<String, Object> values = allResolved();
    values.put("spring.datasource.url", "${SPRING_DATASOURCE_URL}"); // referenced var not set
    putDatasource(environment, values);

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_DATASOURCE_URL");
  }

  @Test
  void unresolvedLiteralWithNonStrictResolution_throwsNamingTheVariable() {
    // Mirrors the real @ConfigurationProperties binder: non-strict resolution passes an unset
    // ${VAR} through as a literal string instead of throwing. The check must still catch it.
    StandardEnvironment environment = environment("prod");
    environment.setIgnoreUnresolvableNestedPlaceholders(true);
    Map<String, Object> values = allResolved();
    values.put("spring.datasource.password", "${SPRING_DATASOURCE_PASSWORD}");
    putDatasource(environment, values);

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
  }

  @Test
  void blankVarInProd_throwsNamingTheVariable() {
    StandardEnvironment environment = environment("prod");
    Map<String, Object> values = allResolved();
    values.put("spring.datasource.username", "   ");
    putDatasource(environment, values);

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, application))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
  }

  @Test
  void missingVarsOutsideProdProfile_areIgnored() {
    // Default/dev profile carries its own real datasource values, so the prod-only check must
    // not fire even when nothing is set here.
    StandardEnvironment environment = environment(); // no active profile
    assertThatCode(() -> processor.postProcessEnvironment(environment, application))
        .doesNotThrowAnyException();
  }
}
