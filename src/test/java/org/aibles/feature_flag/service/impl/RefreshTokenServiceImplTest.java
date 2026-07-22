package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.config.JwtProperties;
import org.aibles.feature_flag.domain.entity.RefreshToken;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.exception.InvalidRefreshTokenException;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.service.RefreshTokenService.RotationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceImplTest {

  @Mock private RefreshTokenRepository repository;
  @Mock private UserRepository userRepository;
  @Mock private JwtProperties jwtProperties;
  @Mock private RefreshTokenFamilyRevoker familyRevoker;
  @InjectMocks private RefreshTokenServiceImpl service;

  private final UUID userId = UUID.randomUUID();
  private final UUID familyId = UUID.randomUUID();

  @BeforeEach
  void stubTtl() {
    when(jwtProperties.refreshExpirationMs()).thenReturn(1_209_600_000L);
  }

  private RefreshToken activeRow(String plaintext) {
    return RefreshToken.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .familyId(familyId)
        .tokenHash(RefreshTokenServiceImpl.hash(plaintext))
        .expiresAt(LocalDateTime.now().plusDays(14))
        .build();
  }

  @Test
  void issueNewFamilyPersistsRowAndReturnsPlaintext() {
    String token = service.issueNewFamily(userId);

    assertThat(token).hasSize(64);
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getTokenHash()).isEqualTo(RefreshTokenServiceImpl.hash(token));
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    // The plaintext must never be what we store.
    assertThat(captor.getValue().getTokenHash()).isNotEqualTo(token);
  }

  @Test
  void rotateHappyPathConsumesAndIssuesNewToken() {
    String presented = "a".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(repository.consume(eq(row.getId()), any())).thenReturn(1);
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(true).build()));

    RotationResult result = service.rotate(presented);

    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.refreshToken()).hasSize(64).isNotEqualTo(presented);
    verify(repository).consume(eq(row.getId()), any());
    verify(familyRevoker, never()).revoke(any(), any());
  }

  @Test
  void rotateStaysInTheSameFamily() {
    String presented = "z".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(repository.consume(eq(row.getId()), any())).thenReturn(1);
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(true).build()));

    service.rotate(presented);

    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getFamilyId()).isEqualTo(familyId);
  }

  @Test
  void rotateOfAlreadyRotatedTokenRevokesFamily() {
    String presented = "b".repeat(64);
    RefreshToken row = activeRow(presented);
    row.setRotatedAt(LocalDateTime.now().minusMinutes(1));
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(familyRevoker).revoke(eq(familyId), any());
  }

  @Test
  void rotateLosingTheConcurrentConsumeRevokesFamily() {
    String presented = "c".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(true).build()));
    when(repository.consume(eq(row.getId()), any())).thenReturn(0); // someone else won

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(familyRevoker).revoke(eq(familyId), any());
  }

  @Test
  void rotateOfExpiredTokenIsRejected() {
    String presented = "d".repeat(64);
    RefreshToken row = activeRow(presented);
    row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void rotateOfRevokedTokenIsRejected() {
    String presented = "r".repeat(64);
    RefreshToken row = activeRow(presented);
    row.setRevokedAt(LocalDateTime.now().minusMinutes(1));
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(repository, never()).consume(any(), any());
  }

  @Test
  void rotateForDisabledUserRevokesFamily() {
    String presented = "e".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(User.builder().id(userId).enabled(false).build()));

    assertThatThrownBy(() -> service.rotate(presented))
        .isInstanceOf(InvalidRefreshTokenException.class);
    verify(familyRevoker).revoke(eq(familyId), any());
    // The enabled-check runs BEFORE consume() (commit 433266f) so the disabled-user path never
    // holds consume()'s row lock when the REQUIRES_NEW revoke fires. Asserting consume is skipped
    // locks in that ordering against a future re-reordering that would reintroduce the self-deadlock.
    verify(repository, never()).consume(any(), any());
  }

  @Test
  void rotateOfUnknownTokenIsRejected() {
    when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rotate("f".repeat(64)))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  @Test
  void logoutRevokesFamilyWhenTokenKnown() {
    String presented = "g".repeat(64);
    RefreshToken row = activeRow(presented);
    when(repository.findByTokenHash(RefreshTokenServiceImpl.hash(presented)))
        .thenReturn(Optional.of(row));

    service.logout(presented);

    verify(familyRevoker).revoke(eq(familyId), any());
  }

  @Test
  void logoutOfUnknownTokenIsSilent() {
    when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

    service.logout("h".repeat(64)); // no throw, no revoke

    verify(familyRevoker, never()).revoke(any(), any());
  }
}
