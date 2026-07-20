package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.RefreshRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.exception.InvalidRefreshTokenException;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.AuthService;
import org.aibles.feature_flag.service.RefreshTokenService;
import org.aibles.feature_flag.service.RefreshTokenService.RotationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Reuse detection revokes the family and then throws. Both happen inside one transaction, so if the
 * throw rolls that transaction back the revoke is silently undone and the whole security control is
 * a no-op. Mockito can only prove {@code revokeFamily} was <em>called</em> — only a real
 * transaction boundary proves it was <em>committed</em>.
 *
 * <p>Deliberately NOT {@code @Transactional}: a test-managed transaction would wrap the service
 * calls in one rolled-back outer transaction and mask exactly the behaviour under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_rt_commit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
class RefreshTokenRotationCommitTest {

  @Autowired RefreshTokenService service;
  @Autowired AuthService authService;
  @Autowired RefreshTokenRepository repository;
  @Autowired UserRepository userRepository;

  private UUID newUser() {
    return userRepository
        .save(
            User.builder()
                .email("commit-" + System.nanoTime() + "@example.com")
                .passwordHash("h")
                .build())
        .getId();
  }

  @Test
  void replayingARotatedTokenRevokesTheFamilyDurably() {
    UUID userId = newUser();
    String first = service.issueNewFamily(userId);
    RotationResult rotated = service.rotate(first);

    // Replay the already-consumed token.
    assertThatThrownBy(() -> service.rotate(first))
        .isInstanceOf(InvalidRefreshTokenException.class);

    // The revoke must have survived the exception.
    RefreshToken successor =
        repository
            .findByTokenHash(RefreshTokenServiceImpl.hash(rotated.refreshToken()))
            .orElseThrow();
    assertThat(successor.getRevokedAt())
        .as("family revoke must commit despite the UnauthorizedException")
        .isNotNull();

    // And the stolen successor must now be useless.
    assertThatThrownBy(() -> service.rotate(rotated.refreshToken()))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void happyPathRotationCommitsTheNewToken() {
    UUID userId = newUser();
    String first = service.issueNewFamily(userId);

    RotationResult rotated = service.rotate(first);

    assertThat(repository.findByTokenHash(RefreshTokenServiceImpl.hash(rotated.refreshToken())))
        .isPresent();
    assertThat(repository.findByTokenHash(RefreshTokenServiceImpl.hash(first)).orElseThrow())
        .satisfies(row -> assertThat(row.getRotatedAt()).isNotNull());
  }

  /**
   * The same guarantee, but reached through AuthService.refresh(), which is itself @Transactional
   * and therefore becomes the outer transaction. This is the case a noRollbackFor on rotate() alone
   * does NOT cover — the outer transaction's rollback rules win and undo the revoke.
   */
  @Test
  void replayThroughAuthServiceAlsoRevokesTheFamilyDurably() {
    UUID userId = newUser();
    String first = service.issueNewFamily(userId);

    RefreshRequest good = new RefreshRequest();
    good.setRefreshToken(first);
    AuthResponse rotated = authService.refresh(good);

    RefreshRequest replay = new RefreshRequest();
    replay.setRefreshToken(first);
    assertThatThrownBy(() -> authService.refresh(replay))
        .isInstanceOf(InvalidRefreshTokenException.class);

    RefreshToken successor =
        repository
            .findByTokenHash(RefreshTokenServiceImpl.hash(rotated.getRefreshToken()))
            .orElseThrow();
    assertThat(successor.getRevokedAt())
        .as("family revoke must commit even when an outer @Transactional caller rolls back")
        .isNotNull();
  }

  @Test
  void logoutRevokesTheFamilyDurably() {
    UUID userId = newUser();
    String token = service.issueNewFamily(userId);

    service.logout(token);

    assertThat(repository.findByTokenHash(RefreshTokenServiceImpl.hash(token)).orElseThrow())
        .satisfies(row -> assertThat(row.getRevokedAt()).isNotNull());
    assertThatThrownBy(() -> service.rotate(token))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }
}
