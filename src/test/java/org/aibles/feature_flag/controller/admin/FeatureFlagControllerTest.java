package org.aibles.feature_flag.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FeatureFlagControllerTest {

    @Mock
    private FeatureFlagService featureFlagService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID projectId = UUID.randomUUID();
    private final UUID flagId = UUID.randomUUID();
    private final UUID envId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FeatureFlagController(featureFlagService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private CreateFeatureFlagRequest validCreate() {
        CreateFeatureFlagRequest request = new CreateFeatureFlagRequest();
        request.setProjectId(projectId);
        request.setName("Dark Mode");
        request.setKey("dark-mode");
        request.setValueType(FlagValueType.BOOLEAN);
        return request;
    }

    private FeatureFlagResponse response() {
        return FeatureFlagResponse.builder()
                .id(flagId).name("Dark Mode").key("dark-mode")
                .valueType(FlagValueType.BOOLEAN).archived(false).projectId(projectId).build();
    }

    @Test
    void createReturns201WithBody() throws Exception {
        when(featureFlagService.create(any(CreateFeatureFlagRequest.class))).thenReturn(response());

        mockMvc.perform(post("/api/v1/flags")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validCreate())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("dark-mode"));
    }

    @Test
    void createRejectsInvalidKeyPatternWith400() throws Exception {
        CreateFeatureFlagRequest invalid = validCreate();
        invalid.setKey("Dark Mode!");

        mockMvc.perform(post("/api/v1/flags")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        verify(featureFlagService, never()).create(any());
    }

    @Test
    void getMapsNotFoundTo404() throws Exception {
        when(featureFlagService.get(flagId)).thenThrow(new ResourceNotFoundException("FeatureFlag", flagId));

        mockMvc.perform(get("/api/v1/flags/{flagId}", flagId))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateReturns200() throws Exception {
        when(featureFlagService.update(eq(flagId), any(UpdateFeatureFlagRequest.class))).thenReturn(response());
        UpdateFeatureFlagRequest request = new UpdateFeatureFlagRequest();
        request.setName("Dark Mode v2");

        mockMvc.perform(put("/api/v1/flags/{flagId}", flagId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void archiveReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/flags/{flagId}", flagId))
                .andExpect(status().isNoContent());

        verify(featureFlagService).archive(flagId);
    }

    @Test
    void unarchiveReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/flags/{flagId}/unarchive", flagId))
                .andExpect(status().isNoContent());

        verify(featureFlagService).unarchive(flagId);
    }

    @Test
    void listByProjectReturns200() throws Exception {
        when(featureFlagService.listByProject(projectId)).thenReturn(java.util.List.of(response()));

        mockMvc.perform(get("/api/v1/flags").param("projectId", projectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("dark-mode"));
    }

    @Test
    void getStateMapsNotFoundTo404() throws Exception {
        when(featureFlagService.getState(flagId, envId))
                .thenThrow(new ResourceNotFoundException("Flag state not found for this environment"));

        mockMvc.perform(get("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStateReturns200() throws Exception {
        when(featureFlagService.updateState(eq(flagId), eq(envId), any(UpdateFlagStateRequest.class)))
                .thenReturn(FlagStateResponse.builder()
                        .flagId(flagId).environmentId(envId).enabled(true).value("on").rolloutPercent(50).build());
        UpdateFlagStateRequest request = new UpdateFlagStateRequest();
        request.setEnabled(true);
        request.setValue("on");
        request.setRolloutPercent(50);

        mockMvc.perform(put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rolloutPercent").value(50));
    }

    @Test
    void updateStateRejectsOutOfRangeRolloutWith400() throws Exception {
        UpdateFlagStateRequest request = new UpdateFlagStateRequest();
        request.setEnabled(true);
        request.setRolloutPercent(150);

        mockMvc.perform(put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(featureFlagService, never()).updateState(any(), any(), any());
    }
}
