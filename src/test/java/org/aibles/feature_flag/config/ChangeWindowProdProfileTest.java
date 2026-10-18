package org.aibles.feature_flag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.ZoneId;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.service.impl.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S-2.12 (D-09): boots with the real {@code application.properties} + {@code
 * application-prod.properties} (profile {@code prod}) and proves the change-window zone is required
 * there (no dev default, no JVM-zone fallback), and that the Lombok-generated PermissionService
 * constructor really receives the dedicated change-window Clock.
 */
class ChangeWindowProdProfileTest {

  @Configuration
  @Import(AppConfig.class)
  static class Cfg {}

  @Configuration
  @Import({AppConfig.class, Repos.class, PermissionService.class})
  static class WithPermissionService {}

  @Configuration
  static class Repos {
    @Bean
    OrganizationMemberRepository memberRepository() {
      return mock(OrganizationMemberRepository.class);
    }

    @Bean
    ProjectRepository projectRepository() {
      return mock(ProjectRepository.class);
    }

    @Bean
    EnvironmentRepository environmentRepository() {
      return mock(EnvironmentRepository.class);
    }

    @Bean
    PermissionGrantRepository grantRepository() {
      return mock(PermissionGrantRepository.class);
    }
  }

  private ApplicationContextRunner prodRunner(Class<?> cfg) {
    return new ApplicationContextRunner()
        .withUserConfiguration(cfg)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=prod");
  }

  private static String rootMessages(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getMessage()).append('\n');
    }
    return sb.toString();
  }

  @Test
  void prodProfileWithoutTheEnvVarFailsStartupNamingIt() {
    // Guard: the env var must really be absent from the process, or this test proves nothing.
    assertThat(System.getenv("APP_CHANGE_WINDOW_ZONE")).isNull();
    prodRunner(Cfg.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure()))
                  .contains("APP_CHANGE_WINDOW_ZONE");
            });
  }

  @Test
  void prodProfileWithTheZoneSetStartsAndUsesIt() {
    prodRunner(Cfg.class)
        .withPropertyValues("APP_CHANGE_WINDOW_ZONE=Asia/Ho_Chi_Minh")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AppConfig.CHANGE_WINDOW_CLOCK, Clock.class).getZone())
                  .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
            });
  }

  @Test
  void prodProfileWithAnInvalidZoneFailsStartup() {
    prodRunner(Cfg.class)
        .withPropertyValues("APP_CHANGE_WINDOW_ZONE=Mars/Olympus")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessages(context.getStartupFailure()))
                  .contains("not a valid IANA zone id");
            });
  }

  @Test
  void defaultProfileKeepsTheUtcDevDefaultSoLocalAndTestBootsStillWork() {
    new ApplicationContextRunner()
        .withUserConfiguration(Cfg.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AppConfig.CHANGE_WINDOW_CLOCK, Clock.class).getZone())
                  .isEqualTo(ZoneId.of("UTC"));
            });
  }

  @Test
  void permissionServiceConstructorReceivesTheChangeWindowClockNotTheGeneralOne() {
    ZoneId configured =
        ZoneId.systemDefault().equals(ZoneId.of("Pacific/Kiritimati"))
            ? ZoneId.of("Pacific/Pago_Pago")
            : ZoneId.of("Pacific/Kiritimati");
    prodRunner(WithPermissionService.class)
        .withPropertyValues("APP_CHANGE_WINDOW_ZONE=" + configured.getId())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Clock injected =
                  (Clock)
                      ReflectionTestUtils.getField(
                          context.getBean(PermissionService.class), "clock");
              assertThat(injected.getZone()).isEqualTo(configured);
              assertThat(injected).isSameAs(context.getBean(AppConfig.CHANGE_WINDOW_CLOCK));
              assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.systemDefault());
            });
  }

  @Test
  void qualifierIsCopiedOntoTheGeneratedConstructorParameter() {
    Parameter last = null;
    for (var ctor : PermissionService.class.getDeclaredConstructors()) {
      Parameter[] ps = ctor.getParameters();
      if (ps.length > 0 && ps[ps.length - 1].getType() == Clock.class) {
        last = ps[ps.length - 1];
      }
    }
    assertThat(last).as("constructor with a trailing Clock parameter").isNotNull();
    Qualifier q = last.getAnnotation(Qualifier.class);
    assertThat(q).as("@Qualifier on the Clock constructor parameter").isNotNull();
    assertThat(q.value()).isEqualTo(AppConfig.CHANGE_WINDOW_CLOCK);
  }
}
