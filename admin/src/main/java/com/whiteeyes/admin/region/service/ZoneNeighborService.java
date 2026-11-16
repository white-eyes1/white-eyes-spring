package com.whiteeyes.admin.region.service;

import java.util.ArrayList;
import java.util.Collection;
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

/** 구역의 동서남북 이웃을 조회·저장한다(FNC-A-016). 저장하면 반대쪽 구역의 반대 방향도 함께 맞춘다. */
@Service
@RequiredArgsConstructor
public class ZoneNeighborService {

    // 구역 존재·삭제 여부 확인과 이웃 구역 이름 조회에 쓴다
    private final ZoneRepository zoneRepository;
    // 방향별 이웃 행(zone_neighbor) 저장소
    private final ZoneNeighborRepository zoneNeighborRepository;
    // 관리자 작업 기록 저장과 로그인 계정 확인을 맡는다
    private final AdminWorkRecorder workRecorder;

    /** 4방향 이웃을 N·E·S·W 순서로 돌려준다. 이웃이 없는 방향은 null(관측 범위 밖). 없거나 삭제된 구역이면 404. */
    @Transactional(readOnly = true)
    public Map<String, ZoneNeighborRef> find(Long zoneId) {
        requireActiveZone(zoneId);
        // 없거나 삭제된 구역이면 위에서 404가 났다. 여기서는 이웃 행을 읽어 응답 형태로 바꾼다
        return toResponse(loadNeighborIds(zoneId));
    }

    /**
     * 주어진 구역들의 방향별 이웃 번호를 구역 번호 → (방향 → 이웃 번호)로 돌려준다(시연용 통계의 구역 간 이동, FNC-A-051).
     * 이웃이 하나도 없는 구역은 빠진다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<ZoneDirection, Long>> findNeighborIds(Collection<Long> zoneIds) {
        // 구역 번호 → (방향 → 이웃 번호). 이웃 행이 없는 구역은 키 자체가 만들어지지 않는다
        Map<Long, Map<ZoneDirection, Long>> result = new HashMap<>();
        for (ZoneNeighbor neighbor : zoneNeighborRepository.findByZoneIdIn(zoneIds)) {
            // 구역별 맵을 처음 만나는 순간 만들고 방향별 이웃 번호를 채운다
            result.computeIfAbsent(neighbor.getZoneId(), zoneId -> new EnumMap<>(ZoneDirection.class))
                    .put(neighbor.getDirection(), neighbor.getNeighborId());
        }
        return result;
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
        // 비우려는 방향(null)은 검사할 이웃이 없으므로 걸러내고, 같은 이웃이 여러 방향에 있어도 한 번만 검사하려고 Set으로 모은다
        Set<Long> neighborIds =
                requested.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        // 자기 자신을 이웃으로 삼으면 방향 쌍(반대 방향)이 자기 자신을 가리키게 되어 의미가 없다
        if (neighborIds.contains(zoneId)) {
            throw new InvalidZoneNeighborException("자기 자신은 이웃으로 지정할 수 없습니다.");
        }
        // 존재하고 삭제되지 않은 이웃만 모은다(한 번의 조회로 전부 확인)
        Set<Long> activeIds = zoneRepository.findAllById(neighborIds).stream()
                .filter(neighbor -> neighbor.getDeletedAt() == null)
                .map(Zone::getId)
                .collect(Collectors.toSet());
        for (Long neighborId : neighborIds) {
            // 없거나 삭제된 구역을 이웃으로 지정할 수 없다. 어느 번호가 문제인지 예외에 담는다
            if (!activeIds.contains(neighborId)) {
                throw new ZoneNotFoundException(neighborId);
            }
        }

        // 처리 순서 2·3: 방향별로 저장/삭제하고 반대쪽도 맞춘다
        // 변경을 메모리에서 먼저 모두 계산한 뒤 마지막에 DB에 반영하는 보조 객체(아래 NeighborBook 참고)
        NeighborBook book = new NeighborBook();
        for (ZoneDirection direction : ZoneDirection.values()) {
            // 요청에 없는 방향은 get이 null을 돌려주므로 '이웃 없음'으로 저장된다
            book.set(zoneId, direction, requested.get(direction));
        }
        // 바뀐 행만 한꺼번에 지우고 저장한다
        book.flush();

        // 처리 순서 4: 작업 기록(바뀜 방향 목록). 같은 트랜잭션이라 기록이 실패하면 이웃 저장도 취소된다
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "NEIGHBORS");
        // 직접 바꾼 구역의 방향별 변경 내용
        detail.put("changes", book.changesOf(zoneId));
        // 반대쪽 자동 맞춤으로 함께 바뀐 다른 구역들의 변경 내용
        detail.put("mirrored", book.mirroredChanges(zoneId));
        workRecorder.record(actor, WorkAction.ZONE_CHANGE, WorkTargetType.ZONE, zoneId, zone.getName(), detail);
        // 저장 뒤 메모리 상태를 그대로 응답으로 만든다(다시 조회하지 않는다)
        return toResponse(book.current(zoneId));
    }

    /** 구역을 읽어 오되, 없거나 삭제 표시된 구역이면 404(ZoneNotFoundException)를 던진다. */
    private Zone requireActiveZone(Long zoneId) {
        return zoneRepository
                .findById(zoneId)
                .filter(zone -> zone.getDeletedAt() == null)
                .orElseThrow(() -> new ZoneNotFoundException(zoneId));
    }

    /** 한 구역의 이웃 행을 읽어 방향 → 이웃 번호로 만든다. */
    private Map<ZoneDirection, Long> loadNeighborIds(Long zoneId) {
        Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
        zoneNeighborRepository
                .findByZoneIdIn(List.of(zoneId))
                .forEach(row -> result.put(row.getDirection(), row.getNeighborId()));
        return result;
    }

    /** 방향 → 이웃 번호를, 4방향이 모두 키로 들어 있는 N·E·S·W → 이웃 요약(id, 이름)으로 바꾼다. 이웃이 없는 방향 값은 null이다. */
    private Map<String, ZoneNeighborRef> toResponse(Map<ZoneDirection, Long> neighborIds) {
        // 이웃 구역을 한 번에 읽어 번호 → 구역으로 만든다(이름을 채우려는 목적)
        Map<Long, Zone> zones = zoneRepository.findAllById(neighborIds.values()).stream()
                .collect(Collectors.toMap(Zone::getId, zone -> zone));
        // LinkedHashMap으로 N, E, S, W 순서를 유지해 응답 JSON 순서가 항상 같게 한다
        Map<String, ZoneNeighborRef> response = new LinkedHashMap<>();
        for (ZoneDirection direction : ZoneDirection.values()) {
            // 방향에 이웃이 없으면 neighborIds.get이 null이고, 그러면 맵 조회도 null이 된다
            Zone neighbor = zones.get(neighborIds.get(direction));
            response.put(
                    direction.name(),
                    neighbor == null ? null : new ZoneNeighborRef(neighbor.getId(), neighbor.getName()));
        }
        return response;
    }

    /**
     * 이번 저장에서 건드리는 구역들의 이웃 행을 메모리에 올려 두고 고친 뒤, 바뀐 행만 한꺼번에 반영한다.
     * 한 구역의 방향을 바꾸면 옛 이웃의 반대 방향, 새 이웃의 반대 방향(거기 있던 다른 구역의 원래 방향까지)이 같이 바뀐다.
     */
    private final class NeighborBook {

        // 구역 번호 → (방향 → 이웃 행). 읽어 온 행을 수정해 가며 최종 상태를 담는다
        private final Map<Long, Map<ZoneDirection, ZoneNeighbor>> rows = new HashMap<>();
        // 구역 번호 → (방향 → 처음 읽었을 때 이웃 번호). 무엇이 바뀌었는지 비교하는 기준이다
        private final Map<Long, Map<ZoneDirection, Long>> before = new HashMap<>();

        /** zoneId의 direction 이웃을 neighborId(null이면 비움)로 바꾸고 반대쪽도 맞춘다. */
        void set(Long zoneId, ZoneDirection direction, Long neighborId) {
            // 이 방향에 원래 있던 이웃
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

        /** 한 구역의 현재(수정 반영 후) 방향 → 이웃 번호. */
        Map<ZoneDirection, Long> current(Long zoneId) {
            Map<ZoneDirection, Long> result = new EnumMap<>(ZoneDirection.class);
            load(zoneId).forEach((direction, row) -> result.put(direction, row.getNeighborId()));
            return result;
        }

        /** 한 구역의 바뀐 방향: [{direction, before, after}] */
        List<Map<String, Object>> changesOf(Long zoneId) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ZoneDirection direction : ZoneDirection.values()) {
                // 처음 읽었을 때의 값(없으면 null)과 지금 값을 비교한다
                Long was = before.get(zoneId).get(direction);
                Long now = neighbor(zoneId, direction);
                // null끼리도 같다고 보려고 Objects.equals를 쓴다
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
            // 번호 순으로 돌려 작업 기록 내용이 항상 같은 순서가 되게 한다
            for (Long id : rows.keySet().stream().sorted().toList()) {
                // 직접 바꾼 구역은 changesOf로 따로 기록하므로 여기서는 뺀다
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
            // 비워진 방향은 삭제 대상, 새로 생기거나 이웃이 바뀐 방향은 저장 대상으로 나눈다
            List<ZoneNeighborId> toDelete = new ArrayList<>();
            List<ZoneNeighbor> toSave = new ArrayList<>();
            for (Map.Entry<Long, Map<ZoneDirection, ZoneNeighbor>> zoneEntry : rows.entrySet()) {
                Long zoneId = zoneEntry.getKey();
                for (ZoneDirection direction : ZoneDirection.values()) {
                    Long was = before.get(zoneId).get(direction);
                    ZoneNeighbor row = zoneEntry.getValue().get(direction);
                    Long now = row == null ? null : row.getNeighborId();
                    // 바뀌지 않은 행은 DB를 건드리지 않는다
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
            // 삭제를 먼저 반영한 뒤 저장한다
            zoneNeighborRepository.deleteAllById(toDelete);
            zoneNeighborRepository.saveAll(toSave);
        }

        /** zoneId의 direction 이웃이 pointsTo일 때만 비운다. 이미 다른 구역을 가리키고 있으면 건드리지 않는다. */
        private void unlinkIfPointsTo(Long zoneId, ZoneDirection direction, Long pointsTo) {
            if (pointsTo.equals(neighbor(zoneId, direction))) {
                put(zoneId, direction, null);
            }
        }

        /** 메모리에 올라온 상태에서 zoneId의 direction 이웃 번호를 읽는다(없으면 null). */
        private Long neighbor(Long zoneId, ZoneDirection direction) {
            ZoneNeighbor row = load(zoneId).get(direction);
            return row == null ? null : row.getNeighborId();
        }

        /** 메모리 상태의 한 칸을 바꾼다. neighborId가 null이면 비우고, 행이 있으면 이웃만 고치고, 없으면 새 행을 만든다. */
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

        /** 구역의 이웃 행을 처음 필요할 때 한 번만 DB에서 읽어 오고, 그때의 값을 before에 기록해 둔다. */
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
