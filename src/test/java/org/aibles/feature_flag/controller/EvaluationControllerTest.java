package org.aibles.feature_flag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.controller.sdk.EvaluationController;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.security.ApiKeyAuthenticationToken;
import org.aibles.feature_flag.service.EvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class EvaluationControllerTest {

    @Mock EvaluationService evaluationService;

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvaluationController(evaluationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Environment testEnvironment() {
        Project project = Project.builder().id(UUID.randomUUID()).name("proj").build();
        return Environment.builder()
                .id(UUID.randomUUID()).project(project).name("prod").apiKeyHash("test-key").build();
    }

    // Sets request.userPrincipal so PrincipalMethodArgumentResolver resolves Authentication parameter
    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, Environment env) {
        ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(env);
        return builder.with(request -> { request.setUserPrincipal(auth); return request; });
    }

    @Test
    void getAllFlags_returns200_withFlagList() throws Exception {
        Environment env = testEnvironment();
        FlagEvaluationResponse flag = FlagEvaluationResponse.builder()
                .flagKey("dark-mode").enabled(true).valueType(FlagValueType.BOOLEAN).build();
        when(evaluationService.getAllFlags(any(), any())).thenReturn(List.of(flag));

        mockMvc.perform(withAuth(get("/api/v1/sdk/flags"), env))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flagKey").value("dark-mode"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void getAllFlags_returns200_withEmptyList_whenNoFlags() throws Exception {
        Environment env = testEnvironment();
        when(evaluationService.getAllFlags(any(), any())).thenReturn(List.of());

        mockMvc.perform(withAuth(get("/api/v1/sdk/flags"), env))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void getFlag_returns200_withFlagEvaluation() throws Exception {
        Environment env = testEnvironment();
        FlagEvaluationResponse response = FlagEvaluationResponse.builder()
                .flagKey("beta-feature").enabled(false).valueType(FlagValueType.BOOLEAN).build();
        when(evaluationService.getFlag(any(), eq("beta-feature"), any())).thenReturn(response);

        mockMvc.perform(withAuth(get("/api/v1/sdk/flags/{flagKey}", "beta-feature"), env))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("beta-feature"))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void getAllFlags_passesIdentifierToService() throws Exception {
        Environment env = testEnvironment();
        FlagEvaluationResponse flag = FlagEvaluationResponse.builder()
                .flagKey("flag").enabled(true).valueType(FlagValueType.BOOLEAN).build();
        when(evaluationService.getAllFlags(any(), eq("user-42"))).thenReturn(List.of(flag));

        mockMvc.perform(withAuth(get("/api/v1/sdk/flags").param("identifier", "user-42"), env))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flagKey").value("flag"));
    }
}
