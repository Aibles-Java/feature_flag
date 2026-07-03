package org.aibles.feature_flag.controller.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private RegisterRequest validRegister() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("password123");
        request.setFirstName("Ada");
        request.setLastName("Lovelace");
        return request;
    }

    @Test
    void registerReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validRegister())))
                .andExpect(status().isCreated());

        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void registerRejectsInvalidBodyWith400() throws Exception {
        RegisterRequest invalid = new RegisterRequest();
        invalid.setEmail("not-an-email");
        invalid.setPassword("short");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());

        verify(authService, never()).register(any());
    }

    @Test
    void registerMapsDuplicateEmailTo409() throws Exception {
        doThrow(new DuplicateResourceException("Email already in use: new@example.com"))
                .when(authService).register(any(RegisterRequest.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validRegister())))
                .andExpect(status().isConflict());
    }

    @Test
    void loginReturnsTokenPayload() throws Exception {
        UUID userId = UUID.randomUUID();
        LoginRequest request = new LoginRequest();
        request.setEmail("member@example.com");
        request.setPassword("password123");
        when(authService.login(any(LoginRequest.class))).thenReturn(AuthResponse.builder()
                .token("jwt-token").userId(userId).email("member@example.com").build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.email").value("member@example.com"))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }
}
