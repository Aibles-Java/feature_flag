package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.Action;

@Data
public class CreateCustomRoleRequest {

  @NotBlank
  @Size(max = 100)
  private String name;

  @NotEmpty private Set<Action> actions;
}
