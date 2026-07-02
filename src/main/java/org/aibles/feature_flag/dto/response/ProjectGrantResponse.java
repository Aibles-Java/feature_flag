package org.aibles.feature_flag.dto.response;

import lombok.Builder;
import lombok.Data;
import org.aibles.feature_flag.domain.enums.MemberRole;

import java.util.UUID;

@Data
@Builder
public class ProjectGrantResponse {
    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private MemberRole role;
    private UUID customRoleId;
    private String customRoleName;
}
