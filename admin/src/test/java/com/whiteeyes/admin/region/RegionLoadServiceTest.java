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
        long idOfFirstLoad = regionRepository.findByCodeIn(java.util.List.of("11110530")).get(0).getId();

        regionLoadService.load(VALID_FILE);

        assertThat(regionRepository.count()).isEqualTo(6);
        assertThat(regionRepository.findByCodeIn(java.util.List.of("11110530")).get(0).getId())
                .isEqualTo(idOfFirstLoad);
    }

    @Test
    @DisplayName("FNC-A-012 시·구·동의 상위 연결이 원본 코드와 맞다")
    void load_linksParentByCode() {
        regionLoadService.load(VALID_FILE);

        transactionTemplate.executeWithoutResult(status -> {
            Region sido = find("11");
            Region sigungu = find("11110");
            Region emd = find("11110530");

            assertThat(sido.getLevel()).isEqualTo(RegionLevel.SIDO);
            assertThat(sido.getParent()).isNull();
            assertThat(sigungu.getLevel()).isEqualTo(RegionLevel.SIGUNGU);
            assertThat(sigungu.getParent().getCode()).isEqualTo("11");
            assertThat(emd.getLevel()).isEqualTo(RegionLevel.EMD);
            assertThat(emd.getParent().getCode()).isEqualTo("11110");
            assertThat(emd.getName()).isEqualTo("사직동");
        });
    }

    @Test
    @DisplayName("FNC-A-012 중심점은 경계 안의 점이고 구멍 안에 들어가지 않는다")
    void load_centerIsInsideBoundary() {
        regionLoadService.load(VALID_FILE);

        Region emd = find("11110540");
        double lat = emd.getCenterLat().doubleValue();
        double lng = emd.getCenterLng().doubleValue();

        // 삼청동: 경도 127.00~127.05, 위도 37.55~37.65 에서 구멍(127.02~127.03, 37.62~37.63)을 뺀 영역
        assertThat(lng).isBetween(127.0, 127.05);
        assertThat(lat).isBetween(37.55, 37.65);
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

        assertThat(regionRepository.count()).isEqualTo(6);
        assertThat(find("11110540").getName()).isEqualTo("새삼청동");
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
    @DisplayName("FNC-A-012 시·도와 시·군·구의 경계는 소속 읍·면·동을 합친 것이다")
    void load_mergesBoundaryOfChildren() {
        regionLoadService.load(VALID_FILE);

        Region sido = find("11");
        Region jongno = find("11110");
        Region jung = find("11140");

        // 종로구: 사직동(126.95~127.0)과 삼청동(127.0~127.05)의 합, 중구는 127.05~127.1
        assertThat(jongno.getName()).isEqualTo("종로구");
        assertThat(jongno.getBoundary()).contains("126.95", "127.05").doesNotContain("127.1");
        assertThat(jung.getName()).isEqualTo("중구");
        assertThat(jung.getBoundary()).contains("127.05", "127.1");
        // 시·도: 세 읍·면·동 전체 범위, 이름은 sidonm
        assertThat(sido.getName()).isEqualTo("서울특별시");
        assertThat(sido.getBoundary()).contains("126.95", "127.1");
        // 삼청동 구멍(127.02~127.03)은 합쳐도 남는다
        assertThat(jongno.getBoundary()).contains("127.02", "127.03");
    }

    @Test
    @DisplayName("FNC-A-012 adm_cd가 8자리가 아니면 작업이 중단된다")
    void load_codeNotEightDigits_fails(@TempDir Path tempDir) throws Exception {
        String sevenDigits = Files.readString(VALID_FILE).replace("\"11110530\"", "\"1111053\"");

        assertThatThrownBy(() -> regionLoadService.load(writeTemp(tempDir, sevenDigits)))
                .isInstanceOf(RegionFileFormatException.class);

        assertThat(regionRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-012 위도·경도 범위를 벗어난 좌표면 원인을 알리고 중단된다")
    void load_coordinateOutOfRange_fails(@TempDir Path tempDir) throws Exception {
        // 좌표계가 다른 파일처럼 경도 값을 180보다 크게 바꾼다
        String projected = Files.readString(VALID_FILE).replace("127.1,", "955156.5,");

        assertThatThrownBy(() -> regionLoadService.load(writeTemp(tempDir, projected)))
                .isInstanceOf(RegionFileFormatException.class)
                .hasMessageContaining("범위를 벗어난");

        assertThat(regionRepository.count()).isZero();
    }

    @Test
    @DisplayName("FNC-A-012 같은 시·군·구 코드에 이름이 다르면 작업이 중단된다")
    void load_conflictingSigunguName_fails(@TempDir Path tempDir) throws Exception {
        // 삼청동만 시·군·구 이름을 바꿔 같은 코드(11110)에 이름이 둘이 되게 한다
        String conflict = Files.readString(VALID_FILE)
                .replace("삼청동\", \"sidonm\": \"서울특별시\", \"sggnm\": \"종로구\"", "삼청동\", \"sidonm\": \"서울특별시\", \"sggnm\": \"다른구\"");

        assertThatThrownBy(() -> regionLoadService.load(writeTemp(tempDir, conflict)))
                .isInstanceOf(RegionFileFormatException.class);

        assertThat(regionRepository.count()).isZero();
    }

    private Path writeTemp(Path tempDir, String content) throws Exception {
        Path file = tempDir.resolve("input.geojson");
        Files.writeString(file, content);
        return file;
    }

    private Region find(String code) {
        return regionRepository.findByCodeIn(java.util.List.of(code)).get(0);
    }
}
