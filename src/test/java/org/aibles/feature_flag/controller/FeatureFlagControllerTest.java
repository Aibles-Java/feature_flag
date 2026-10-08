package org.aibles.feature_flag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.PaginationConfig;
import org.aibles.feature_flag.controller.admin.FeatureFlagController;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.request.CreateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFeatureFlagRequest;
import org.aibles.feature_flag.dto.request.UpdateFlagStateRequest;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class FeatureFlagControllerTest {

  @Mock FeatureFlagService featureFlagService;

  MockMvc mockMvc;
  ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    PageableHandlerMethodArgumentResolver pageableResolver =
        new PageableHandlerMethodArgumentResolver();
    pageableResolver.setMaxPageSize(PaginationConfig.MAX_PAGE_SIZE);
    pageableResolver.setFallbackPageable(PageRequest.of(0, PaginationConfig.DEFAULT_PAGE_SIZE));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FeatureFlagController(featureFlagService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(pageableResolver)
            .setValidator(validator)
            .build();
  }

  @Test
  void create_returns201_whenRequestIsValid() throws Exception {
    UUID projectId = UUID.randomUUID();
    FeatureFlagResponse response =
        FeatureFlagResponse.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .name("Dark Mode")
            .key("dark-mode")
            .valueType(FlagValueType.BOOLEAN)
            .archived(false)
            .build();
    when(featureFlagService.create(any())).thenReturn(response);

    CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
    req.setProjectId(projectId);
    req.setName("Dark Mode");
    req.setKey("dark-mode");
    req.setValueType(FlagValueType.BOOLEAN);

    mockMvc
        .perform(
            post("/api/v1/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("dark-mode"));
  }

  @Test
  void create_returns400_whenKeyContainsUppercase() throws Exception {
    CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
    req.setProjectId(UUID.randomUUID());
    req.setName("Bad Flag");
    req.setKey("BadKey");
    req.setValueType(FlagValueType.BOOLEAN);

    mockMvc
        .perform(
            post("/api/v1/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void create_returns400_whenProjectIdIsNull() throws Exception {
    CreateFeatureFlagRequest req = new CreateFeatureFlagRequest();
    req.setName("Flag");
    req.setKey("flag");
    req.setValueType(FlagValueType.BOOLEAN);

    mockMvc
        .perform(
            post("/api/v1/flags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listByProject_returns200_withFlagList() throws Exception {
    UUID projectId = UUID.randomUUID();
    FeatureFlagResponse flag =
        FeatureFlagResponse.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .name("Feature")
            .key("feature")
            .valueType(FlagValueType.BOOLEAN)
            .build();
    when(featureFlagService.listByProject(eq(projectId), any()))
        .thenReturn(new PageImpl<>(List.of(flag)));

    mockMvc
        .perform(get("/api/v1/flags").param("projectId", projectId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].key").value("feature"));
  }

  @Test
  void archive_returns204_whenFlagExists() throws Exception {
    UUID flagId = UUID.randomUUID();
    doNothing().when(featureFlagService).archive(flagId);

    mockMvc.perform(delete("/api/v1/flags/{flagId}", flagId)).andExpect(status().isNoContent());
  }

  @Test
  void unarchive_returns204_whenFlagExists() throws Exception {
    UUID flagId = UUID.randomUUID();
    doNothing().when(featureFlagService).unarchive(flagId);

    mockMvc
        .perform(post("/api/v1/flags/{flagId}/unarchive", flagId))
        .andExpect(status().isNoContent());
  }

  @Test
  void updateState_returns200_withUpdatedState() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    FlagStateResponse stateResponse =
        FlagStateResponse.builder()
            .flagId(flagId)
            .environmentId(envId)
            .enabled(true)
            .value("true")
            .rolloutPercent(100)
            .build();
    when(featureFlagService.updateState(eq(flagId), eq(envId), any())).thenReturn(stateResponse);

    UpdateFlagStateRequest req = new UpdateFlagStateRequest();
    req.setEnabled(true);
    req.setValue("true");

    mockMvc
        .perform(
            put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void updateState_deserializesClearValueAndPassesItToService() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    when(featureFlagService.updateState(eq(flagId), eq(envId), any()))
        .thenReturn(FlagStateResponse.builder().flagId(flagId).environmentId(envId).build());

    mockMvc
        .perform(
            put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"clearValue\":true}"))
        .andExpect(status().isOk());

    org.mockito.ArgumentCaptor<UpdateFlagStateRequest> captor =
        org.mockito.ArgumentCaptor.forClass(UpdateFlagStateRequest.class);
    verify(featureFlagService).updateState(eq(flagId), eq(envId), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getClearValue()).isTrue();
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getValue()).isNull();
  }

  @Test
  void updateState_returns400_whenServiceRejectsClearValueWithValue() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    when(featureFlagService.updateState(eq(flagId), eq(envId), any()))
        .thenThrow(
            new org.aibles.feature_flag.exception.InvalidRequestException(
                "clearValue cannot be combined with value"));

    mockMvc
        .perform(
            put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"clearValue\":true,\"value\":\"y\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateState_acceptsRolloutPercentAtBothBounds() throws Exception {
    for (int percent : new int[] {0, 100}) {
      UUID flagId = UUID.randomUUID();
      UUID envId = UUID.randomUUID();
      when(featureFlagService.updateState(eq(flagId), eq(envId), any()))
          .thenReturn(
              FlagStateResponse.builder()
                  .flagId(flagId)
                  .environmentId(envId)
                  .enabled(true)
                  .rolloutPercent(percent)
                  .build());

      UpdateFlagStateRequest req = new UpdateFlagStateRequest();
      req.setEnabled(true);
      req.setRolloutPercent(percent);

      mockMvc
          .perform(
              put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(req)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.rolloutPercent").value(percent));
    }
  }

  /**
   * `@Min(0) @Max(100)` on UpdateFlagStateRequest.rolloutPercent must reject out-of-range values.
   */
  @Test
  void updateState_returns400_whenRolloutPercentOutOfRange() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();

    for (int invalid : new int[] {-1, 101}) {
      UpdateFlagStateRequest req = new UpdateFlagStateRequest();
      req.setEnabled(true);
      req.setRolloutPercent(invalid);

      mockMvc
          .perform(
              put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(req)))
          .andExpect(status().isBadRequest());
    }

    verify(featureFlagService, never()).updateState(any(), any(), any());
  }

  /**
   * S-0.4 AC1 at the HTTP layer: the shared validator's InvalidRequestException, raised from the
   * service on PUT state, must surface as 400 ProblemDetail via GlobalExceptionHandler, and the
   * rejected value must not be echoed back.
   */
  @Test
  void updateState_returns400_whenValueDoesNotMatchValueType() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    when(featureFlagService.updateState(eq(flagId), eq(envId), any()))
        .thenAnswer(
            inv -> {
              UpdateFlagStateRequest r = inv.getArgument(2);
              org.aibles.feature_flag.util.FlagValueValidator.validate(
                  FlagValueType.INTEGER, r.getValue());
              return null;
            });

    mockMvc
        .perform(
            put("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"value\":\"s3cr3t-abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("s3cr3t-abc"))));
  }

  @Test
  void get_returns200_withFlag() throws Exception {
    UUID flagId = UUID.randomUUID();
    FeatureFlagResponse response =
        FeatureFlagResponse.builder()
            .id(flagId)
            .name("Dark Mode")
            .key("dark-mode")
            .valueType(FlagValueType.BOOLEAN)
            .build();
    when(featureFlagService.get(flagId)).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/flags/{flagId}", flagId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value("dark-mode"));
  }

  @Test
  void update_returns200_withUpdatedFlag() throws Exception {
    UUID flagId = UUID.randomUUID();
    FeatureFlagResponse response =
        FeatureFlagResponse.builder()
            .id(flagId)
            .name("Renamed")
            .key("dark-mode")
            .valueType(FlagValueType.BOOLEAN)
            .build();
    when(featureFlagService.update(eq(flagId), any())).thenReturn(response);

    UpdateFeatureFlagRequest req = new UpdateFeatureFlagRequest();
    req.setName("Renamed");

    mockMvc
        .perform(
            put("/api/v1/flags/{flagId}", flagId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void listArchived_returns200_withArchivedFlags() throws Exception {
    UUID projectId = UUID.randomUUID();
    FeatureFlagResponse archived =
        FeatureFlagResponse.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .name("Old")
            .key("old")
            .valueType(FlagValueType.BOOLEAN)
            .archived(true)
            .build();
    when(featureFlagService.listArchivedByProject(eq(projectId), any()))
        .thenReturn(new PageImpl<>(List.of(archived)));

    mockMvc
        .perform(get("/api/v1/flags/archived").param("projectId", projectId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].key").value("old"))
        .andExpect(jsonPath("$.content[0].archived").value(true));
  }

  @Test
  void getState_returns200_withFlagState() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    FlagStateResponse stateResponse =
        FlagStateResponse.builder().flagId(flagId).environmentId(envId).enabled(false).build();
    when(featureFlagService.getState(flagId, envId)).thenReturn(stateResponse);

    mockMvc
        .perform(get("/api/v1/flags/{flagId}/environments/{envId}", flagId, envId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void listStates_returns200_withAllStatesOfFlag() throws Exception {
    UUID flagId = UUID.randomUUID();
    UUID envId = UUID.randomUUID();
    when(featureFlagService.listStates(flagId))
        .thenReturn(
            List.of(
                FlagStateResponse.builder()
                    .flagId(flagId)
                    .environmentId(envId)
                    .enabled(true)
                    .rolloutPercent(50)
                    .version(3L)
                    .build()));

    mockMvc
        .perform(get("/api/v1/flags/{flagId}/environments", flagId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].environmentId").value(envId.toString()))
        .andExpect(jsonPath("$[0].version").value(3))
        .andExpect(jsonPath("$[0].rolloutPercent").value(50));
  }
}
