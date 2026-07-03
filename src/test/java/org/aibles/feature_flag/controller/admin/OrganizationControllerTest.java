package org.aibles.feature_flag.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.request.CreateOrganizationRequest;
import org.aibles.feature_flag.dto.request.InviteMemberRequest;
import org.aibles.feature_flag.dto.response.MemberResponse;
import org.aibles.feature_flag.dto.response.OrganizationResponse;
import org.aibles.feature_flag.exception.GlobalExceptionHandler;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrganizationControllerTest {

    @Mock
    private OrganizationService organizationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrganizationController(organizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturns201() throws Exception {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme");
        request.setSlug("acme");
        when(organizationService.create(any(CreateOrganizationRequest.class)))
                .thenReturn(OrganizationResponse.builder().id(orgId).name("Acme").slug("acme").build());

        mockMvc.perform(post("/api/v1/organisations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("acme"));
    }

    @Test
    void createRejectsInvalidSlugWith400() throws Exception {
        CreateOrganizationRequest request = new CreateOrganizationRequest();
        request.setName("Acme");
        request.setSlug("Not A Slug");

        mockMvc.perform(post("/api/v1/organisations")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(organizationService, never()).create(any());
    }

    @Test
    void getMapsUnauthorizedTo403() throws Exception {
        when(organizationService.get(orgId))
                .thenThrow(new UnauthorizedException("You are not a member of this organisation"));

        mockMvc.perform(get("/api/v1/organisations/{orgId}", orgId))
                .andExpect(status().isForbidden());
    }

    @Test
    void inviteMemberReturns201() throws Exception {
        UUID inviteeId = UUID.randomUUID();
        InviteMemberRequest request = new InviteMemberRequest();
        request.setUserId(inviteeId);
        request.setRole(MemberRole.VIEWER);
        when(organizationService.inviteMember(eq(orgId), any(InviteMemberRequest.class)))
                .thenReturn(MemberResponse.builder().userId(inviteeId).email("u@example.com").role(MemberRole.VIEWER).build());

        mockMvc.perform(post("/api/v1/organisations/{orgId}/members", orgId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void removeMemberReturns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/organisations/{orgId}/members/{userId}", orgId, userId))
                .andExpect(status().isNoContent());

        verify(organizationService).removeMember(orgId, userId);
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/organisations/{orgId}", orgId))
                .andExpect(status().isNoContent());

        verify(organizationService).delete(orgId);
    }
}
