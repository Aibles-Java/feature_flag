package org.aibles.feature_flag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.aibles.feature_flag.controller.auth.AuthController;
import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.AuthService;
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
class AuthControllerTest {

  @Mock AuthService authService;

  MockMvc mockMvc;
  ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AuthController(authService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void register_returns201_whenRequestIsValid() throws Exception {
    doNothing().when(authService).register(any());

    RegisterRequest req = new RegisterRequest();
    req.setEmail("alice@example.com");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated());
  }

  @Test
  void register_returns400_whenEmailIsInvalid() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setEmail("not-an-email");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void register_returns400_whenEmailIsBlank() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setEmail("");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void register_returns400_whenPasswordTooShort() throws Exception {
    RegisterRequest req = new RegisterRequest();
    req.setEmail("alice@example.com");
    req.setPassword("short");

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void login_returns200_withToken_whenCredentialsAreValid() throws Exception {
    AuthResponse response =
        AuthResponse.builder()
            .token("jwt-token")
            .userId(UUID.randomUUID())
            .email("alice@example.com")
            .build();
    when(authService.login(any())).thenReturn(response);

    LoginRequest req = new LoginRequest();
    req.setEmail("alice@example.com");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("jwt-token"))
        .andExpect(jsonPath("$.email").value("alice@example.com"));
  }

  @Test
  void login_returns400_whenEmailIsBlank() throws Exception {
    LoginRequest req = new LoginRequest();
    req.setEmail("");
    req.setPassword("password123");

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }
}
