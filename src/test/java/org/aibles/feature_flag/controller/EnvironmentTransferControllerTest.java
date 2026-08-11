package org.aibles.feature_flag.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.EnvironmentTransferController;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.ImportConflictStrategy;
import org.aibles.feature_flag.domain.enums.ImportOutcome;
import org.aibles.feature_flag.dto.request.CloneEnvironmentRequest;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.exception.InvalidRequestException;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class EnvironmentTransferControllerTest {

  @Mock EnvironmentTransferService environmentTransferService;

  MockMvc mockMvc;
  ObjectMapper objectMapper = new ObjectMapper();

  UUID envId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new EnvironmentTransferController(environmentTransferService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void clone_returns201_withTheOneTimeApiKey() throws Exception {
    EnvironmentSecretResponse response =
        EnvironmentSecretResponse.builder()
            .id(UUID.randomUUID())
            .name("production-copy")
            .projectId(UUID.randomUUID())
            .apiKey("a".repeat(64))
            .build();
    when(environmentTransferService.clone(eq(envId), any())).thenReturn(response);

    CloneEnvironmentRequest request = new CloneEnvironmentRequest();
    request.setName("production-copy");

    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/clone", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("production-copy"))
        .andExpect(jsonPath("$.apiKey").value("a".repeat(64)));
  }

  @Test
  void clone_returns400_whenNameIsBlank() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/clone", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
    verify(environmentTransferService, never()).clone(any(), any());
  }

  @Test
  void export_returns200_withSchemaVersionedEnvelope() throws Exception {
    when(environmentTransferService.export(envId))
        .thenReturn(
            EnvironmentSnapshotResponse.builder()
                .schemaVersion(EnvironmentSnapshotResponse.SCHEMA_VERSION)
                .environmentId(envId)
                .environmentName("production")
                .flags(
                    List.of(
                        EnvironmentSnapshotResponse.FlagSnapshot.builder()
                            .key("checkout-v2")
                            .valueType(FlagValueType.BOOLEAN)
                            .enabled(true)
                            .rolloutPercent(100)
                            .build()))
                .build());

    mockMvc
        .perform(get("/api/v1/environments/{envId}/export", envId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schemaVersion").value(1))
        .andExpect(jsonPath("$.flags[0].key").value("checkout-v2"))
        .andExpect(jsonPath("$.flags[0].enabled").value(true));
  }

  @Test
  void import_returns200_andForwardsDryRunAndStrategy() throws Exception {
    when(environmentTransferService.importSnapshot(eq(envId), any()))
        .thenReturn(
            ImportResultResponse.builder()
                .dryRun(true)
                .conflictStrategy(ImportConflictStrategy.OVERWRITE)
                .schemaVersion(1)
                .summary(
                    ImportResultResponse.Summary.builder()
                        .created(0)
                        .updated(1)
                        .unchanged(0)
                        .skipped(0)
                        .build())
                .items(
                    List.of(
                        ImportResultResponse.ItemResult.builder()
                            .flagKey("checkout-v2")
                            .outcome(ImportOutcome.UPDATED)
                            .build()))
                .build());

    String body =
        objectMapper.writeValueAsString(
            Map.of(
                "dryRun",
                true,
                "conflictStrategy",
                "OVERWRITE",
                "snapshot",
                Map.of(
                    "schemaVersion",
                    1,
                    "flags",
                    List.of(
                        Map.of(
                            "key", "checkout-v2",
                            "valueType", "BOOLEAN",
                            "enabled", true)))));

    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/import", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.summary.updated").value(1))
        .andExpect(jsonPath("$.items[0].outcome").value("UPDATED"));

    ArgumentCaptor<ImportEnvironmentRequest> captor =
        ArgumentCaptor.forClass(ImportEnvironmentRequest.class);
    verify(environmentTransferService).importSnapshot(eq(envId), captor.capture());
    assertRequest(captor.getValue());
  }

  @Test
  void import_returns400_whenSnapshotIsMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/import", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true}"))
        .andExpect(status().isBadRequest());
    verify(environmentTransferService, never()).importSnapshot(any(), any());
  }

  @Test
  void import_returns400_whenAFlagEntryHasNoKey() throws Exception {
    String body = "{\"snapshot\":{\"schemaVersion\":1,\"flags\":[{\"valueType\":\"BOOLEAN\"}]}}";

    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/import", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verify(environmentTransferService, never()).importSnapshot(any(), any());
  }

  /** An unsupported schema version is a content-level rejection, so it maps to 400 not 500. */
  @Test
  void import_returns400_whenSchemaVersionIsUnsupported() throws Exception {
    when(environmentTransferService.importSnapshot(eq(envId), any()))
        .thenThrow(new InvalidRequestException("Unsupported snapshot schemaVersion: 99"));

    mockMvc
        .perform(
            post("/api/v1/environments/{envId}/import", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"snapshot\":{\"schemaVersion\":99,\"flags\":[{\"key\":\"a\",\"valueType\":\"BOOLEAN\"}]}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Unsupported snapshot schemaVersion: 99"));
  }

  private void assertRequest(ImportEnvironmentRequest request) {
    assertThat(request.isDryRun()).isTrue();
    assertThat(request.getConflictStrategy()).isEqualTo(ImportConflictStrategy.OVERWRITE);
    assertThat(request.getSnapshot().getFlags())
        .singleElement()
        .satisfies(f -> assertThat(f.getKey()).isEqualTo("checkout-v2"));
  }
}
