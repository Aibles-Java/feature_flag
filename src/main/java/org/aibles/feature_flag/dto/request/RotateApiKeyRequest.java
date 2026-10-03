package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class RotateApiKeyRequest {

  /**
   * How long the old key keeps working after the new one is issued. {@code 0} revokes it
   * immediately, reproducing the pre-multi-key hard cutover. Anything above that is the point of
   * the feature: both keys authenticate while the SDK fleet is redeployed.
   */
  @Min(0)
  @Max(720)
  private int graceHours = 0;
}
