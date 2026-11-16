package com.whiteeyes.tools.region;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * region-sql 명령: 행정구역 경계 파일에서 정한 지역만 골라 Flyway SQL로 쓴다(FNC-A-012). DB에는 접속하지 않는다.
 *
 * <pre>
 * region-sql --codes=25,29010 --out=../admin/src/main/resources/db/regions/R__region_seed.sql
 * </pre>
 * <ul>
 *   <li>--codes: adm_cd 앞자리(시·도 2자리 또는 시·군·구 5자리)를 쉼표로. 이 코드로 시작하는 읍·면·동만 넣는다</li>
 *   <li>--file: 경계 파일. 없으면 {@link #SOURCE_URL}을 받아 --cache 폴더(기본 build/region-source)에 둔다</li>
 *   <li>--h2: 시험용 H2에 맞춰 쓴다(경계 문자열을 JSON 값으로 읽게 함)</li>
 * </ul>
 */
public final class RegionSqlCommand {

    public static final String NAME = "region-sql";
    /** 경계 원본(decisions/0014). 데이터는 CC BY 4.0이라 출처를 SQL 머리말에 남긴다. */
    static final String SOURCE_VERSION = "ver20261001";

    static final String SOURCE_URL = "https://raw.githubusercontent.com/vuski/admdongkor/master/" + SOURCE_VERSION
            + "/HangJeongDong_" + SOURCE_VERSION + ".geojson";
    // 성공은 0, 실패는 1(셸·CI가 종료 코드로 구분한다).
    private static final int OK = 0;
    private static final int FAILED = 1;

    // 정적 메서드만 있는 도구 클래스라 인스턴스를 만들지 않는다.
    private RegionSqlCommand() {}

    /**
     * region-sql 명령 본체. 옵션을 읽고 경계 파일에서 지역을 골라 SQL 파일로 쓴다.
     *
     * @param args 명령 이름을 뺀 옵션(--codes, --out, --file, --cache, --h2)
     * @return 0 성공, 1 실패(원인은 표준 오류로 출력)
     */
    public static int run(String[] args) {
        try {
            // "--이름=값"을 맵으로 바꾼다.
            Map<String, String> options = options(args);
            // 적재할 지역 코드 앞자리(필수). 형식이 틀리면 여기서 예외가 난다.
            List<String> codes = codes(required(options, "codes"));
            // 출력할 SQL 파일 경로(필수).
            Path out = Path.of(required(options, "out"));
            // --file이 있으면 그 파일을 쓰고, 없으면 경계 원본을 받아 캐시 폴더에 둔 파일을 쓴다(이미 받았으면 다시 받지 않는다).
            Path file = options.containsKey("file")
                    ? Path.of(options.get("file"))
                    : download(Path.of(options.getOrDefault("cache", "build/region-source")));
            // 시험용 H2에 맞춘 SQL을 쓸지 여부.
            boolean h2 = options.containsKey("h2");

            // 전체 경계 파일에서 코드가 맞는 읍·면·동만 남긴다.
            String selected = select(Files.readString(file, StandardCharsets.UTF_8), codes);
            // 남긴 읍·면·동으로 시·도, 시·군·구, 읍·면·동 세 단계의 적재 대상을 만든다.
            List<RegionSource> sources = new RegionGeoJsonParser().parse(selected);
            // SQL 파일 맨 위에 남길 머리말. 출처(CC BY 4.0 표기), 생성 명령, 지역 수를 적어 손으로 고치지 않도록 알린다.
            List<String> header = List.of(
                    "행정구역(FNC-A-012). tools의 region-sql 명령으로 만든 파일이다. 손으로 고치지 않는다.",
                    "출처: 통계청 SGIS 행정구역 경계, vuski/admdongkor " + SOURCE_VERSION + " (CC BY 4.0)",
                    "명령: region-sql --codes=" + String.join(",", codes) + (h2 ? " --h2" : ""),
                    "지역 수: " + sources.size());
            // 출력 폴더가 없으면 만든다.
            Files.createDirectories(out.toAbsolutePath().getParent());
            // SQL을 UTF-8로 쓴다(한글 이름이 깨지지 않게).
            Files.writeString(out, RegionSqlWriter.write(header, sources, h2), StandardCharsets.UTF_8);
            System.out.println(out + ": 지역 " + sources.size() + "개");
            return OK;
        } catch (IOException | RuntimeException e) {
            // 입력 오류, 파일 오류, 형식 오류 모두 메시지만 출력하고 실패 코드로 끝낸다.
            System.err.println("region-sql 실패: " + e.getMessage());
            return FAILED;
        }
    }

    /** codes 중 하나로 시작하는 adm_cd의 읍·면·동만 남긴 FeatureCollection. */
    static String select(String geoJson, List<String> codes) {
        // 경계 파일 전체가 아니라 지정한 코드의 항목만 남긴 새 FeatureCollection을 만든다.
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode root = mapper.readTree(geoJson);
        ArrayNode kept = mapper.createArrayNode();
        // 읍·면·동 하나씩 adm_cd가 지정한 코드 앞자리 중 하나로 시작하는지 본다.
        for (JsonNode feature : root.path("features")) {
            String code = feature.path("properties").path("adm_cd").asString("");
            if (codes.stream().anyMatch(code::startsWith)) {
                kept.add(feature);
            }
        }
        // 맞는 지역이 없으면 코드 오타로 보고 멈춘다(빈 SQL을 만들지 않는다).
        if (kept.isEmpty()) {
            throw new RegionFileFormatException("--codes에 맞는 읍·면·동이 없습니다: " + codes);
        }
        // 남긴 항목으로 FeatureCollection을 다시 만들어 파서에 넘긴다.
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "FeatureCollection");
        result.set("features", kept);
        return mapper.writeValueAsString(result);
    }

    /**
     * 경계 원본을 받아 cacheDir에 둔다. 이미 있으면 다시 받지 않는다. 받다가 끊겨도 불완전한 파일이 남지 않게 .part 파일로 받은 뒤 옮긴다.
     */
    private static Path download(Path cacheDir) throws IOException {
        Path file = cacheDir.resolve("HangJeongDong_" + SOURCE_VERSION + ".geojson");
        if (Files.exists(file)) {
            return file;
        }
        // 캐시 폴더가 없으면 만든다.
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(file.getFileName() + ".part");
        System.out.println("경계 원본을 받습니다: " + SOURCE_URL);
        // 원본 주소가 다른 주소로 넘길 수 있어 리다이렉트를 따라간다.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(SOURCE_URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
            // 성공이 아니면 오류 페이지를 파일로 저장하지 않도록 바로 실패시킨다.
            if (response.statusCode() != 200) {
                throw new IOException("경계 원본을 받지 못했습니다: HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            // 인터럽트 상태를 되살린 뒤 입출력 예외로 바꿔 던진다.
            Thread.currentThread().interrupt();
            throw new IOException("경계 원본 받기가 중단되었습니다", e);
        }
        // 다 받은 뒤에 최종 이름으로 옮겨, 캐시에 있는 파일은 항상 완전한 파일이다.
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    // --이름=값 또는 --이름(값 없음)
    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("옵션은 --이름=값 형식입니다: " + arg);
            }
            int eq = arg.indexOf('=');
            options.put(eq < 0 ? arg.substring(2) : arg.substring(2, eq), eq < 0 ? "" : arg.substring(eq + 1));
        }
        return options;
    }

    /**
     * 필수 옵션의 값을 돌려준다. 없거나 비어 있으면 예외를 던진다.
     */
    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " 옵션이 필요합니다");
        }
        return value;
    }

    /**
     * --codes 값을 쉼표로 나눠 검사한다.
     */
    private static List<String> codes(String value) {
        List<String> codes = new ArrayList<>();
        for (String code : Arrays.stream(value.split(",")).map(String::trim).toList()) {
            // 시·도(2자리) 또는 시·군·구(5자리) 숫자만 허용한다. 읍·면·동(8자리)까지 직접 고르는 것은 받지 않는다.
            if (!code.matches("\\d{2}|\\d{5}")) {
                throw new IllegalArgumentException("--codes는 2자리(시·도) 또는 5자리(시·군·구) 숫자입니다: " + code);
            }
            codes.add(code);
        }
        return codes;
    }
}
