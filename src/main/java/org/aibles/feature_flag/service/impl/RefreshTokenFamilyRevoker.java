package org.aibles.feature_flag.service.impl;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoking a token family is a security control that must survive the exception which reports it.
 *
 * <p>Reuse detection revokes the family and then throws {@code UnauthorizedException}, an unchecked
 * exception that rolls back the ambient transaction by default — silently undoing the revoke.
 * Marking the throwing method {@code noRollbackFor} only fixes the innermost transaction: any
 * caller that is itself {@code @Transactional} (e.g. {@code AuthServiceImpl.refresh}) becomes the
 * outer transaction whose rollback rules win, and the hole reopens.
 *
 * <p>Running the revoke in its own {@code REQUIRES_NEW} transaction makes it commit independently
 * of whatever the caller's transaction decides to do, so the guarantee cannot be undone by adding
 * {@code @Transactional} somewhere up the stack later.
 *
 * <p>Deliberately a separate bean: {@code REQUIRES_NEW} is proxy-based, so a self-invocation from
 * within {@code RefreshTokenServiceImpl} would silently do nothing.
 */
@Component
@RequiredArgsConstructor
class RefreshTokenFamilyRevoker {

  private final RefreshTokenRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  int revoke(UUID familyId, LocalDateTime now) {
    return repository.revokeFamily(familyId, now);
  }
}
