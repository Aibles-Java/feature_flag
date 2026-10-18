package org.aibles.feature_flag.flags;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.controller.admin.FlagMatrixController;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.FlagMatrixService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FlagMatrixControllerTest {

  private final FlagMatrixService service = mock(FlagMatrixService.class);
  private MockMvc mvc;
  private final UUID projectId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new FlagMatrixController(service)).build();
    when(service.getMatrix(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(
            PageResponse.<org.aibles.feature_flag.dto.response.FlagMatrixRowResponse>builder()
                .content(List.of())
                .page(0)
                .size(50)
                .totalElements(0)
                .totalPages(0)
                .build());
  }

  @Test
  void missingProjectId_is400() throws Exception {
    mvc.perform(get("/api/v1/flags/environment-states")).andExpect(status().isBadRequest());
  }

  @Test
  void defaultsToPage0Size50() throws Exception {
    mvc.perform(get("/api/v1/flags/environment-states").param("projectId", projectId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    verify(service).getMatrix(projectId, 0, 50);
  }

  @Test
  void invalidProjectId_is400() throws Exception {
    mvc.perform(get("/api/v1/flags/environment-states").param("projectId", "not-a-uuid"))
        .andExpect(status().isBadRequest());
  }
}
