package org.aibles.feature_flag.dto.response;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.MemberRole;

@Data
@Builder
public class MemberResponse {
  private UUID userId;
  private String email;
  private String firstName;
  private String lastName;
  private MemberRole role;
}
