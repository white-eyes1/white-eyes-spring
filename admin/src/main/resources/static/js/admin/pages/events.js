// 이벤트 로그 화면 SCR-A-010 중 요약 카드·필터·이벤트 표(FNC-A-036), 내려받기(FNC-A-039)와, 행을 누르면 열리는
// 이벤트 상세 창 SCR-A-011 중 이벤트 정보·처리 이력(FNC-A-037), 표의 조치 버튼(FNC-A-038).
// 조치 완료 창은 event-action.js. 발생 시점 영상(FNC-A-040), 새 이벤트 알림(FNC-A-048)은 각 기능의 이슈에서 붙인다.
// 필터는 주소의 쿼리로 유지한다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).

// 공용 모듈(조회·파일 내려받기), 날짜·숫자 표시 함수, 조치 완료 창(event-action.js), 상세 창의 영상 영역(event-video.js)을 가져온다.
import { download, get } from '../../common/api.js';
import { formatDateTime, formatDecimal, formatNumber } from '../../common/format.js';
import { openActionDialog, requestStatusChange } from '../event-action.js';
import { closeEventVideo, openEventVideo } from '../event-video.js';

// 한 페이지 행 수, 검색어 입력 후 조회까지의 대기(ms), 한국 시간 오프셋, 현재 쪽 앞뒤로 보일 번호 개수.
const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;
const KST_OFFSET = '+09:00';
const PAGE_BUTTON_WINDOW = 2;

// 이벤트 종류 코드를 화면 이름으로 바꾸는 사전.
const TYPE_LABEL = { CONGESTION: '혼잡', SURGE: '급증', CAM_FAULT: '캠 이상' };
// 처리 상태 코드별 화면 이름과 칩 색(CSS 클래스).
const STATUS = {
    UNCHECKED: { label: '미확인', chip: 'unread' },
    CHECKED: { label: '확인 중', chip: 'review' },
    RESOLVED: { label: '조치완료', chip: 'done' },
};
// 혼잡 이벤트의 칩 색은 최고 단계의 색(주의 l2, 위험 l3), 급증은 보라, 캠 이상은 회색
// 혼잡 이벤트의 최고 단계(2=주의, 3=위험)별 칩 색 클래스.
const LEVEL_CHIP = { 2: 'l2', 3: 'l3' };
// 조치 항목 코드(conventions.md 코드 값 event.action_code)
// 조치 항목 코드를 화면 이름으로 바꾸는 사전.
const ACTION_LABEL = {
    ONSITE_OK: '현장 확인·정상',
    STAFF: '인력 배치',
    FALSE_ALARM: '오탐',
    CAM_CHECK: '캠 점검 요청',
    OTHER: '기타',
};

// 필터 줄, 요약 카드, 표, 페이지, 내려받기 버튼, 상세 창의 요소들을 id로 한 번만 찾아 둔다.
const form = document.getElementById('event-filter');
const fromInput = document.getElementById('filter-from');
const toInput = document.getElementById('filter-to');
const statusSelect = document.getElementById('filter-status');
const typeSelect = document.getElementById('filter-type');
const keywordInput = document.getElementById('filter-keyword');
const detailToggle = document.getElementById('filter-detail-toggle');
const detailPanel = document.getElementById('filter-detail');
const rowsEl = document.getElementById('event-rows');
const emptyEl = document.getElementById('event-empty');
const totalEl = document.getElementById('event-total');
const pagesEl = document.getElementById('event-pages');
const summaryTotalLabel = document.getElementById('summary-total-label');
const exportButton = document.getElementById('export-button');
const detailModal = document.getElementById('detail-modal');
const detailTypeChip = document.getElementById('detail-type-chip');
const detailTitle = document.getElementById('detail-title');
const detailStatusChip = document.getElementById('detail-status-chip');
const detailInfo = document.getElementById('detail-info');
const detailHistories = document.getElementById('detail-histories');

// page: 현재 쪽(0부터). requestSeq: 목록 요청 번호. keywordTimer: 검색어 입력 지연 타이머. levelNames: 단계 번호 -> 이름. detailSeq: 상세 요청 번호. detailOpener: 상세 창을 연 행(닫을 때 초점 복귀).
let page = 0;
let requestSeq = 0;
let keywordTimer = null;
let levelNames = {};
let detailSeq = 0;
let detailOpener = null;

init();

// 화면 시작 순서: 주소의 이벤트 번호 읽기 -> 필터 복원 -> 이벤트 연결 -> 단계 이름 -> 첫 목록 -> (요청 번호가 있으면) 상세 열기 -> 알림 연결.
async function init() {
    // refresh()가 주소를 필터 값으로 다시 쓰면 ?eventId=가 사라지므로, 그 전에 읽어 둔다
    const requestedEventId = readRequestedEventId();
    restoreFilters();
    bindFilters();
    bindDetail();
    // 단계 이름을 먼저 받아 두어야 표를 그릴 때 이름이 바로 보인다.
    await loadLevelNames();
    await refresh();
    if (requestedEventId !== null) {
        openDetail(requestedEventId);
    }
    bindNotifications();
}

// 새 이벤트 토스트나 다시보기의 '이벤트 상세 보기'를 눌러 들어오면(?eventId=) 그 이벤트의 상세 창을 바로 연다(FNC-A-048)
// 주소의 eventId를 읽는다. 양의 정수가 아니면 null(상세를 열지 않음).
function readRequestedEventId() {
    const eventId = Number(new URLSearchParams(window.location.search).get('eventId'));
    return Number.isInteger(eventId) && eventId > 0 ? eventId : null;
}

// 알림 스트림에서 새 이벤트가 오면 첫 쪽을 다시 읽어 맨 위에 보이게 한다. 다른 쪽을 보는 중이면 보던 쪽을 흐트리지 않는다
// 새 이벤트 알림(we:event-created, 알림 스트림 모듈이 보냄)을 받으면 첫 쪽을 보고 있을 때만 목록을 새로 읽는다.
function bindNotifications() {
    window.addEventListener('we:event-created', () => {
        if (page === 0) {
            refresh();
        }
    });
}

// ---- 필터 ----

// 주소의 쿼리에서 필터를 복원한다. 값이 없으면 기간은 오늘 하루, 나머지는 전체로 둔다. 새로고침·링크 공유에도 같은 화면이 나오게 하려는 것이다.
function restoreFilters() {
    const params = new URLSearchParams(window.location.search);
    const today = formatDate(new Date());
    fromInput.value = params.get('from') || today;
    toInput.value = params.get('to') || today;
    statusSelect.value = params.get('status') || '';
    typeSelect.value = params.get('type') || '';
    keywordInput.value = params.get('keyword') || '';
    // 쪽 번호는 음수나 숫자가 아닌 값이면 0으로 보정한다.
    page = Math.max(0, Number(params.get('page')) || 0);
    // 기간·종류는 '상세 필터'에 있다(screens.md SCR-A-010). 주소로 지정돼 들어오면 펼쳐서 보인다
    setDetailOpen(params.has('from') || params.has('to') || params.has('type'));
}

// 상세 필터 영역을 열고 닫으며 접근성 속성(aria-expanded)을 맞춘다.
function setDetailOpen(open) {
    detailPanel.hidden = !open;
    detailToggle.setAttribute('aria-expanded', String(open));
}

// 필터 변경 시 첫 쪽부터 다시 조회한다. 검색어는 입력할 때마다 요청하지 않도록 마지막 입력 후 일정 시간 뒤 한 번만 조회한다(디바운스).
function bindFilters() {
    form.addEventListener('submit', (event) => event.preventDefault());
    detailToggle.addEventListener('click', () => setDetailOpen(detailPanel.hidden));
    for (const control of [fromInput, toInput, statusSelect, typeSelect]) {
        control.addEventListener('change', () => applyFilters());
    }
    keywordInput.addEventListener('input', () => {
        window.clearTimeout(keywordTimer);
        keywordTimer = window.setTimeout(applyFilters, KEYWORD_DEBOUNCE_MS);
    });
}

// 엑셀 내려받기 버튼 연결.
exportButton.addEventListener('click', downloadExcel);

// 내려받기: 지금 화면의 필터 그대로(page·size는 보내지 않는다). 5만 건 초과 같은 오류는 api.js가 토스트로 알린다
// FNC-A-039. 화면의 필터와 같은 조건으로 서버가 만든 파일을 받아 저장한다. 중복 클릭을 막으려 요청 중 버튼을 잠근다.
async function downloadExcel() {
    exportButton.disabled = true;
    // 기간·종류·검색어 공통 조건에 처리 상태를 더한다. 목록과 달리 쪽·크기는 보내지 않는다.
    const query = commonQuery();
    if (statusSelect.value) {
        query.append('statuses', statusSelect.value);
    }
    try {
        // 항목 배열 형태로 넘겨 같은 키가 여러 번 올 수 있게 한다.
        const { blob, fileName } = await download('/api/v1/events/export', [...query.entries()]);
        // 서버가 파일명을 주지 않으면 기본 이름을 쓴다. 오류 알림은 공용 모듈이 이미 띄웠으므로 여기서는 콘솔에만 남긴다.
        saveFile(blob, fileName ?? 'events.xlsx');
    } catch (error) {
        console.error(error);
    } finally {
        exportButton.disabled = false;
    }
}

// 브라우저에서 파일을 저장시키는 방법: 임시 주소를 만들어 보이지 않는 링크를 눌러 내려받게 한 뒤, 링크와 임시 주소를 바로 정리해 메모리를 돌려준다.
function saveFile(blob, fileName) {
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
}

// 필터가 바뀌면 첫 쪽으로 돌아가 다시 조회한다.
function applyFilters() {
    page = 0;
    refresh();
}

// 기간은 날짜 입력의 양끝 날짜를 포함한다(conventions.md '기간 조회'): from은 그날 00:00, to는 다음 날 00:00
// 날짜 입력으로 조회 기간 파라미터를 만든다. 선택한 마지막 날 전체가 포함되도록 끝은 다음 날 00:00으로 보낸다.
function periodParams() {
    const params = {};
    if (fromInput.value) {
        params.from = `${fromInput.value}T00:00:00${KST_OFFSET}`;
    }
    if (toInput.value) {
        params.to = `${addDays(toInput.value, 1)}T00:00:00${KST_OFFSET}`;
    }
    return params;
}

// 요약은 처리 상태를 받지 않으므로 상태를 뺀 같은 조건을 쓴다
// 목록·요약·내려받기가 함께 쓰는 조건(기간, 종류, 검색어). 요약 카드는 처리 상태별 개수를 보여 주므로 상태 조건은 넣지 않는다.
function commonQuery() {
    const params = new URLSearchParams(periodParams());
    if (typeSelect.value) {
        params.append('types', typeSelect.value);
    }
    const keyword = keywordInput.value.trim();
    if (keyword) {
        params.set('keyword', keyword);
    }
    return params;
}

// 현재 필터를 주소에 기록한다. 새 페이지 이동 없이 주소만 교체(replaceState)하므로 뒤로가기 기록이 쌓이지 않는다.
function saveFiltersToUrl() {
    const params = new URLSearchParams();
    params.set('from', fromInput.value);
    params.set('to', toInput.value);
    if (statusSelect.value) {
        params.set('status', statusSelect.value);
    }
    if (typeSelect.value) {
        params.set('type', typeSelect.value);
    }
    if (keywordInput.value.trim()) {
        params.set('keyword', keywordInput.value.trim());
    }
    if (page > 0) {
        params.set('page', String(page));
    }
    window.history.replaceState(null, '', `${window.location.pathname}?${params.toString()}`);
}

// ---- 조회 ----

// 현재 필터로 목록과 요약 카드를 함께 조회해 그린다(FNC-A-036).
async function refresh() {
    // 이번 요청의 번호. 더 새 요청이 나가면 이 응답은 버린다.
    const seq = ++requestSeq;
    saveFiltersToUrl();
    const listQuery = commonQuery();
    if (statusSelect.value) {
        listQuery.append('statuses', statusSelect.value);
    }
    listQuery.set('page', String(page));
    listQuery.set('size', String(PAGE_SIZE));
    try {
        // 목록과 요약을 동시에 요청해 기다리는 시간을 줄인다. 요약 오류는 토스트 없이, 목록 오류만 토스트로 알려 같은 오류가 두 번 뜨지 않게 한다.
        const [list, summary] = await Promise.all([
            get('/api/v1/events', Object.fromEntries(listQuery)),
            get('/api/v1/events/summary', Object.fromEntries(commonQuery()), { toast: false }),
        ]);
        // 그 사이 필터가 바뀌어 더 새로운 요청이 나갔으면 이 응답은 버린다
        if (seq !== requestSeq) {
            return;
        }
        renderSummary(summary);
        renderList(list);
    // 실패하면 이전 결과를 비운다.
    } catch {
        // 새 필터와 어긋난 이전 결과가 남지 않게 표와 카드를 비운다. 오류 알림은 목록 호출이 토스트로 한 번만 띄운다
        if (seq === requestSeq) {
            clearResult();
            totalEl.textContent = '목록을 불러오지 못했습니다.';
        }
    }
}

// 표, 빈 목록 안내, 페이지 버튼을 비우고 요약 카드를 '-'로 되돌린다.
function clearResult() {
    rowsEl.replaceChildren();
    emptyEl.hidden = true;
    pagesEl.replaceChildren();
    for (const id of [
        'summary-total',
        'summary-unchecked',
        'summary-checked',
        'summary-resolved',
    ]) {
        document.getElementById(id).textContent = '-';
    }
}

// 혼잡 단계 번호에 대응하는 이름(주의·위험 등)을 서버 메타 정보에서 받아 둔다. 실패해도 화면은 '숫자단계'로 대체해 계속 동작한다.
async function loadLevelNames() {
    try {
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        levelNames = Object.fromEntries(levels.map((level) => [level.level, level.name]));
    } catch {
        levelNames = {};
    }
}

// ---- 요약 카드 ----

// 요약 카드(전체·미확인·확인 중·조치완료)를 채운다. 기간이 오늘 하루면 '전체 (오늘)'로 표시한다.
function renderSummary(summary) {
    summaryTotalLabel.textContent =
        fromInput.value === toInput.value && fromInput.value === formatDate(new Date())
            ? '전체 (오늘)'
            : '전체';
    document.getElementById('summary-total').textContent = formatNumber(summary.total);
    document.getElementById('summary-unchecked').textContent = formatNumber(summary.unchecked);
    document.getElementById('summary-checked').textContent = formatNumber(summary.checked);
    document.getElementById('summary-resolved').textContent = formatNumber(summary.resolved);
}

// ---- 표 ----

// 목록 응답으로 표, 빈 목록 안내, 총 건수, 페이지 버튼을 갱신한다.
function renderList(result) {
    rowsEl.replaceChildren(...result.items.map(buildRow));
    emptyEl.hidden = result.items.length > 0;
    totalEl.textContent = `총 ${formatNumber(result.totalElements)}건`;
    renderPages(result.page, result.totalPages);
}

// 이벤트 하나를 표의 행으로 만든다. 행은 키보드로도 열 수 있게 tabIndex를 주고, data-event-id를 상세 열기에 쓴다.
function buildRow(event) {
    const row = el('div', 'tr ev-row');
    row.setAttribute('role', 'row');
    row.tabIndex = 0;
    row.dataset.eventId = event.id;
    // 열: 발생(종료 문구), 종류, 구역(지역·캠), 값(최고 인원), 지속 시간, 처리 상태, 동작.
    row.append(
        cell(textWithSmall(formatDateTime(event.startedAt), endedText(event))),
        cell(typeChip(event)),
        cell(textWithSmall(event.zoneName, regionAndCamera(event))),
        cell(textWithSmall(valueText(event), peakCountText(event))),
        cell(document.createTextNode(durationText(event.durationSec))),
        cell(statusChip(event.status)),
        cell(actionControls(event)),
    );
    return row;
}

// 미확인: [확인] [조치하기] / 확인 중: [조치하기] / 조치완료: '처리됨' 표시(screens.md SCR-A-010, FNC-A-038)
// 처리 상태에 따라 버튼을 달리 보인다. 조치완료면 버튼 없이 '처리됨'만.
function actionControls(event) {
    const box = el('div', 'ev-actions');
    if (event.status === 'RESOLVED') {
        box.append(el('span', 'muted ev-handled', '처리됨'));
        return box;
    }
    // 미확인일 때만 '확인' 버튼이 있다. 조치하기는 미확인·확인 중 둘 다 있다.
    if (event.status === 'UNCHECKED') {
        const check = el('button', 'btn secondary sm', '확인');
        check.type = 'button';
        check.addEventListener('click', () => markChecked(event, check));
        box.append(check);
    }
    const resolve = el('button', 'btn primary sm', '조치하기');
    resolve.type = 'button';
    // 조치 완료 창을 열고, 저장이 끝나면 목록을 다시 읽는다.
    resolve.addEventListener('click', () => {
        openActionDialog(actionTarget(event, resolve), refresh);
    });
    box.append(resolve);
    return box;
}

// [확인]은 창 없이 바로 저장한다. 성공이든 다른 관리자가 먼저 처리한 409든 목록·요약 카드를 다시 읽는다
// '확인' 처리. expectedStatus로 사용자가 본 상태를 함께 보내 다른 관리자가 먼저 바꿨는지 서버가 판단하게 한다(낙관적 잠금). 결과와 무관하게 서버 기준으로 다시 읽는다.
async function markChecked(event, button) {
    button.disabled = true;
    await requestStatusChange(event.id, { expectedStatus: event.status, status: 'CHECKED' });
    await refresh();
}

// 조치 창 머리: 구역(캠 이상은 캠)·발생 시각·단계
// 조치 완료 창에 넘길 대상 정보. 요약 문구는 장소, 발생 시각의 시분초(문자열 11번째 이후), 단계 이름으로 만든다.
function actionTarget(event, opener) {
    const place = [event.zoneName, event.cameraName].filter(Boolean).join(' · ');
    return {
        eventId: event.id,
        expectedStatus: event.status,
        summary: `${place} · ${formatDateTime(event.startedAt).slice(11)} · ${peakTitle(event)}`,
        opener,
    };
}

// 발생 칸 아래 작은 글자: 진행 중이면 '진행 중', 아니면 종료 시각. 같은 날 끝난 경우 날짜는 생략한다.
function endedText(event) {
    if (event.ongoing) {
        return '진행 중';
    }
    const started = formatDateTime(event.startedAt);
    const ended = formatDateTime(event.endedAt);
    // 같은 날 끝났으면 시각만
    return `${started.slice(0, 10) === ended.slice(0, 10) ? ended.slice(11) : ended} 종료`;
}

// 종류 칩. 혼잡은 단계에 맞는 색(정보 없으면 주의 색), 급증은 surge, 캠 이상은 cam 색이다.
function typeChip(event) {
    const chipClass =
        event.type === 'CONGESTION'
            ? (LEVEL_CHIP[event.peakLevel] ?? 'l2')
            : event.type === 'SURGE'
              ? 'surge'
              : 'cam';
    return el('span', `chip ${chipClass}`, TYPE_LABEL[event.type] ?? event.type);
}

// 지역 경로와 캠 이름을 ' · '로 이어 붙인다(없는 쪽은 제외).
function regionAndCamera(event) {
    return [event.regionPath, event.cameraName].filter(Boolean).join(' · ');
}

// 혼잡=단계 이름 · 밀집도, 급증=증가율, 캠 이상='-'(screens.md SCR-A-010)
// 종류별 값 문구: 혼잡은 단계 이름과 밀집도, 급증은 증가율, 캠 이상은 값이 없어 '-'.
function valueText(event) {
    if (event.type === 'CONGESTION') {
        const levelName = levelNames[event.peakLevel] ?? `${event.peakLevel}단계`;
        return `${levelName} · ${formatDecimal(event.peakValue, 2)}명/㎡`;
    }
    if (event.type === 'SURGE') {
        return `+${formatDecimal(event.peakValue, 0)}%`;
    }
    return '-';
}

// 최고 인원이 있을 때만 보조 문구를 만든다.
function peakCountText(event) {
    return event.peakCount === null || event.peakCount === undefined
        ? ''
        : `최고 ${formatNumber(event.peakCount)}명`;
}

// 초를 초(1분 미만), 분(1시간 미만), 시간·분 순으로 읽기 쉽게 바꾼다.
function durationText(seconds) {
    const minutes = Math.floor(seconds / 60);
    if (minutes < 1) {
        return `${seconds}초`;
    }
    if (minutes < 60) {
        return `${minutes}분`;
    }
    const rest = minutes % 60;
    return rest === 0
        ? `${Math.floor(minutes / 60)}시간`
        : `${Math.floor(minutes / 60)}시간 ${rest}분`;
}

// 처리 상태 칩. 모르는 상태는 회색 칩에 원문을 보인다.
function statusChip(status) {
    const info = STATUS[status] ?? { label: status, chip: 'off' };
    return el('span', `chip ${info.chip}`, info.label);
}

// ---- 상세 창 (SCR-A-011) ----

// 상세 창 이벤트 연결: 행 클릭 또는 Enter로 열기(이벤트 위임), 닫기 버튼·배경 클릭·Esc로 닫기.
function bindDetail() {
    rowsEl.addEventListener('click', (event) => openDetailOf(event.target));
    rowsEl.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            openDetailOf(event.target);
        }
    });
    detailModal
        .querySelectorAll('[data-close]')
        .forEach((button) => button.addEventListener('click', closeDetail));
    detailModal.addEventListener('mousedown', (event) => {
        if (event.target === detailModal) {
            closeDetail();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !detailModal.hidden) {
            closeDetail();
        }
    });
}

// 클릭된 요소에서 행을 찾아 상세를 연다. 행 안의 버튼 클릭은 조치 동작이므로 제외한다.
function openDetailOf(target) {
    // 조치 버튼을 누른 것은 행 클릭이 아니다
    if (target.closest('button')) {
        return;
    }
    const row = target.closest('.ev-row');
    if (row) {
        detailOpener = row;
        openDetail(row.dataset.eventId);
    }
}

// 이벤트 상세를 조회해 창을 연다. 응답이 늦게 와 다른 요청이 앞섰으면 버려 엉뚱한 상세가 뜨지 않게 한다.
async function openDetail(eventId) {
    const seq = ++detailSeq;
    try {
        const detail = await get(`/api/v1/events/${eventId}`);
        // 그 사이 다른 행을 눌렀거나 창을 닫았으면 이 응답은 버린다
        if (seq === detailSeq) {
            renderDetail(detail);
            openEventVideo(detail);
            detailModal.hidden = false;
            detailModal.querySelector('.modal').focus();
        }
    } catch {
        // 오류 알림은 공용 API 모듈이 토스트로 띄운다. 창은 열지 않는다
    }
}

// 상세 창을 닫는다. 요청 번호를 올려 아직 오지 않은 응답을 무효화하고, 영상 재생을 멈추며, 행으로 초점을 돌린다.
function closeDetail() {
    detailSeq++;
    closeEventVideo();
    detailModal.hidden = true;
    // 열었던 행으로 포커스를 돌려준다(목록이 다시 그려져 행이 사라졌으면 건너뛴다)
    if (detailOpener?.isConnected) {
        detailOpener.focus();
    }
    detailOpener = null;
}

// 상세 창의 제목·칩·정보 목록·처리 이력을 채운다.
function renderDetail(detail) {
    detailTypeChip.replaceChildren(typeChip(detail));
    detailTitle.textContent = `${detail.zoneName ?? '-'} · ${peakTitle(detail)}`;
    detailStatusChip.replaceChildren(statusChip(detail.status));

    // 정보 목록(항목명, 값) 쌍. 조치 항목·메모·처리 일시는 값이 있을 때만 덧붙인다.
    const rows = [
        ['구역 · 지역', [detail.zoneName, regionAndCamera(detail)].filter(Boolean).join(' · ')],
        ['발생 · 종료', periodText(detail)],
        ['최고 단계·값', peakNode(detail)],
        ['당시 기준값', thresholdText(detail)],
    ];
    if (detail.actionCode) {
        rows.push(['조치 항목', ACTION_LABEL[detail.actionCode] ?? detail.actionCode]);
    }
    if (detail.actionNote) {
        rows.push(['조치 메모', detail.actionNote]);
    }
    if (detail.handledAt) {
        rows.push(['처리 일시', formatDateTime(detail.handledAt)]);
    }
    // 각 쌍을 dt(항목명)와 dd(값) 요소로 풀어 넣는다.
    detailInfo.replaceChildren(
        ...rows.flatMap(([label, value]) => [el('dt', '', label), definition(value)]),
    );

    // 처리 이력이 없으면 안내 문구를 보인다.
    detailHistories.replaceChildren(
        ...(detail.histories.length > 0
            ? detail.histories.map(buildHistory)
            : [el('p', 'muted ev-history-empty', '처리 이력이 없습니다')]),
    );
}

// 제목 끝: 혼잡=최고 단계 이름, 급증·캠 이상=종류 이름
// 상세 창 제목과 조치 창 요약에 쓰는 이름.
function peakTitle(detail) {
    if (detail.type === 'CONGESTION') {
        return levelNames[detail.peakLevel] ?? `${detail.peakLevel}단계`;
    }
    return TYPE_LABEL[detail.type] ?? detail.type;
}

// '시작 ~ 종료 (지속 시간)' 문구. 진행 중이면 종료 대신 '진행 중'. 같은 날 끝났으면 종료는 시각만.
function periodText(detail) {
    const started = formatDateTime(detail.startedAt);
    const duration = durationText(detail.durationSec);
    if (detail.ongoing) {
        return `${started} ~ 진행 중 (${duration})`;
    }
    const ended = formatDateTime(detail.endedAt);
    const endedLabel = started.slice(0, 10) === ended.slice(0, 10) ? ended.slice(11) : ended;
    return `${started} ~ ${endedLabel} (${duration})`;
}

// 혼잡=단계 칩 + 밀집도 + 최고 인원, 급증=증가율 + 최고 인원, 캠 이상='-'
// 최고 단계·값 칸. 칩과 글자를 함께 넣어야 해서 문자열이 아니라 조각(DocumentFragment)으로 만든다.
function peakNode(detail) {
    if (detail.type === 'CAM_FAULT') {
        return '-';
    }
    const fragment = document.createDocumentFragment();
    if (detail.type === 'CONGESTION') {
        fragment.append(
            el('span', `chip ${LEVEL_CHIP[detail.peakLevel] ?? 'l2'}`, peakTitle(detail)),
            ' ',
        );
        fragment.append(`${formatDecimal(detail.peakValue, 2)}명/㎡`);
    } else {
        fragment.append(`+${formatDecimal(detail.peakValue, 0)}%`);
    }
    fragment.append(` · ${formatNumber(detail.peakCount)}명`);
    return fragment;
}

// 기준값 JSON은 이벤트 종류마다 키가 다르다(tables.md event.threshold_snapshot, FNC-A-033·034·035)
// 이벤트가 발생할 당시의 기준값(스냅샷)을 문구로 만든다. 설정이 나중에 바뀌어도 당시 기준을 보이려고 서버가 저장해 둔 값을 쓴다. 종류마다 키가 달라 필요한 키가 모두 있을 때만 문구를 만든다.
function thresholdText(detail) {
    const snapshot = detail.thresholdSnapshot ?? {};
    if (detail.type === 'CONGESTION' && has(snapshot.warnDensity) && has(snapshot.dangerDensity)) {
        return `주의 ${snapshot.warnDensity} · 위험 ${snapshot.dangerDensity} 명/㎡`;
    }
    if (detail.type === 'CAM_FAULT' && has(snapshot.timeoutSec)) {
        return `결과 없음 ${snapshot.timeoutSec}초 이상`;
    }
    if (
        detail.type === 'SURGE' &&
        has(snapshot.window) &&
        has(snapshot.rate) &&
        has(snapshot.minDensity)
    ) {
        return `${snapshot.window}분 전보다 +${snapshot.rate}% 이상 · 최소 밀집도 ${snapshot.minDensity}명/㎡`;
    }
    return '-';
}

// 값이 있는지(null·undefined 제외) 확인한다. 0도 유효한 값이라 단순 참/거짓 검사를 쓰지 않는다.
function has(value) {
    return value !== null && value !== undefined;
}

// 처리 이력 한 줄: '이전 상태 -> 새 상태, 시각, 처리자, 조치 항목·메모'. 서버 값이므로 모두 텍스트로 붙인다.
function buildHistory(history) {
    const row = el('div', 'ev-history-row');
    if (history.from) {
        row.append(statusChip(history.from), ' → ');
    }
    row.append(statusChip(history.to), ` ${formatDateTime(history.at)} · ${history.loginId}`);
    const action = ACTION_LABEL[history.actionCode] ?? history.actionCode;
    if (action) {
        row.append(` · ${action}${history.actionNote ? ` — ${history.actionNote}` : ''}`);
    } else if (history.actionNote) {
        row.append(` · ${history.actionNote}`);
    }
    return row;
}

// dd 요소를 만들어 값(글자 또는 조각)을 넣는다.
function definition(value) {
    const node = el('dd');
    node.append(value);
    return node;
}

// ---- 페이지 ----

// 한 쪽뿐이면 페이지 버튼을 보이지 않는다. 그 외에는 이전·다음 화살표와 현재 쪽 앞뒤 번호를 보인다.
function renderPages(current, totalPages) {
    if (totalPages <= 1) {
        pagesEl.replaceChildren();
        return;
    }
    const buttons = [pageButton('‹', current - 1, current === 0, false)];
    const first = Math.max(0, current - PAGE_BUTTON_WINDOW);
    const last = Math.min(totalPages - 1, current + PAGE_BUTTON_WINDOW);
    for (let index = first; index <= last; index++) {
        buttons.push(pageButton(String(index + 1), index, false, index === current));
    }
    buttons.push(pageButton('›', current + 1, current >= totalPages - 1, false));
    pagesEl.replaceChildren(...buttons);
}

// 페이지 이동 버튼. disabled면 눌러도 이동하지 않고, selected면 현재 쪽 표시(aria-current)를 단다.
function pageButton(label, target, disabled, selected) {
    const button = el('button', selected ? 'on' : '', label);
    button.type = 'button';
    button.disabled = disabled;
    if (selected) {
        button.setAttribute('aria-current', 'page');
    }
    button.addEventListener('click', () => {
        page = target;
        refresh();
    });
    return button;
}

// ---- 도우미 ----

// 요소 도우미. 글자는 textContent로 넣어 HTML로 해석되지 않게 한다.
function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined) {
        node.textContent = text;
    }
    return node;
}

// 표 칸(div role=cell) 도우미.
function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

// 큰 글자 아래에 작은 보조 글자를 붙인 조각을 만든다.
function textWithSmall(main, small) {
    const fragment = document.createDocumentFragment();
    fragment.append(document.createTextNode(main ?? '-'));
    if (small) {
        fragment.append(el('small', '', small));
    }
    return fragment;
}

// Date를 입력칸 형식 'yyyy-MM-dd'로 바꾼다(브라우저 현지 날짜 기준).
function formatDate(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// 'yyyy-MM-dd' 문자열에 일수를 더한 날짜 문자열을 돌려준다. UTC로 계산해 서머타임 등 시간대 영향 없이 날짜만 더한다.
function addDays(dateText, days) {
    const date = new Date(`${dateText}T00:00:00Z`);
    date.setUTCDate(date.getUTCDate() + days);
    return date.toISOString().slice(0, 10);
}
