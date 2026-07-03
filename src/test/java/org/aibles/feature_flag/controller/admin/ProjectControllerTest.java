package org.aibles.feature_flag.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.dto.request.CreateProjectRequest;
import org.aibles.feature_flag.dto.response.ProjectResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ProjectResponse response() {
        return ProjectResponse.builder().id(projectId).name("Billing").organisationId(orgId).build();
    }

    @Test
    void createReturns201() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setOrganisationId(orgId);
        request.setName("Billing");
        when(projectService.create(any(CreateProjectRequest.class))).thenReturn(response());

        mockMvc.perform(post("/api/v1/projects")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Billing"));
    }

    @Test
    void createRejectsMissingRequiredFieldsWith400() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest();

        mockMvc.perform(post("/api/v1/projects")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(projectService, never()).create(any());
    }

    @Test
    void listByOrganisationReturns200() throws Exception {
        when(projectService.listByOrganisation(orgId)).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/v1/projects").param("organisationId", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Billing"));
    }

    @Test
    void getMapsNotFoundTo404() throws Exception {
        when(projectService.get(projectId)).thenThrow(new ResourceNotFoundException("Project", projectId));

        mockMvc.perform(get("/api/v1/projects/{projectId}", projectId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/{projectId}", projectId))
                .andExpect(status().isNoContent());

        verify(projectService).delete(projectId);
    }
}
