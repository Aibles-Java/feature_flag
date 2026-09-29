package org.aibles.feature_flag.dto.response;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
  private String accessToken;
  private String refreshToken;
  @Builder.Default private String tokenType = "Bearer";
  private long expiresIn; // access-token lifetime in seconds
  private UUID userId;
  private String email;
}
