package org.aibles.feature_flag.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.PaginationConfig;
import org.aibles.feature_flag.controller.admin.AuditController;
import org.aibles.feature_flag.domain.enums.AuditAction;
import org.aibles.feature_flag.domain.enums.AuditEntityType;
import org.aibles.feature_flag.dto.response.AuditLogResponse;
import org.aibles.feature_flag.service.impl.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

  @Mock AuditService auditService;

  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    PageableHandlerMethodArgumentResolver pageableResolver =
        new PageableHandlerMethodArgumentResolver();
    pageableResolver.setMaxPageSize(PaginationConfig.MAX_PAGE_SIZE);
    pageableResolver.setFallbackPageable(PageRequest.of(0, PaginationConfig.DEFAULT_PAGE_SIZE));
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AuditController(auditService))
            .setCustomArgumentResolvers(pageableResolver)
            .build();
  }

  @Test
  void listAuditLog_returns200_withPagedEnvelope() throws Exception {
    UUID orgId = UUID.randomUUID();
    AuditLogResponse entry =
        AuditLogResponse.builder()
            .id(UUID.randomUUID())
            .orgId(orgId)
            .action(AuditAction.CHANGE_STATE)
            .entityType(AuditEntityType.FLAG_STATE)
            .build();
    when(auditService.list(eq(orgId), any())).thenReturn(new PageImpl<>(List.of(entry)));

    mockMvc
        .perform(get("/api/v1/organisations/{orgId}/audit-log", orgId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].action").value("CHANGE_STATE"))
        .andExpect(jsonPath("$.content[0].entityType").value("FLAG_STATE"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void listAuditLog_defaultsToNewestFirst_size20() throws Exception {
    UUID orgId = UUID.randomUUID();
    when(auditService.list(eq(orgId), any())).thenReturn(new PageImpl<>(List.of()));
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    mockMvc
        .perform(get("/api/v1/organisations/{orgId}/audit-log", orgId))
        .andExpect(status().isOk());

    org.mockito.Mockito.verify(auditService).list(eq(orgId), captor.capture());
    Pageable used = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(used.getPageSize()).isEqualTo(20);
    // Newest-first: createdAt sorted DESC.
    org.assertj.core.api.Assertions.assertThat(used.getSort().getOrderFor("createdAt"))
        .isNotNull()
        .satisfies(o -> org.assertj.core.api.Assertions.assertThat(o.isDescending()).isTrue());
  }
}
