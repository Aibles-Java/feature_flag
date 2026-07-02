package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.MemberRole;

import java.util.UUID;

/** Provide exactly one of {@code role} or {@code customRoleId}. */
@Data
public class CreateProjectGrantRequest {

    @NotNull
    private UUID userId;

    private MemberRole role;

    private UUID customRoleId;
}
