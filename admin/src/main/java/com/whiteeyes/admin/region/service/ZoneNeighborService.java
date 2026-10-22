package com.whiteeyes.admin.region.service;

import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.ZoneNeighborRef;
import com.whiteeyes.admin.region.exception.InvalidZoneNeighborException;
import com.whiteeyes.admin.region.exception.ZoneNotFoundException;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.region.entity.Zone;
import com.whiteeyes.common.region.entity.ZoneDirection;
import com.whiteeyes.common.region.entity.ZoneNeighbor;
import com.whiteeyes.common.region.entity.ZoneNeighborId;
import com.whiteeyes.common.region.repository.ZoneNeighborRepository;
import com.whiteeyes.common.region.repository.ZoneRepository;
import com.whiteeyes.common.security.LoginAccount;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구역의 동서남북 이웃을 조회·저장한다(FNC-A-016). 저장하면 반대쪽 구역의 반대 방향도 함께 맞춘다. */
@Service
@RequiredArgsConstructor
public class ZoneNeighborService {

    private final ZoneRepository zoneRepository;
    private final ZoneNeighborRepository zoneNeighborRepository;
    private final AdminWorkRecorder workRecorder;

    /** 4방향 이웃을 N·E·S·W 순서로 돌려준다. 이웃이 없는 방향은 null(관측 범위 밖). 없거나 삭제된 구역이면 404. */
    @Transactional(readOnly = true)
    public Map<String, ZoneNeighborRef> find(Long zoneId) {
        requireActiveZone(zoneId);
        return toResponse(loadNeighborIds(zoneId));
    }

    /**
     * 4방향 이웃을 저장한다. 요청에 없는 방향은 이웃 없음으로 본다.
     *
     * @param requested 방향 → 이웃 구역 번호(비우려면 null)
     */
    @Transactional
    public Map<String, ZoneNeighborRef> save(Long zoneId, Map<ZoneDirection, Long> requested) {
        // 로그인하지 않은 요청은 저장하기 전에 거부한다(작업 기록의 행동한 계정을 채울 수 없다)
        LoginAccount actor = workRecorder.requireActor();
        // 처리 순서 1: 구역과 이웃 구역의 존재·삭제 여부·자기 자신 여부 확인
        Zone zone = requireActiveZone(zoneId);
        Set<Long> neighborIds = requested.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (neighborIds.contains(zoneId)) {
            throw new InvalidZoneNeighborException("자기 자신은 이웃으로 지정할 수 없습니다.");
        }
        Set<Long> activeIds = zoneRepository.findAllById(neighborIds).stream()
                .filter(neighbor -> neighbor.getDeletedAt() == null)
                .map(Zone::getId)
                .collect(Collectors.toSet());
        for (Long neighborId : neighborIds) {
            if (!activeIds.contains(neighborId)) {
                throw new ZoneNotFoundException(neighborId);
            }
        }

        // 처리 순서 2·3: 방향별로 저장/삭제하고 반대쪽도 맞춘다
        NeighborBook book = new NeighborBook();
        for (ZoneDirection direction : ZoneDirection.values()) {
            book.set(zoneId, direction, requested.get(direction));
        }
        book.flush();

        // 처리 순서 4: 작업 기록(바뀜 방향 목록). 같은 트랜잭션이라 기록이 실패하면 이웃 저장도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "NEIGHBORS");
        detail.put("changes", book.changesOf(zoneId));
        detail.put("mirrored", book.mirroredChanges(zoneId));
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zoneId, zone.getName(), detail);
        return toResponse(book.current(zoneId));
    }

    private Zone requireActiveZone(Long zoneId) {
        return zoneRepository
                .findById(zoneId)
                .filter(zone -> zone.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
    }

    private Map<ZoneDirection, Long> loadNeighborIds(Long zoneId) {
        Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
        zoneNeighborRepository
                .findByZoneIdIn(List.of(zoneId))
                .forEach(row -> result.put(row.getDirection(), row.getNeighborId()));
        return result;
    }

    private Map<String, ZoneNeighborRef> toResponse(Map<ZoneDirection, Long> neighborIds) {
        Map<Long, Zone> zones = zoneRepository.findAllById(neighborIds.values()).stream()
                .collect(Collectors.toMap(Zone::getId, zone -> zone));
        Map<String, ZoneNeighborRef> response = new LinkedHashMap<>();
        for (ZoneDirection direction : ZoneDirection.values()) {
            Zone neighbor = zones.get(neighborIds.get(direction));
            response.put(direction.name(), neighbor == null ? null : new ZoneNeighborRef(neighbor.getId(), neighbor.getName()));
        }
        return response;
    }

    /**
     * 이번 저장에서 건드리는 구역들의 이웃 행을 메모리에 올려 두고 고친 뒤, 바뀐 행만 한꺼번에 반영한다.
     * 한 구역의 방향을 바꾸면 옛 이웃의 반대 방향, 새 이웃의 반대 방향(거기 있던 다른 구역의 원래 방향까지)이 같이 바뀐다.
     */
    private final class NeighborBook {

        private final Map<Long, Map<ZoneDirection, ZoneNeighbor>> rows = new HashMap<>();
        private final Map<Long, Map<ZoneDirection, Long>> before = new HashMap<>();

        /** zoneId의 direction 이웃을 neighborId(null이면 비움)로 바꾸고 반대쪽도 맞춘다. */
        void set(Long zoneId, ZoneDirection direction, Long neighborId) {
            Long old = neighbor(zoneId, direction);
            if (old != null && !old.equals(neighborId)) {
                // 옛 이웃의 반대 방향이 나를 가리키고 있었다면 비운다
                unlinkIfPointsTo(old, direction.opposite(), zoneId);
            }
            if (neighborId == null) {
                put(zoneId, direction, null);
                return;
            }
            // 새 이웃의 반대 방향에 다른 구역 C가 있었다면 C의 direction도 비운다
            Long other = neighbor(neighborId, direction.opposite());
            if (other != null && !other.equals(zoneId)) {
                unlinkIfPointsTo(other, direction, neighborId);
            }
            put(zoneId, direction, neighborId);
            put(neighborId, direction.opposite(), zoneId);
        }

        Map<ZoneDirection, Long> current(Long zoneId) {
            Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
            load(zoneId).forEach((direction, row) -> result.put(direction, row.getNeighborId()));
            return result;
        }

        /** 한 구역의 바뀐 방향: [{direction, before, after}] */
        List<Map<String, Object>> changesOf(Long zoneId) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ZoneDirection direction : ZoneDirection.values()) {
                Long was = before.get(zoneId).get(direction);
                Long now = neighbor(zoneId, direction);
                if (!Objects.equals(was, now)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("direction", direction.name());
                    item.put("before", was);
                    item.put("after", now);
                    result.add(item);
                }
            }
            return result;
        }

        /** 반대쪽 자동 반영으로 바뀐 다른 구역의 방향: [{zoneId, direction, before, after}] */
        List<Map<String, Object>> mirroredChanges(Long zoneId) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Long id : rows.keySet().stream().sorted().toList()) {
                if (id.equals(zoneId)) {
                    continue;
                }
                for (Map<String, Object> change : changesOf(id)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("zoneId", id);
                    item.putAll(change);
                    result.add(item);
                }
            }
            return result;
        }

        /** 바뀐 행만 지우고 쓴다. */
        void flush() {
            List<ZoneNeighborId> toDelete = new ArrayList<>();
            List<ZoneNeighbor> toSave = new ArrayList<>();
            for (Map.Entry<Long, Map<ZoneDirection, ZoneNeighbor>> zoneEntry : rows.entrySet()) {
                Long zoneId = zoneEntry.getKey();
                for (ZoneDirection direction : ZoneDirection.values()) {
                    Long was = before.get(zoneId).get(direction);
                    ZoneNeighbor row = zoneEntry.getValue().get(direction);
                    Long now = row == null ? null : row.getNeighborId();
                    if (Objects.equals(was, now)) {
                        continue;
                    }
                    if (now == null) {
                        toDelete.add(new ZoneNeighborId(zoneId, direction));
                    } else {
                        toSave.add(row);
                    }
                }
            }
            zoneNeighborRepository.deleteAllById(toDelete);
            zoneNeighborRepository.saveAll(toSave);
        }

        private void unlinkIfPointsTo(Long zoneId, ZoneDirection direction, Long pointsTo) {
            if (pointsTo.equals(neighbor(zoneId, direction))) {
                put(zoneId, direction, null);
            }
        }

        private Long neighbor(Long zoneId, ZoneDirection direction) {
            ZoneNeighbor row = load(zoneId).get(direction);
            return row == null ? null : row.getNeighborId();
        }

        private void put(Long zoneId, ZoneDirection direction, Long neighborId) {
            Map<ZoneDirection, ZoneNeighbor> zoneRows = load(zoneId);
            if (neighborId == null) {
                zoneRows.remove(direction);
                return;
            }
            ZoneNeighbor row = zoneRows.get(direction);
            if (row == null) {
                zoneRows.put(direction, new ZoneNeighbor(zoneId, direction, neighborId));
            } else {
                row.changeNeighbor(neighborId);
            }
        }

        private Map<ZoneDirection, ZoneNeighbor> load(Long zoneId) {
            return rows.computeIfAbsent(zoneId, id -> {
                Map<ZoneDirection, ZoneNeighbor> loaded = new EnumMap<>(ZoneDirection.class);
                Map<ZoneDirection, Long> snapshot = new EnumMap<>(ZoneDirection.class);
                zoneNeighborRepository.findByZoneIdIn(List.of(id)).forEach(row -> {
                    loaded.put(row.getDirection(), row);
                    snapshot.put(row.getDirection(), row.getNeighborId());
                });
                before.put(id, snapshot);
                return loaded;
            });
        }
    }
}
