package com.whiteeyes.admin.event.service;

import com.whiteeyes.admin.account.service.AdminAccountLookupService;
import com.whiteeyes.admin.event.dto.EventExportRequest;
import com.whiteeyes.admin.event.dto.EventExportRow;
import com.whiteeyes.admin.event.dto.EventListItem;
import com.whiteeyes.admin.event.dto.EventSearchRequest;
import com.whiteeyes.admin.history.service.AdminWorkRecorder;
import com.whiteeyes.admin.region.dto.RegionNames;
import com.whiteeyes.admin.region.dto.ZoneSummary;
import com.whiteeyes.admin.region.service.RegionLookupService;
import com.whiteeyes.admin.region.service.ZoneService;
import com.whiteeyes.common.event.entity.Event;
import com.whiteeyes.common.event.repository.EventRepository;
import com.whiteeyes.common.event.repository.EventSpecifications;
import com.whiteeyes.common.exception.BusinessException;
import com.whiteeyes.common.exception.ErrorCode;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.security.LoginAccount;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트 엑셀 다운로드(FNC-A-039): 이벤트 목록(FNC-A-036)과 같은 조건으로 이벤트를 모아 엑셀로 만들고 작업 기록을 남긴다.
 * 처리 순서는 명세 그대로 ① 같은 조건으로 조회(최대 5만 건, 넘으면 400 TOO_MANY_ROWS) ② 열 작성 ③ 작업 기록
 * (EXCEL_DOWNLOAD, detail: kind=EVENT, 필터).
 */
@Service
@RequiredArgsConstructor
public class EventExportService {

    // 한 번에 내려받을 수 있는 최대 이벤트 수(FNC-A-039 처리 순서 1). 설정으로 바꿀 값이 아니라 명세의 고정값이다
    static final int MAX_ROWS = 50_000;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String NO_LIMIT_IN_FILE_NAME = "all";
    private static final String EXPORT_KIND = "EVENT";
    private static final String ALL_TARGET_NAME = "전체";

    private final AdminWorkRecorder adminWorkRecorder;
    private final EventConditionService eventConditionService;
    private final EventRepository eventRepository;
    private final EventListService eventListService;
    private final AdminAccountLookupService adminAccountLookupService;
    private final RegionLookupService regionLookupService;
    private final ZoneService zoneService;

    /**
     * 파일을 만들고 작업 기록을 남긴다. 받은 쪽이 {@link EventExportFile#writeTo}로 내보낸 뒤 {@link EventExportFile#close()}로
     * 임시 파일을 지워야 한다.
     *
     * @throws BusinessException 5만 건을 넘으면(400 TOO_MANY_ROWS), 조건이 올바르지 않으면(400 INVALID_INPUT)
     */
    @Transactional
    public EventExportFile export(EventExportRequest request) {
        // 기록할 수 없는 요청(로그인 없음)은 일을 하기 전에 거부한다
        LoginAccount actor = adminWorkRecorder.requireActor();
        List<Event> events = findEvents(request.toSearchRequest());

        EventExcelWriter writer = new EventExcelWriter();
        try {
            toRows(events).forEach(writer::append);
            adminWorkRecorder.record(
                    actor,
                    WorkAction.EXCEL_DOWNLOAD,
                    WorkTargetType.EVENT,
                    null,
                    targetNameOf(request),
                    detailOf(request));
        } catch (RuntimeException e) {
            closeQuietly(writer);
            throw e;
        }
        return new EventExportFile(fileNameOf(request), writer);
    }

    private List<Event> findEvents(EventSearchRequest search) {
        Optional<Specification<Event>> base = eventConditionService.toSpecification(search);
        if (base.isEmpty()) {
            return List.of();
        }
        Specification<Event> condition =
                Specification.allOf(base.get(), EventSpecifications.ofStatuses(search.statuses()));
        long total = eventRepository.count(condition);
        if (total > MAX_ROWS) {
            throw new BusinessException(
                    ErrorCode.TOO_MANY_ROWS,
                    "한 번에 최대 " + format(MAX_ROWS) + "건까지 내려받을 수 있습니다(해당 " + format(total)
                            + "건). 기간이나 조건을 좁혀 주세요.");
        }
        return eventRepository.findAll(condition, EventListService.LATEST_FIRST);
    }

    private List<EventExportRow> toRows(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<EventListItem> items = eventListService.toItems(events);
        Set<Long> handlerIds =
                events.stream().map(Event::getHandledBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> loginIds = adminAccountLookupService.findLoginIds(handlerIds);
        Map<Long, ZoneSummary> zones =
                zoneService.findSummaries(events.stream().map(Event::getZoneId).collect(Collectors.toSet()));
        Map<Long, RegionNames> regionNames = regionLookupService.findNames(
                zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet()));

        List<EventExportRow> rows = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            ZoneSummary zone = zones.get(event.getZoneId());
            RegionNames names = zone == null ? null : regionNames.get(zone.dongId());
            rows.add(new EventExportRow(
                    items.get(i),
                    names == null ? null : names.sido(),
                    names == null ? null : names.sigungu(),
                    names == null ? null : names.emd(),
                    event.getActionCode(),
                    event.getActionNote(),
                    event.getHandledBy() == null ? null : loginIds.get(event.getHandledBy()),
                    event.getHandledAt() == null ? null : toKstOffset(event.getHandledAt())));
        }
        return rows;
    }

    // 작업 기록의 대상 이름(histwr-001): 구역 조건이면 구역 이름, 지역 조건이면 지역 이름, 둘 다 없으면 '전체'
    private String targetNameOf(EventExportRequest request) {
        String name = null;
        if (request.zoneId() != null) {
            ZoneSummary zone = zoneService.findSummaries(Set.of(request.zoneId())).get(request.zoneId());
            name = zone == null ? null : zone.name();
        } else if (request.regionId() != null) {
            name = regionLookupService.findName(request.regionId());
        }
        return name == null ? ALL_TARGET_NAME : name;
    }

    // 다운로드 조건을 그대로 남긴다. 비운 조건은 적지 않는다(histwr-001)
    private static Map<String, Object> detailOf(EventExportRequest request) {
        Map<String, Object> filter = new LinkedHashMap<>();
        putIfPresent(filter, "from", isoOf(request.from()));
        putIfPresent(filter, "to", isoOf(request.to()));
        putIfPresent(filter, "regionId", request.regionId());
        putIfPresent(filter, "zoneId", request.zoneId());
        if (!request.types().isEmpty()) {
            filter.put("types", request.types().stream().map(Enum::name).toList());
        }
        if (!request.statuses().isEmpty()) {
            filter.put("statuses", request.statuses().stream().map(Enum::name).toList());
        }
        putIfPresent(filter, "keyword", request.keyword());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", EXPORT_KIND);
        detail.put("filter", filter);
        return detail;
    }

    private static String isoOf(OffsetDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // events_{from}_{to}.xlsx. 날짜는 yyyyMMdd이고 to는 미만이라 마지막으로 들어간 날(to에서 1분 뺀 날). 비운 쪽은 all
    private static String fileNameOf(EventExportRequest request) {
        String from = request.from() == null
                ? NO_LIMIT_IN_FILE_NAME
                : request.from().atZoneSameInstant(KST).format(FILE_DATE);
        String to = request.to() == null
                ? NO_LIMIT_IN_FILE_NAME
                : request.to().minusMinutes(1).atZoneSameInstant(KST).format(FILE_DATE);
        return "events_" + from + "_" + to + ".xlsx";
    }

    // DB의 KST 시각에 +09:00을 붙인다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }

    private static String format(long number) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(number);
    }

    private static void closeQuietly(EventExcelWriter writer) {
        try {
            writer.close();
        } catch (IOException e) {
            // 원래 오류를 가리지 않도록 임시 파일 정리 실패는 무시한다
        }
    }

    /** 만든 파일. 내보낸 뒤 반드시 닫아 임시 파일을 지운다. */
    public static final class EventExportFile implements AutoCloseable {

        private final String fileName;
        private final EventExcelWriter writer;

        private EventExportFile(String fileName, EventExcelWriter writer) {
            this.fileName = fileName;
            this.writer = writer;
        }

        public String fileName() {
            return fileName;
        }

        public void writeTo(OutputStream out) throws IOException {
            writer.writeTo(out);
        }

        @Override
        public void close() {
            try {
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
