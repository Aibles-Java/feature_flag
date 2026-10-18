package org.aibles.feature_flag.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.UserPrincipal;
import org.aibles.feature_flag.testsupport.FixtureIds;
import org.aibles.feature_flag.testsupport.SyntheticFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * S-2.5 independent QA: the REAL controllers + security chain + exception handler. Proves the
 * static route /flags/environment-states is not captured by /flags/{flagId}, 403/400 mapping,
 * clamping, tie-stable paging and the response field whitelist (solution-design 6.5).
 */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:matrix-http-testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
          + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@ActiveProfiles("test")
class FlagMatrixHttpIntegrationTest {

  private static final String URL = "/api/v1/flags/environment-states";

  @Autowired WebApplicationContext ctx;
  @Autowired JwtTokenProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper om;
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = webAppContextSetup(ctx).apply(springSecurity()).build();
    SyntheticFixture.load(jdbc);
  }

  private String bearer(UUID user) {
    String email = jdbc.queryForObject("select email from users where id = ?", String.class, user);
    return "Bearer "
        + jwt.generateToken(
            UserPrincipal.from(User.builder().id(user).email(email).passwordHash("x").build()));
  }

  private MvcResult call(UUID user, String... kv) throws Exception {
    var rb = get(URL).header("Authorization", bearer(user));
    for (int i = 0; i < kv.length; i += 2) rb = rb.param(kv[i], kv[i + 1]);
    return mvc.perform(rb).andReturn();
  }

  @Test
  @DisplayName("route: static path is the matrix handler, not /{flagId} (no 400 type mismatch)")
  void routeNotCapturedByFlagId() throws Exception {
    MvcResult r = call(FixtureIds.USER_OWNER_X, "projectId", FixtureIds.PROJECT_A.toString());
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = om.readTree(r.getResponse().getContentAsString());
    assertThat(body.has("content")).isTrue();
    assertThat(body.get("content").get(0).has("states")).isTrue();
  }

  @Test
  @DisplayName("route: a real UUID path still reaches /{flagId}")
  void uuidPathStillFlagDetail() throws Exception {
    mvc.perform(
            get("/api/v1/flags/" + FixtureIds.FLAG_A1)
                .header("Authorization", bearer(FixtureIds.USER_OWNER_X)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(FixtureIds.FLAG_A1.toString()));
  }

  @Test
  @DisplayName("403 over HTTP for every non-authorized caller; 401 without token")
  void forbidden() throws Exception {
    for (UUID u :
        List.of(FixtureIds.USER_MEMBER_Y, FixtureIds.USER_OUTSIDER_X, FixtureIds.USER_NOACCESS)) {
      assertThat(call(u, "projectId", FixtureIds.PROJECT_A.toString()).getResponse().getStatus())
          .as("user %s", u)
          .isEqualTo(403);
    }
    assertThat(
            call(FixtureIds.USER_GRANT_A, "projectId", FixtureIds.PROJECT_B.toString())
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    // same-org viewer on a project in the other org
    assertThat(
            call(FixtureIds.USER_VIEWER_X, "projectId", FixtureIds.PROJECT_C.toString())
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    mvc.perform(get(URL).param("projectId", FixtureIds.PROJECT_A.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("400: missing / invalid projectId and non-numeric page/size")
  void badRequests() throws Exception {
    assertThat(call(FixtureIds.USER_OWNER_X).getResponse().getStatus()).isEqualTo(400);
    assertThat(call(FixtureIds.USER_OWNER_X, "projectId", "nope").getResponse().getStatus())
        .isEqualTo(400);
    String p = FixtureIds.PROJECT_A.toString();
    assertThat(call(FixtureIds.USER_OWNER_X, "projectId", p, "page", "x").getResponse().getStatus())
        .isEqualTo(400);
    assertThat(call(FixtureIds.USER_OWNER_X, "projectId", p, "size", "x").getResponse().getStatus())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("page/size edge values never 500")
  void edgeValues() throws Exception {
    String p = FixtureIds.PROJECT_A.toString();
    String[][] cases = {
      {"size", "0"},
      {"size", "-1"},
      {"size", "1000"},
      {"page", "-1"},
      {"page", "-999"},
      {"page", "2147483647"},
      {"page", "999999999"},
      {"size", "2147483647"},
      {"size", "-2147483648"},
      {"page", "2147483647", "size", "100"}
    };
    for (String[] c : cases) {
      String[] args = new String[c.length + 2];
      args[0] = "projectId";
      args[1] = p;
      System.arraycopy(c, 0, args, 2, c.length);
      MvcResult r = call(FixtureIds.USER_OWNER_X, args);
      assertThat(r.getResponse().getStatus())
          .as("params %s -> %s", String.join(",", c), r.getResponse().getContentAsString())
          .isEqualTo(200);
      JsonNode b = om.readTree(r.getResponse().getContentAsString());
      assertThat(b.get("size").asInt()).isBetween(1, 100);
      assertThat(b.get("page").asInt()).isGreaterThanOrEqualTo(0);
    }
    JsonNode z =
        om.readTree(
            call(FixtureIds.USER_OWNER_X, "projectId", p, "size", "0")
                .getResponse()
                .getContentAsString());
    assertThat(z.get("size").asInt()).isEqualTo(50);
    JsonNode big =
        om.readTree(
            call(FixtureIds.USER_OWNER_X, "projectId", p, "size", "1000")
                .getResponse()
                .getContentAsString());
    assertThat(big.get("size").asInt()).isEqualTo(100);
  }

  @Test
  @DisplayName("paging with createdAt ties: size=1 walk yields every flag exactly once")
  void tiesNoDuplicatesNoGaps() throws Exception {
    // all four fixture flags of project A share created_at
    jdbc.update(
        "update feature_flags set created_at = (select created_at from feature_flags where id = ?)"
            + " where project_id = ?",
        FixtureIds.FLAG_A1,
        FixtureIds.PROJECT_A);
    Long distinct =
        jdbc.queryForObject(
            "select count(distinct created_at) from feature_flags where project_id = ?",
            Long.class,
            FixtureIds.PROJECT_A);
    assertThat(distinct).isEqualTo(1);
    List<String> seen = new ArrayList<>();
    for (int pg = 0; pg < 4; pg++) {
      JsonNode b =
          om.readTree(
              call(
                      FixtureIds.USER_OWNER_X,
                      "projectId",
                      FixtureIds.PROJECT_A.toString(),
                      "page",
                      "" + pg,
                      "size",
                      "1")
                  .getResponse()
                  .getContentAsString());
      assertThat(b.get("content")).hasSize(1);
      assertThat(b.get("totalElements").asInt()).isEqualTo(4);
      assertThat(b.get("totalPages").asInt()).isEqualTo(4);
      seen.add(b.get("content").get(0).get("flag").get("id").asText());
    }
    assertThat(new HashSet<>(seen)).hasSize(4);
    assertThat(seen).doesNotContainNull();
  }

  @Test
  @DisplayName("response exposes only the 6.5 fields (no api keys, secrets, audit, org internals)")
  void fieldWhitelist() throws Exception {
    JsonNode b =
        om.readTree(
            call(FixtureIds.USER_OWNER_X, "projectId", FixtureIds.PROJECT_A.toString())
                .getResponse()
                .getContentAsString());
    Set<String> page = new HashSet<>();
    b.fieldNames().forEachRemaining(page::add);
    assertThat(page).isSubsetOf("content", "page", "size", "totalElements", "totalPages");
    JsonNode row = b.get("content").get(0);
    Set<String> rowFields = new HashSet<>();
    row.fieldNames().forEachRemaining(rowFields::add);
    assertThat(rowFields).containsExactlyInAnyOrder("flag", "states");
    Set<String> flagFields = new HashSet<>();
    row.get("flag").fieldNames().forEachRemaining(flagFields::add);
    assertThat(flagFields)
        .isSubsetOf(
            "id",
            "name",
            "key",
            "description",
            "valueType",
            "archived",
            "expiresAt",
            "projectId",
            "createdAt");
    Set<String> stateFields = new HashSet<>();
    row.get("states").get(0).fieldNames().forEachRemaining(stateFields::add);
    assertThat(stateFields)
        .isSubsetOf(
            "flagId",
            "environmentId",
            "enabled",
            "value",
            "rolloutPercent",
            "version",
            "lastEvaluatedAt");
  }

  @Test
  @DisplayName("unknown projectId: pins current behaviour (existence oracle is accepted residual)")
  void unknownProject() throws Exception {
    int st =
        call(FixtureIds.USER_OWNER_X, "projectId", UUID.randomUUID().toString())
            .getResponse()
            .getStatus();
    assertThat(st).isIn(403, 404);
  }
}
