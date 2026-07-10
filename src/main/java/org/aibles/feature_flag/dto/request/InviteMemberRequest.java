package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.MemberRole;

@Data
public class InviteMemberRequest {
  @NotNull private UUID userId;

  @NotNull private MemberRole role;
}
