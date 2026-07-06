package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.service.EnvironmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService environmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentSecretResponse create(@Valid @RequestBody CreateEnvironmentRequest request) {
        return environmentService.create(request);
    }

    @GetMapping
    public List<EnvironmentResponse> listByProject(@RequestParam UUID projectId) {
        return environmentService.listByProject(projectId);
    }

    @GetMapping("/{envId}")
    public EnvironmentResponse get(@PathVariable UUID envId) {
        return environmentService.get(envId);
    }

    @PutMapping("/{envId}")
    public EnvironmentResponse update(@PathVariable UUID envId,
                                      @Valid @RequestBody UpdateEnvironmentRequest request) {
        return environmentService.update(envId, request);
    }

    @DeleteMapping("/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID envId) {
        environmentService.delete(envId);
    }

    @PostMapping("/{envId}/api-key/rotate")
    public EnvironmentSecretResponse rotateApiKey(@PathVariable UUID envId) {
        return environmentService.rotateApiKey(envId);
    }
}
