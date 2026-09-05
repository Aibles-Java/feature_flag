package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.MemberRole;

/**
 * Identifies the account to add by <em>either</em> its id or its email address, never both.
 *
 * <p>Email exists because there is no user-directory endpoint: a UI has no way to turn the address
 * an admin actually knows into a {@code userId}, which forced callers to paste a raw UUID.
 * Resolving it here keeps the directory unexposed — an admin can add someone whose address they
 * already know, but cannot enumerate accounts.
 *
 * <p>{@code userId} is kept for callers that already hold one, so this stays backward compatible.
 */
@Data
public class InviteMemberRequest {

  private UUID userId;

  @Email private String email;

  @NotNull private MemberRole role;

  @AssertTrue(message = "Provide exactly one of userId or email")
  public boolean isExactlyOneIdentifier() {
    return (userId == null) != (email == null || email.isBlank());
  }
}
