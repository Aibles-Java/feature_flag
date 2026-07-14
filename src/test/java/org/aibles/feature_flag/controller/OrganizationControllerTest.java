package org.aibles.feature_flag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.OrganizationController;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.request.UpdateOrganizationRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.service.OrganizationService;
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
class OrganizationControllerTest {

  @Mock OrganizationService organizationService;

  MockMvc mockMvc;
  ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new OrganizationController(organizationService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void create_returns201_withOrgResponse() throws Exception {
    OrganizationResponse response =
        OrganizationResponse.builder().id(UUID.randomUUID()).name("Acme").slug("acme").build();
    when(organizationService.create(any())).thenReturn(response);

    CreateOrganizationRequest req = new CreateOrganizationRequest();
    req.setName("Acme");
    req.setSlug("acme");

    mockMvc
        .perform(
            post("/api/v1/organisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.slug").value("acme"));
  }

  @Test
  void listMine_returns200_withOrgList() throws Exception {
    OrganizationResponse response =
        OrganizationResponse.builder().id(UUID.randomUUID()).name("Acme").slug("acme").build();
    when(organizationService.listMine()).thenReturn(List.of(response));

    mockMvc
        .perform(get("/api/v1/organisations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].slug").value("acme"));
  }

  @Test
  void get_returns200_withOrg() throws Exception {
    UUID orgId = UUID.randomUUID();
    OrganizationResponse response =
        OrganizationResponse.builder().id(orgId).name("Acme").slug("acme").build();
    when(organizationService.get(orgId)).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/organisations/{orgId}", orgId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orgId.toString()));
  }

  @Test
  void delete_returns204() throws Exception {
    UUID orgId = UUID.randomUUID();
    doNothing().when(organizationService).delete(orgId);

    mockMvc
        .perform(delete("/api/v1/organisations/{orgId}", orgId))
        .andExpect(status().isNoContent());
  }

  @Test
  void listMembers_returns200_withMemberList() throws Exception {
    UUID orgId = UUID.randomUUID();
    MemberResponse member =
        MemberResponse.builder()
            .userId(UUID.randomUUID())
            .email("alice@example.com")
            .role(MemberRole.OWNER)
            .build();
    when(organizationService.listMembers(orgId)).thenReturn(List.of(member));

    mockMvc
        .perform(get("/api/v1/organisations/{orgId}/members", orgId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("alice@example.com"));
  }

  @Test
  void inviteMember_returns201_withMemberResponse() throws Exception {
    UUID orgId = UUID.randomUUID();
    UUID newUserId = UUID.randomUUID();
    MemberResponse member =
        MemberResponse.builder()
            .userId(newUserId)
            .email("bob@example.com")
            .role(MemberRole.ADMIN)
            .build();
    when(organizationService.inviteMember(eq(orgId), any())).thenReturn(member);

    InviteMemberRequest req = new InviteMemberRequest();
    req.setUserId(newUserId);
    req.setRole(MemberRole.ADMIN);

    mockMvc
        .perform(
            post("/api/v1/organisations/{orgId}/members", orgId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("ADMIN"));
  }

  @Test
  void removeMember_returns204() throws Exception {
    UUID orgId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    doNothing().when(organizationService).removeMember(orgId, userId);

    mockMvc
        .perform(delete("/api/v1/organisations/{orgId}/members/{userId}", orgId, userId))
        .andExpect(status().isNoContent());
  }

  @Test
  void update_returns200_withUpdatedOrganisation() throws Exception {
    UUID orgId = UUID.randomUUID();
    OrganizationResponse response =
        OrganizationResponse.builder().id(orgId).name("Renamed Corp").slug("acme").build();
    when(organizationService.update(eq(orgId), any())).thenReturn(response);

    UpdateOrganizationRequest req = new UpdateOrganizationRequest();
    req.setName("Renamed Corp");

    mockMvc
        .perform(
            put("/api/v1/organisations/{orgId}", orgId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed Corp"));
  }

  @Test
  void create_returns400_whenSlugContainsUppercase() throws Exception {
    CreateOrganizationRequest req = new CreateOrganizationRequest();
    req.setName("Acme");
    req.setSlug("ACME");

    mockMvc
        .perform(
            post("/api/v1/organisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }
}
