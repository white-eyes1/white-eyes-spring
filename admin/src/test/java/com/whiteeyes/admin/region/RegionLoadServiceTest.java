package com.whiteeyes.admin.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.admin.region.exception.RegionFileFormatException;
import com.whiteeyes.admin.region.service.RegionLoadService;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class RegionLoadServiceTest {

    private static final Path VALID_FILE = Paths.get("src/test/resources/region/regions.geojson");

    @Autowired
    private RegionLoadService regionLoadService;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearRegions() {
        jdbcTemplate.update("UPDATE region SET parent_id = NULL");
        jdbcTemplate.update("DELETE FROM region");
    }

    @Test
    @DisplayName("FNC-A-012 다시 실행해도 중복이 없다")
    void loadTwice_noDuplicates() {
        regionLoadService.load(VALID_FILE);
        long idOfFirstLoad = regionRepository.findByCodeIn(java.util.List.of("1111051")).get(0).getId();

        regionLoadService.load(VALID_FILE);

        assertThat(regionRepository.count()).isEqualTo(4);
        assertThat(regionRepository.findByCodeIn(java.util.List.of("1111051")).get(0).getId())
                .isEqualTo(idOfFirstLoad);
    }

    @Test
    @DisplayName("FNC-A-012 시·구·동의 상위 연결이 원본 코드와 맞다")
    void load_linksParentByCode() {
        regionLoadService.load(VALID_FILE);

        transactionTemplate.executeWithoutResult(status -> {
            Region si = find("11");
            Region gu = find("11110");
            Region dong = find("1111051");

            assertThat(si.getLevel()).isEqualTo(RegionLevel.SI);
            assertThat(si.getParent()).isNull();
            assertThat(gu.getLevel()).isEqualTo(RegionLevel.GU);
            assertThat(gu.getParent().getCode()).isEqualTo("11");
            assertThat(dong.getLevel()).isEqualTo(RegionLevel.DONG);
            assertThat(dong.getParent().getCode()).isEqualTo("11110");
            assertThat(dong.getName()).isEqualTo("사직동");
        });
    }

    @Test
    @DisplayName("FNC-A-012 중심점은 경계 안의 점이고 구멍 안에 들어가지 않는다")
    void load_centerIsInsideBoundary() {
        regionLoadService.load(VALID_FILE);

        Region dong = find("1111052");
        double lat = dong.getCenterLat().doubleValue();
        double lng = dong.getCenterLng().doubleValue();

        // 삼청동: 경도 127.00~127.05, 위도 37.60~37.65 에서 구멍(127.02~127.03, 37.62~37.63)을 뺀 영역
        assertThat(lng).isBetween(127.0, 127.05);
        assertThat(lat).isBetween(37.6, 37.65);
        boolean inHole = lng > 127.02 && lng < 127.03 && lat > 37.62 && lat < 37.63;
        assertThat(inHole).isFalse();
    }

    @Test
    @DisplayName("FNC-A-012 다시 적재하면 같은 code의 값을 덮어쓴다")
    void load_overwritesSameCode(@TempDir Path tempDir) throws Exception {
        regionLoadService.load(VALID_FILE);
        String renamed = Files.readString(VALID_FILE).replace("삼청동", "새삼청동");
        Path renamedFile = tempDir.resolve("renamed.geojson");
        Files.writeString(renamedFile, renamed);

        regionLoadService.load(renamedFile);

        assertThat(regionRepository.count()).isEqualTo(4);
        assertThat(find("1111052").getName()).isEqualTo("새삼청동");
    }

    @Test
    @DisplayName("FNC-A-012 파일 형식 오류면 작업이 중단되고 아무것도 저장되지 않는다")
    void load_invalidFile_savesNothing(@TempDir Path tempDir) throws Exception {
        // 사직동 경계 종류를 지원하지 않는 값으로 바꾼다
        String broken = Files.readString(VALID_FILE).replace("\"MultiPolygon\"", "\"LineString\"");
        Path brokenFile = tempDir.resolve("broken.geojson");
        Files.writeString(brokenFile, broken);

        assertThatThrownBy(() -> regionLoadService.load(brokenFile)).isInstanceOf(RegionFileFormatException.class);

        assertThat(regionRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-012 JSON이 아닌 파일이면 작업이 중단된다")
    void load_notJson_fails(@TempDir Path tempDir) throws Exception {
        Path textFile = tempDir.resolve("not-json.geojson");
        Files.writeString(textFile, "이건 GeoJSON이 아님");

        assertThatThrownBy(() -> regionLoadService.load(textFile)).isInstanceOf(RegionFileFormatException.class);

        assertThat(regionRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-012 상위 행정구역이 파일에 없으면 작업이 중단된다")
    void load_missingParent_fails(@TempDir Path tempDir) throws Exception {
        String noGu = Files.readString(VALID_FILE).replace("\"11110\"", "\"99999\"");
        Path noGuFile = tempDir.resolve("no-gu.geojson");
        Files.writeString(noGuFile, noGu);

        assertThatThrownBy(() -> regionLoadService.load(noGuFile)).isInstanceOf(RegionFileFormatException.class);

        assertThat(regionRepository.count()).isZero();
    }

    private Region find(String code) {
        return regionRepository.findByCodeIn(java.util.List.of(code)).get(0);
    }
}
