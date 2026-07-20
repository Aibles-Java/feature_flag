package org.aibles.feature_flag.controller.auth;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** End-to-end over the real admin security chain + H2: register → login → refresh → reuse. */
@SpringBootTest
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

  @Autowired private WebApplicationContext webApplicationContext;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  private String register(String prefix) throws Exception {
    String email = prefix + "-" + System.nanoTime() + "@example.com";
    String body =
        objectMapper.writeValueAsString(
            Map.of("email", email, "password", "Password123!", "firstName", "A", "lastName", "B"));
    mockMvc
        .perform(
            post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    String login =
        objectMapper.writeValueAsString(Map.of("email", email, "password", "Password123!"));
    return mockMvc
        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String field(String json, String name) throws Exception {
    return objectMapper.readTree(json).get(name).asText();
  }

  private String refreshBody(String token) throws Exception {
    return objectMapper.writeValueAsString(Map.of("refreshToken", token));
  }

  @Test
  void refreshRotatesAndReuseRevokesFamily() throws Exception {
    String oldRefresh = field(register("rotate"), "refreshToken");
    String body = refreshBody(oldRefresh);

    String refreshedJson =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The rotated token must not be reusable.
    mockMvc
        .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());

    // ...and that reuse must have revoked the whole family, killing the successor too.
    String newBody = refreshBody(field(refreshedJson, "refreshToken"));
    mockMvc
        .perform(
            post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(newBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshRotatesToADifferentToken() throws Exception {
    String oldRefresh = field(register("distinct"), "refreshToken");

    String refreshedJson =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(oldRefresh)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(field(refreshedJson, "refreshToken"))
        .isNotEqualTo(oldRefresh);
  }

  @Test
  void logoutRevokesFamilyAndIsIdempotent() throws Exception {
    String refresh = field(register("logout"), "refreshToken");
    String body = refreshBody(refresh);

    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());

    // Idempotent, and gives away nothing about whether the token existed.
    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());
  }

  @Test
  void refreshWithUnknownTokenIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody("0".repeat(64))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshWithBlankTokenIsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody("")))
        .andExpect(status().isBadRequest());
  }
}
