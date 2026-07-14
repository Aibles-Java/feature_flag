package org.aibles.feature_flag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.ProjectController;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.request.UpdateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

  @Mock ProjectService projectService;

  MockMvc mockMvc;
  ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void create_returns201_withProjectResponse() throws Exception {
    UUID orgId = UUID.randomUUID();
    ProjectResponse response =
        ProjectResponse.builder()
            .id(UUID.randomUUID())
            .name("Backend")
            .organisationId(orgId)
            .build();
    when(projectService.create(any())).thenReturn(response);

    CreateProjectRequest req = new CreateProjectRequest();
    req.setOrganisationId(orgId);
    req.setName("Backend");

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Backend"));
  }

  @Test
  void listByOrganisation_returns200_withProjectList() throws Exception {
    UUID orgId = UUID.randomUUID();
    ProjectResponse response =
        ProjectResponse.builder()
            .id(UUID.randomUUID())
            .name("Backend")
            .organisationId(orgId)
            .build();
    when(projectService.listByOrganisation(orgId)).thenReturn(List.of(response));

    mockMvc
        .perform(get("/api/v1/projects").param("organisationId", orgId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Backend"));
  }

  @Test
  void get_returns200_withProject() throws Exception {
    UUID projectId = UUID.randomUUID();
    ProjectResponse response = ProjectResponse.builder().id(projectId).name("Backend").build();
    when(projectService.get(projectId)).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/projects/{projectId}", projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(projectId.toString()));
  }

  @Test
  void update_returns200_withUpdatedProject() throws Exception {
    UUID projectId = UUID.randomUUID();
    ProjectResponse response = ProjectResponse.builder().id(projectId).name("Renamed").build();
    when(projectService.update(eq(projectId), any())).thenReturn(response);

    UpdateProjectRequest req = new UpdateProjectRequest();
    req.setName("Renamed");

    mockMvc
        .perform(
            put("/api/v1/projects/{projectId}", projectId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void delete_returns204() throws Exception {
    UUID projectId = UUID.randomUUID();
    doNothing().when(projectService).delete(projectId);

    mockMvc
        .perform(delete("/api/v1/projects/{projectId}", projectId))
        .andExpect(status().isNoContent());
  }

  @Test
  void create_returns400_whenOrganisationIdIsNull() throws Exception {
    CreateProjectRequest req = new CreateProjectRequest();
    req.setName("Backend");

    mockMvc
        .perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }
}
