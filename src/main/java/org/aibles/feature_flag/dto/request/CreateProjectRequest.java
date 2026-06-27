package org.aibles.feature_flag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateProjectRequest {
    @NotNull
    private UUID organisationId;

    @NotBlank @Size(max = 255)
    private String name;

    private String description;
}
