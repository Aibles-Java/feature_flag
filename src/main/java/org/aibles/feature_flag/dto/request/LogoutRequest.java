package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LogoutRequest {
  @NotBlank private String refreshToken;
}
