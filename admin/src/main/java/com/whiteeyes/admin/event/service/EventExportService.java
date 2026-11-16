package com.whiteeyes.admin.event.service;

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

/**
 * 이벤트 엑셀 다운로드(FNC-A-039): 이벤트 목록(FNC-A-036)과 같은 조건으로 이벤트를 모아 엑셀로 만들고 작업 기록을 남긴다.
 * 처리 순서는 명세 그대로 ① 같은 조건으로 조회(최대 5만 건, 넘으면 400 TOO_MANY_ROWS) ② 열 작성 ③ 작업 기록
 * (EXCEL_DOWNLOAD, detail: kind=EVENT, 필터).
 */
@Service
@RequiredArgsConstructor
public class EventExportService {

    // 한 번에 내려받을 수 있는 최대 이벤트 수(FNC-A-039 처리 순서 1). 설정으로 바꿀 값이 아니라 명세의 고정값이다
    // (패키지 공개: 같은 패키지의 테스트에서도 한도를 확인한다)
    static final int MAX_ROWS = 50_000;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 파일 이름에 넣는 날짜 형식
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 기간의 한쪽을 비웠을 때 파일 이름에 넣는 글자
    private static final String NO_LIMIT_IN_FILE_NAME = "all";
    // 작업 기록 detail에 남기는 내려받기 종류(이벤트)
    private static final String EXPORT_KIND = "EVENT";
    // 구역·지역 조건이 없을 때 작업 기록의 대상 이름
    private static final String ALL_TARGET_NAME = "전체";

    // 필드: 작업 기록 남기기, 조회 조건 만들기, 이벤트 저장소, 목록 행 변환, 처리자 아이디·구역·지역 이름 조회
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
    // 흐름: 로그인 확인 -> 조회 -> 엑셀 작성 -> 작업 기록. 하나의 트랜잭션 안에서 해서 기록 저장이 실패하면 함께 되돌린다
    public EventExportFile export(EventExportRequest request) {
        // 기록할 수 없는 요청(로그인 없음)은 일을 하기 전에 거부한다
        // 누가 내려받는지 알아야 기록할 수 있으므로 가장 먼저 확인한다
        LoginAccount actor = adminWorkRecorder.requireActor();
        // 목록과 같은 조건으로 이벤트를 모은다(5만 건 초과면 여기서 예외)
        List<Event> events = findEvents(request.toSearchRequest());

        // 엑셀 작성기. 임시 파일을 쓰므로 실패 시 직접 닫아 줘야 한다
        EventExcelWriter writer = new EventExcelWriter();
        // 행 추가와 작업 기록 중 하나라도 실패하면 아래 catch에서 임시 파일을 정리한다
        try {
            // 이벤트마다 한 행씩 엑셀에 쓴다
            toRows(events).forEach(writer::append);
            // 엑셀 다운로드 작업 기록(EXCEL_DOWNLOAD). 대상 이름과 필터 조건을 남긴다
            adminWorkRecorder.record(
                    actor,
                    WorkAction.EXCEL_DOWNLOAD,
                    WorkTargetType.EVENT,
                    null,
                    targetNameOf(request),
                    detailOf(request));
            // 정리 후 원래 예외를 그대로 다시 던진다
        } catch (RuntimeException e) {
            closeQuietly(writer);
            throw e;
        }
        // 성공하면 파일을 닫지 않고 돌려준다. 내보내기는 컨트롤러가 하고 닫기도 컨트롤러의 try-with-resources가 한다
        return new EventExportFile(fileNameOf(request), writer);
    }

    /**
     * 조건에 맞는 이벤트를 모두 가져온다. 목록과 달리 페이지 없이 전부(상한 5만 건)이며 정렬은 목록과 같다.
     */
    private List<Event> findEvents(EventSearchRequest search) {
        // 기간·종류·구역 조건. 맞는 구역이 없으면 비어 있다
        Optional<Specification<Event>> base = eventConditionService.toSpecification(search);
        // 맞을 수 있는 이벤트가 없으니 빈 엑셀(머리글만)이 된다
        if (base.isEmpty()) {
            return List.of();
        }
        // 처리 상태 조건은 공통 조건에 없으므로 여기서 덧붙인다(요약이 상태를 무시하기 때문)
        Specification<Event> condition =
                Specification.allOf(base.get(), EventSpecifications.ofStatuses(search.statuses()));
        // 먼저 건수만 세어, 너무 많으면 실제 조회·엑셀 작성 전에 거절한다(메모리·시간 보호)
        long total = eventRepository.count(condition);
        if (total > MAX_ROWS) {
            throw new BusinessException(
                    ErrorCode.TOO_MANY_ROWS,
                    "한 번에 최대 " + format(MAX_ROWS) + "건까지 내려받을 수 있습니다(해당 " + format(total) + "건). 기간이나 조건을 좁혀 주세요.");
        }
        return eventRepository.findAll(condition, EventListService.LATEST_FIRST);
    }

    /**
     * 이벤트들을 엑셀 행으로 바꾼다. 구역·지역·처리자 이름은 건마다 읽지 않고 한 번에 모아서 읽는다(N+1 방지).
     */
    private List<EventExportRow> toRows(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        // 목록 화면과 같은 변환 결과. events와 같은 순서이므로 아래에서 같은 인덱스로 짝짓는다
        List<EventListItem> items = eventListService.toItems(events);
        // 처리한 관리자 번호들(처리 전 이벤트는 null이라 제외)
        Set<Long> handlerIds = events.stream()
                .map(Event::getHandledBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 관리자 번호 -> 로그인 아이디
        Map<Long, String> loginIds = adminAccountLookupService.findLoginIds(handlerIds);
        // 구역 번호 -> 구역 요약(소속 동 번호 포함)
        Map<Long, ZoneSummary> zones =
                zoneService.findSummaries(events.stream().map(Event::getZoneId).collect(Collectors.toSet()));
        // 동 번호 -> 시·도, 시·군·구, 읍·면·동 이름
        Map<Long, RegionNames> regionNames = regionLookupService.findNames(
                zones.values().stream().map(ZoneSummary::dongId).collect(Collectors.toSet()));

        // 처리 정보와 지역 이름을 목록 행에 붙여 엑셀 행을 만든다
        List<EventExportRow> rows = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            ZoneSummary zone = zones.get(event.getZoneId());
            // 구역이나 지역을 못 찾으면(예: 정보가 사라진 경우) 이름 칸은 null로 두어 비운다
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
    // (위 주석 참고) 구역 번호가 우선이고, 없으면 지역 번호
    private String targetNameOf(EventExportRequest request) {
        String name = null;
        if (request.zoneId() != null) {
            // 삭제된 구역도 요약에 포함되므로 이름을 얻을 수 있다
            ZoneSummary zone =
                    zoneService.findSummaries(Set.of(request.zoneId())).get(request.zoneId());
            name = zone == null ? null : zone.name();
        } else if (request.regionId() != null) {
            name = regionLookupService.findName(request.regionId());
        }
        return name == null ? ALL_TARGET_NAME : name;
    }

    // 다운로드 조건을 그대로 남긴다. 비운 조건은 적지 않는다(histwr-001)
    // (위 주석 참고) LinkedHashMap으로 넣은 순서를 유지해 기록의 JSON 순서가 일정하다
    private static Map<String, Object> detailOf(EventExportRequest request) {
        // 조건 항목 모음
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
        if (request.overlap()) {
            filter.put("overlap", true);
        }

        // 최종 detail: 내려받기 종류와 필터
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", EXPORT_KIND);
        detail.put("filter", filter);
        return detail;
    }

    // 시각을 ISO-8601 문자열(오프셋 포함)로. 없으면 null
    private static String isoOf(OffsetDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    // 값이 있을 때만 맵에 넣는다(비운 조건은 기록에 남기지 않는다)
    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    // events_{from}_{to}.xlsx. 날짜는 yyyyMMdd이고 to는 미만이라 마지막으로 들어간 날(to에서 1분 뺀 날). 비운 쪽은 all
    // (위 주석 참고) to는 '미만' 경계라 화면에서 고른 마지막 날이 되도록 1분을 빼서 날짜를 얻는다
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
    // (위 주석 참고) 엑셀에는 시간대 없이 KST 시각으로 쓰이므로 목록과 같은 기준을 유지한다
    private static OffsetDateTime toKstOffset(LocalDateTime time) {
        return time.atZone(KST).toOffsetDateTime();
    }

    /** 1,234 같은 천 단위 구분 숫자. 안내 메시지용. */
    private static String format(long number) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(number);
    }

    /** 오류 처리 중에 쓰는 정리용. 정리에서 또 오류가 나도 원래 오류를 우선한다. */
    private static void closeQuietly(EventExcelWriter writer) {
        try {
            writer.close();
        } catch (IOException e) {
            // 원래 오류를 가리지 않도록 임시 파일 정리 실패는 무시한다
        }
    }

    /** 만든 파일. 내보낸 뒤 반드시 닫아 임시 파일을 지운다. */
    public static final class EventExportFile implements AutoCloseable {

        // 내려받을 파일 이름과, 실제 내용을 가진 작성기
        private final String fileName;
        private final EventExcelWriter writer;

        // 서비스만 만들 수 있다(private)
        private EventExportFile(String fileName, EventExcelWriter writer) {
            this.fileName = fileName;
            this.writer = writer;
        }

        // 컨트롤러가 Content-Disposition 헤더에 쓴다
        public String fileName() {
            return fileName;
        }

        // 응답 스트림에 엑셀 내용을 쓴다
        public void writeTo(OutputStream out) throws IOException {
            writer.writeTo(out);
        }

        @Override
        // 임시 파일 삭제. AutoCloseable이라 try-with-resources로 호출된다. 검사 예외는 비검사로 감싸 던진다
        public void close() {
            try {
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
