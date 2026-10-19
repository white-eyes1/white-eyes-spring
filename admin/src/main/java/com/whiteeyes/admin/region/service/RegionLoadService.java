package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.region.dto.RegionSource;
import com.whiteeyes.admin.region.exception.RegionFileFormatException;
import com.whiteeyes.common.region.entity.Region;
import com.whiteeyes.common.region.entity.RegionLevel;
import com.whiteeyes.common.region.repository.RegionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegionLoadService {

    private static final Logger log = LoggerFactory.getLogger(RegionLoadService.class);

    private final RegionGeoJsonParser parser;
    private final RegionRepository regionRepository;

    public RegionLoadService(RegionGeoJsonParser parser, RegionRepository regionRepository) {
        this.parser = parser;
        this.regionRepository = regionRepository;
    }

    /**
     * 경계 파일을 region에 넣는다. code가 같은 행은 덮어쓰므로 다시 실행해도 중복이 없다.
     * 파일을 모두 검증한 뒤 한 트랜잭션에서 저장하므로 오류가 나면 아무것도 저장되지 않는다(FNC-A-012).
     *
     * @return 적재(생성·갱신)한 행정구역 수
     */
    @Transactional
    public int load(Path file) {
        List<RegionSource> sources = parser.parse(read(file));

        // 상위(시 → 구 → 동)부터 저장해야 parent_id를 연결할 수 있다
        List<RegionSource> ordered = sources.stream()
                .sorted(Comparator.comparing(RegionSource::level))
                .toList();
        Map<String, Region> existing = regionRepository
                .findByCodeIn(ordered.stream().map(RegionSource::code).toList())
                .stream()
                .collect(Collectors.toMap(Region::getCode, region -> region));
        Map<String, Region> loaded = new HashMap<>();

        for (RegionSource source : ordered) {
            Region parent = source.parentCode() == null ? null : loaded.get(source.parentCode());
            Region region = existing.get(source.code());
            if (region == null) {
                region = regionRepository.save(new Region(
                        parent,
                        source.level(),
                        source.code(),
                        source.name(),
                        source.boundary(),
                        source.centerLat(),
                        source.centerLng()));
            } else {
                region.overwrite(
                        parent,
                        source.level(),
                        source.name(),
                        source.boundary(),
                        source.centerLat(),
                        source.centerLng());
            }
            loaded.put(source.code(), region);
        }

        log.info(
                "행정구역 적재 완료 시={} 구={} 동={}",
                count(ordered, RegionLevel.SI),
                count(ordered, RegionLevel.GU),
                count(ordered, RegionLevel.DONG));
        return ordered.size();
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RegionFileFormatException("파일을 읽을 수 없습니다: " + file, e);
        }
    }

    private long count(List<RegionSource> sources, RegionLevel level) {
        return sources.stream().filter(source -> source.level() == level).count();
    }
}
