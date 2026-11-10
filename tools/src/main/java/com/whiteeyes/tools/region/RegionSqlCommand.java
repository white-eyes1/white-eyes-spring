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
    private static final int OK = 0;
    private static final int FAILED = 1;

    private RegionSqlCommand() {}

    public static int run(String[] args) {
        try {
            Map<String, String> options = options(args);
            List<String> codes = codes(required(options, "codes"));
            Path out = Path.of(required(options, "out"));
            Path file = options.containsKey("file")
                    ? Path.of(options.get("file"))
                    : download(Path.of(options.getOrDefault("cache", "build/region-source")));
            boolean h2 = options.containsKey("h2");

            String selected = select(Files.readString(file, StandardCharsets.UTF_8), codes);
            List<RegionSource> sources = new RegionGeoJsonParser().parse(selected);
            List<String> header = List.of(
                    "행정구역(FNC-A-012). tools의 region-sql 명령으로 만든 파일이다. 손으로 고치지 않는다.",
                    "출처: 통계청 SGIS 행정구역 경계, vuski/admdongkor " + SOURCE_VERSION + " (CC BY 4.0)",
                    "명령: region-sql --codes=" + String.join(",", codes) + (h2 ? " --h2" : ""),
                    "지역 수: " + sources.size());
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, RegionSqlWriter.write(header, sources, h2), StandardCharsets.UTF_8);
            System.out.println(out + ": 지역 " + sources.size() + "개");
            return OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("region-sql 실패: " + e.getMessage());
            return FAILED;
        }
    }

    /** codes 중 하나로 시작하는 adm_cd의 읍·면·동만 남긴 FeatureCollection. */
    static String select(String geoJson, List<String> codes) {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode root = mapper.readTree(geoJson);
        ArrayNode kept = mapper.createArrayNode();
        for (JsonNode feature : root.path("features")) {
            String code = feature.path("properties").path("adm_cd").asString("");
            if (codes.stream().anyMatch(code::startsWith)) {
                kept.add(feature);
            }
        }
        if (kept.isEmpty()) {
            throw new RegionFileFormatException("--codes에 맞는 읍·면·동이 없습니다: " + codes);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("type", "FeatureCollection");
        result.set("features", kept);
        return mapper.writeValueAsString(result);
    }

    private static Path download(Path cacheDir) throws IOException {
        Path file = cacheDir.resolve("HangJeongDong_" + SOURCE_VERSION + ".geojson");
        if (Files.exists(file)) {
            return file;
        }
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(file.getFileName() + ".part");
        System.out.println("경계 원본을 받습니다: " + SOURCE_URL);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(SOURCE_URL)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("경계 원본을 받지 못했습니다: HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("경계 원본 받기가 중단되었습니다", e);
        }
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

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " 옵션이 필요합니다");
        }
        return value;
    }

    private static List<String> codes(String value) {
        List<String> codes = new ArrayList<>();
        for (String code : Arrays.stream(value.split(",")).map(String::trim).toList()) {
            if (!code.matches("\\d{2}|\\d{5}")) {
                throw new IllegalArgumentException("--codes는 2자리(시·도) 또는 5자리(시·군·구) 숫자입니다: " + code);
            }
            codes.add(code);
        }
        return codes;
    }
}
