package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {
  @NotBlank private String refreshToken;
}
