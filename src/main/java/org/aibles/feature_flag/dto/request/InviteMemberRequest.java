package org.aibles.feature_flag.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
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

  /**
   * Project access to confer in the same call, so a member never has to exist without any.
   *
   * <p>Optional and empty by default, which keeps every existing caller working. Applied in the
   * same transaction as the membership: a half-applied invite would leave exactly the member with
   * no reach that passing these together exists to prevent.
   */
  private List<@Valid ProjectGrantSpec> projectGrants = new ArrayList<>();

  @AssertTrue(message = "Provide exactly one of userId or email")
  public boolean isExactlyOneIdentifier() {
    return (userId == null) != (email == null || email.isBlank());
  }

  /** One project's worth of access, mirroring {@code CreateProjectGrantRequest}. */
  @Data
  public static class ProjectGrantSpec {

    @NotNull private UUID projectId;

    private MemberRole role;

    private UUID customRoleId;

    @AssertTrue(message = "Provide exactly one of role or customRoleId")
    public boolean isExactlyOneRole() {
      return (role == null) != (customRoleId == null);
    }
  }
}
