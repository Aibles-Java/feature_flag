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
import org.aibles.feature_flag.controller.admin.EnvironmentController;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.EnvironmentService;
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
class EnvironmentControllerTest {

  @Mock EnvironmentService environmentService;

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
        MockMvcBuilders.standaloneSetup(new EnvironmentController(environmentService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(pageableResolver)
            .setValidator(validator)
            .build();
  }

  @Test
  void create_returns201_withEnvironmentResponse() throws Exception {
    UUID projectId = UUID.randomUUID();
    EnvironmentSecretResponse response =
        EnvironmentSecretResponse.builder()
            .id(UUID.randomUUID())
            .name("Production")
            .projectId(projectId)
            .apiKey("a".repeat(64))
            .build();
    when(environmentService.create(any())).thenReturn(response);

    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(projectId);
    req.setName("Production");

    mockMvc
        .perform(
            post("/api/v1/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Production"))
        .andExpect(jsonPath("$.apiKey").exists());
  }

  @Test
  void listByProject_returns200_withEnvironmentList() throws Exception {
    UUID projectId = UUID.randomUUID();
    EnvironmentResponse response =
        EnvironmentResponse.builder()
            .id(UUID.randomUUID())
            .name("prod")
            .projectId(projectId)
            .build();
    when(environmentService.listByProject(eq(projectId), any()))
        .thenReturn(new PageImpl<>(List.of(response)));

    mockMvc
        .perform(get("/api/v1/environments").param("projectId", projectId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("prod"));
  }

  @Test
  void get_returns200_withEnvironment() throws Exception {
    UUID envId = UUID.randomUUID();
    EnvironmentResponse response = EnvironmentResponse.builder().id(envId).name("prod").build();
    when(environmentService.get(envId)).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/environments/{envId}", envId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(envId.toString()));
  }

  @Test
  void delete_returns204() throws Exception {
    UUID envId = UUID.randomUUID();
    doNothing().when(environmentService).delete(envId);

    mockMvc
        .perform(delete("/api/v1/environments/{envId}", envId))
        .andExpect(status().isNoContent());
  }

  @Test
  void rotateApiKey_returns200_withNewKey() throws Exception {
    UUID envId = UUID.randomUUID();
    EnvironmentSecretResponse response =
        EnvironmentSecretResponse.builder().id(envId).name("prod").apiKey("b".repeat(64)).build();
    when(environmentService.rotateApiKey(envId)).thenReturn(response);

    mockMvc
        .perform(post("/api/v1/environments/{envId}/api-key/rotate", envId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").value("b".repeat(64)));
  }

  @Test
  void update_returns200_withUpdatedEnvironment() throws Exception {
    UUID envId = UUID.randomUUID();
    EnvironmentResponse response = EnvironmentResponse.builder().id(envId).name("staging").build();
    when(environmentService.update(eq(envId), any())).thenReturn(response);

    UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
    req.setName("staging");

    mockMvc
        .perform(
            put("/api/v1/environments/{envId}", envId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("staging"));
  }

  @Test
  void create_returns400_whenNameIsBlank() throws Exception {
    CreateEnvironmentRequest req = new CreateEnvironmentRequest();
    req.setProjectId(UUID.randomUUID());
    req.setName("");

    mockMvc
        .perform(
            post("/api/v1/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }
}
