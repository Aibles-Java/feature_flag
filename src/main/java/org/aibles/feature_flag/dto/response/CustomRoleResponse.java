package org.aibles.feature_flag.dto.response;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.Action;

@Data
@Builder
public class CustomRoleResponse {
  private UUID id;
  private UUID organizationId;
  private String name;
  private Set<Action> actions;
}
