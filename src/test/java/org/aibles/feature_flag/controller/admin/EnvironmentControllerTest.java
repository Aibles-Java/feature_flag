package org.aibles.feature_flag.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EnvironmentControllerTest {

    @Mock
    private EnvironmentService environmentService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EnvironmentController(environmentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private EnvironmentResponse response(String apiKey) {
        return EnvironmentResponse.builder()
                .id(envId).name("production").projectId(projectId).apiKey(apiKey).build();
    }

    @Test
    void createReturns201WithApiKey() throws Exception {
        CreateEnvironmentRequest request = new CreateEnvironmentRequest();
        request.setProjectId(projectId);
        request.setName("production");
        when(environmentService.create(any(CreateEnvironmentRequest.class))).thenReturn(response("api-key-123"));

        mockMvc.perform(post("/api/v1/environments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value("api-key-123"));
    }

    @Test
    void createRejectsMissingNameWith400() throws Exception {
        CreateEnvironmentRequest request = new CreateEnvironmentRequest();
        request.setProjectId(projectId);

        mockMvc.perform(post("/api/v1/environments")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(environmentService, never()).create(any());
    }

    @Test
    void rotateApiKeyReturns200WithNewKey() throws Exception {
        when(environmentService.rotateApiKey(envId)).thenReturn(response("rotated-key"));

        mockMvc.perform(post("/api/v1/environments/{envId}/api-key/rotate", envId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("rotated-key"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/environments/{envId}", envId))
                .andExpect(status().isNoContent());

        verify(environmentService).delete(envId);
    }
}
