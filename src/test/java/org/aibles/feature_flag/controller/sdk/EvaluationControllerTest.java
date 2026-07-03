package org.aibles.feature_flag.controller.sdk;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.service.EvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EvaluationControllerTest {

    @Mock
    private EvaluationService evaluationService;

    private MockMvc mockMvc;
    private Environment environment;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EvaluationController(evaluationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        environment = Environment.builder().id(UUID.randomUUID()).name("production").build();

        authentication = new UsernamePasswordAuthenticationToken(environment, null);
    }

    @Test
    void getAllFlagsReturnsEvaluatedFlags() throws Exception {
        when(evaluationService.getAllFlags(eq(environment), isNull()))
                .thenReturn(List.of(FlagEvaluationResponse.builder()
                        .flagKey("dark-mode").enabled(true).value("on")
                        .valueType(FlagValueType.STRING).rolloutPercent(100).build()));

        mockMvc.perform(get("/api/v1/sdk/flags").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flagKey").value("dark-mode"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void getAllFlagsPassesIdentifierThrough() throws Exception {
        when(evaluationService.getAllFlags(eq(environment), eq("user-42")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sdk/flags").principal(authentication).param("identifier", "user-42"))
                .andExpect(status().isOk());
    }

    @Test
    void getFlagReturnsSingleEvaluation() throws Exception {
        when(evaluationService.getFlag(eq(environment), eq("dark-mode"), isNull()))
                .thenReturn(FlagEvaluationResponse.builder()
                        .flagKey("dark-mode").enabled(true).value("on")
                        .valueType(FlagValueType.STRING).rolloutPercent(100).build());

        mockMvc.perform(get("/api/v1/sdk/flags/{flagKey}", "dark-mode").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("dark-mode"));
    }

    @Test
    void getFlagMapsUnknownKeyTo404() throws Exception {
        when(evaluationService.getFlag(eq(environment), eq("missing"), isNull()))
                .thenThrow(new ResourceNotFoundException("Flag not found with key: missing"));

        mockMvc.perform(get("/api/v1/sdk/flags/{flagKey}", "missing").principal(authentication))
                .andExpect(status().isNotFound());
    }
}
