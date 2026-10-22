package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.admin.security.AdminTestUserConfig;
import com.whiteeyes.admin.support.AdminTestLogin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구역 이웃 조회·저장(FNC-A-016). 검수 기준: 반대쪽 자동 반영·기존 짝 해제 확인.
 */
@Import(AdminTestUserConfig.class)
@SpringBootTest
@AutoConfigureMockMvc
class ZoneNeighborTest {

    private static final Path REGION_FILE = Paths.get("src/test/resources/region/regions.geojson");

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long a;
    private long b;
    private long c;
    private long d;

    @BeforeEach
    void prepare() {
        clearAll();
        regionLoadService.load(REGION_FILE);
        long dongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = '삼청동'", Long.class);
        a = insertZone(dongId, "A구역");
        b = insertZone(dongId, "B구역");
        c = insertZone(dongId, "C구역");
        d = insertZone(dongId, "D구역");
    }

    // zone_neighbor·zone·work_history를 남기면 뒤에 도는 다른 테스트가 zone·region을 지우다 FK로 실패한다
    @AfterEach
    void cleanUp() {
        clearAll();
    }

    private void clearAll() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.update("DELETE FROM zone_neighbor");
        jdbcTemplate.update("DELETE FROM zone");
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-016 이웃이 없으면 4방향 모두 null이다")
    void find_noNeighbors_allNull() throws Exception {
        mockMvc.perform(get(url(a)).with(AdminTestLogin.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.N").value((Object) null))
                .andExpect(jsonPath("$.E").value((Object) null))
                .andExpect(jsonPath("$.S").value((Object) null))
                .andExpect(jsonPath("$.W").value((Object) null));
    }

    @Test
    @DisplayName("FNC-A-016 A의 동쪽을 B로 저장하면 B의 서쪽이 A로 자동 반영된다")
    void save_mirrorsOppositeDirection() throws Exception {
        save(a, "{\"N\":null,\"E\":" + b + ",\"S\":null,\"W\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.E.id").value(b))
                .andExpect(jsonPath("$.E.name").value("B구역"))
                .andExpect(jsonPath("$.W").value((Object) null));

        assertThat(neighbor(a, "E")).isEqualTo(b);
        assertThat(neighbor(b, "W")).isEqualTo(a);
        assertThat(rowCount()).isEqualTo(2);
        mockMvc.perform(get(url(b)).with(AdminTestLogin.admin()))
                .andExpect(jsonPath("$.W.id").value(a))
                .andExpect(jsonPath("$.W.name").value("A구역"));
    }

    @Test
    @DisplayName("FNC-A-016 4방향을 한 번에 저장하면 4쌍이 모두 맞춰진다")
    void save_allFourDirections() throws Exception {
        long e = insertZone(dongOf(a), "E구역");
        save(a, "{\"N\":" + b + ",\"E\":" + c + ",\"S\":" + d + ",\"W\":" + e + "}").andExpect(status().isOk());

        assertThat(neighbor(b, "S")).isEqualTo(a);
        assertThat(neighbor(c, "W")).isEqualTo(a);
        assertThat(neighbor(d, "N")).isEqualTo(a);
        assertThat(neighbor(e, "E")).isEqualTo(a);
        assertThat(rowCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("FNC-A-016 A의 동쪽을 B에서 D로 바꾸면 B의 서쪽(기존 짝)이 해제되고 D의 서쪽이 A가 된다")
    void save_replaceNeighbor_releasesOldPair() throws Exception {
        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());

        save(a, "{\"E\":" + d + "}").andExpect(status().isOk());

        assertThat(neighbor(a, "E")).isEqualTo(d);
        assertThat(neighbor(d, "W")).isEqualTo(a);
        assertThat(neighbor(b, "W")).as("옛 이웃 B의 반대 방향은 비워진다").isNull();
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-016 B의 서쪽에 이미 C가 있으면 A를 동쪽으로 연결할 때 C의 동쪽이 해제된다")
    void save_targetOppositeAlreadyTaken_releasesThirdZone() throws Exception {
        save(c, "{\"E\":" + b + "}").andExpect(status().isOk());
        assertThat(neighbor(b, "W")).isEqualTo(c);

        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());

        assertThat(neighbor(a, "E")).isEqualTo(b);
        assertThat(neighbor(b, "W")).isEqualTo(a);
        assertThat(neighbor(c, "E")).as("B의 서쪽을 빼앗긴 C의 동쪽은 비워진다").isNull();
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("FNC-A-016 이웃을 null로 비우면 상대 쪽 반대 방향도 비운다")
    void save_clearNeighbor_clearsOpposite() throws Exception {
        save(a, "{\"E\":" + b + ",\"N\":" + c + "}").andExpect(status().isOk());

        save(a, "{\"E\":null,\"N\":" + c + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.E").value((Object) null))
                .andExpect(jsonPath("$.N.id").value(c));

        assertThat(neighbor(a, "E")).isNull();
        assertThat(neighbor(b, "W")).isNull();
        assertThat(neighbor(c, "S")).as("그대로 둔 방향의 짝은 유지").isEqualTo(a);
    }

    @Test
    @DisplayName("FNC-A-016 요청에 없는 방향은 이웃 없음으로 본다")
    void save_missingDirection_treatedAsNull() throws Exception {
        save(a, "{\"E\":" + b + ",\"S\":" + c + "}").andExpect(status().isOk());

        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());

        assertThat(neighbor(a, "S")).isNull();
        assertThat(neighbor(c, "N")).isNull();
        assertThat(neighbor(a, "E")).isEqualTo(b);
    }

    @Test
    @DisplayName("FNC-A-016 같은 내용을 다시 저장해도 결과가 같다(멱등)")
    void save_sameTwice_idempotent() throws Exception {
        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());
        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());

        assertThat(rowCount()).isEqualTo(2);
        assertThat(neighbor(b, "W")).isEqualTo(a);
    }

    @Test
    @DisplayName("FNC-A-016 다른 동의 구역도 이웃으로 지정할 수 있다")
    void save_neighborInOtherDong_allowed() throws Exception {
        long sogongDongId = jdbcTemplate.queryForObject("SELECT id FROM region WHERE name = '소공동'", Long.class);
        long other = insertZone(sogongDongId, "소공동 1구역");

        save(a, "{\"E\":" + other + "}").andExpect(status().isOk());

        assertThat(neighbor(other, "W")).isEqualTo(a);
    }

    @Test
    @DisplayName("FNC-A-016 자기 자신을 이웃으로 지정하면 400 INVALID_INPUT이고 아무것도 저장하지 않는다")
    void save_self_badRequest() throws Exception {
        save(a, "{\"E\":" + b + ",\"N\":" + a + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(rowCount()).isZero();
        assertThat(historyCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-016 없는 구역을 이웃으로 지정하면 404 ZONE_NOT_FOUND이고 아무것도 저장하지 않는다")
    void save_missingNeighbor_notFound() throws Exception {
        save(a, "{\"E\":" + b + ",\"S\":999999}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-016 삭제된 구역을 이웃으로 지정하면 404 ZONE_NOT_FOUND")
    void save_deletedNeighbor_notFound() throws Exception {
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", b);

        save(a, "{\"E\":" + b + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-016 없는 구역·삭제된 구역의 이웃은 조회도 저장도 404")
    void missingOrDeletedZone_notFound() throws Exception {
        jdbcTemplate.update("UPDATE zone SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", d);

        mockMvc.perform(get(url(999999)).with(AdminTestLogin.admin())).andExpect(status().isNotFound());
        mockMvc.perform(get(url(d)).with(AdminTestLogin.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ZONE_NOT_FOUND"));
        save(d, "{\"E\":" + b + "}").andExpect(status().isNotFound());
        save(999999, "{}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("FNC-A-016 N·E·S·W 말고 다른 방향 키를 보내면 400 INVALID_INPUT")
    void save_unknownDirection_badRequest() throws Exception {
        save(a, "{\"X\":" + b + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("FNC-A-016 저장하면 work_history에 ZONE_CHANGE 1행이 생기고 바뀐 방향 목록이 들어간다")
    void save_recordsWorkHistory() throws Exception {
        save(a, "{\"E\":" + b + "}").andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTION")).isEqualTo("ZONE_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("ZONE");
        assertThat(((Number) row.get("TARGET_ID")).longValue()).isEqualTo(a);
        assertThat(row.get("TARGET_NAME")).isEqualTo("A구역");
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo(AdminTestLogin.ADMIN_LOGIN_ID);
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("op").asString()).isEqualTo("NEIGHBORS");
        assertThat(detail.get("changes")).hasSize(1);
        assertThat(detail.get("changes").get(0).get("direction").asString()).isEqualTo("E");
        assertThat(detail.get("changes").get(0).get("before").isNull()).isTrue();
        assertThat(detail.get("changes").get(0).get("after").asLong()).isEqualTo(b);
        assertThat(detail.get("mirrored")).hasSize(1);
        assertThat(detail.get("mirrored").get(0).get("zoneId").asLong()).isEqualTo(b);
        assertThat(detail.get("mirrored").get(0).get("direction").asString()).isEqualTo("W");
    }

    @Test
    @DisplayName("FNC-A-016 바뀐 것이 없어도 저장 요청은 작업 기록 1행을 남기고 changes는 비어 있다")
    void save_noChange_recordsEmptyChanges() throws Exception {
        save(a, "{}").andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT detail FROM work_history");
        JsonNode detail = jsonMapper.readTree(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8));
        assertThat(detail.get("changes")).isEmpty();
        assertThat(detail.get("mirrored")).isEmpty();
    }

    @Test
    @DisplayName("FNC-C-026 로그인하지 않으면 401이고 이웃도 작업 기록도 생기지 않는다")
    void save_notLoggedIn_unauthorized() throws Exception {
        mockMvc.perform(put(url(a)).contentType(MediaType.APPLICATION_JSON).content("{\"E\":" + b + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(rowCount()).isZero();
        assertThat(historyCount()).isZero();
    }

    private ResultActions save(long zoneId, String body) throws Exception {
        return mockMvc.perform(put(url(zoneId))
                .with(AdminTestLogin.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String url(long zoneId) {
        return "/api/v1/manage/zones/" + zoneId + "/neighbors";
    }

    private long insertZone(long dongId, String name) {
        jdbcTemplate.update(
                "INSERT INTO zone (dong_id, name, area_m2, boundary, center_lat, center_lng)"
                        + " VALUES (?, ?, 100.00, '{}', 37.600000, 127.002000)",
                dongId,
                name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM zone", Long.class);
    }

    private long dongOf(long zoneId) {
        return jdbcTemplate.queryForObject("SELECT dong_id FROM zone WHERE id = ?", Long.class, zoneId);
    }

    private Long neighbor(long zoneId, String direction) {
        List<Long> found = jdbcTemplate.queryForList(
                "SELECT neighbor_id FROM zone_neighbor WHERE zone_id = ? AND direction = ?",
                Long.class,
                zoneId,
                direction);
        return found.isEmpty() ? null : found.get(0);
    }

    private long rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM zone_neighbor", Long.class);
    }

    private long historyCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM work_history", Long.class);
    }
}
