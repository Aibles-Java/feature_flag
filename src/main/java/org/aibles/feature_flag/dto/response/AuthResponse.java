package org.aibles.feature_flag.dto.response;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
  private String token;
  @Builder.Default private String type = "Bearer";
  private UUID userId;
  private String email;
}
