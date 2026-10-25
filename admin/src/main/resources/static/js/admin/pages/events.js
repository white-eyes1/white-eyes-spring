// 이벤트 로그 화면 SCR-A-010 중 요약 카드·필터·이벤트 표(FNC-A-036), 내려받기(FNC-A-039)와, 행을 누르면 열리는
// 이벤트 상세 창 SCR-A-011 중 이벤트 정보·처리 이력(FNC-A-037), 표의 조치 버튼(FNC-A-038).
// 조치 완료 창은 event-action.js. 발생 시점 영상(FNC-A-040), 새 이벤트 알림(FNC-A-048)은 각 기능의 이슈에서 붙인다.
// 필터는 주소의 쿼리로 유지한다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).

import { download, get } from '../../common/api.js';
import { formatDateTime, formatDecimal, formatNumber } from '../../common/format.js';
import { openActionDialog, requestStatusChange } from '../event-action.js';
import { closeEventVideo, openEventVideo } from '../event-video.js';

const PAGE_SIZE = 20;
const KEYWORD_DEBOUNCE_MS = 300;
const KST_OFFSET = '+09:00';
const PAGE_BUTTON_WINDOW = 2;

const TYPE_LABEL = { CONGESTION: '혼잡', SURGE: '급증', CAM_FAULT: '캠 이상' };
const STATUS = {
    UNCHECKED: { label: '미확인', chip: 'unread' },
    CHECKED: { label: '확인 중', chip: 'review' },
    RESOLVED: { label: '조치완료', chip: 'done' },
};
// 혼잡 이벤트의 칩 색은 최고 단계의 색(주의 l2, 위험 l3), 급증은 보라, 캠 이상은 회색
const LEVEL_CHIP = { 2: 'l2', 3: 'l3' };
// 조치 항목 코드(conventions.md 코드 값 event.action_code)
const ACTION_LABEL = {
    ONSITE_OK: '현장 확인·정상',
    STAFF: '인력 배치',
    FALSE_ALARM: '오탐',
    CAM_CHECK: '캠 점검 요청',
    OTHER: '기타',
};

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

let page = 0;
let requestSeq = 0;
let keywordTimer = null;
let levelNames = {};
let detailSeq = 0;
let detailOpener = null;

init();

async function init() {
    restoreFilters();
    bindFilters();
    bindDetail();
    await loadLevelNames();
    await refresh();
}

// ---- 필터 ----

function restoreFilters() {
    const params = new URLSearchParams(window.location.search);
    const today = formatDate(new Date());
    fromInput.value = params.get('from') || today;
    toInput.value = params.get('to') || today;
    statusSelect.value = params.get('status') || '';
    typeSelect.value = params.get('type') || '';
    keywordInput.value = params.get('keyword') || '';
    page = Math.max(0, Number(params.get('page')) || 0);
    // 기간·종류는 '상세 필터'에 있다(screens.md SCR-A-010). 주소로 지정돼 들어오면 펼쳐서 보인다
    setDetailOpen(params.has('from') || params.has('to') || params.has('type'));
}

function setDetailOpen(open) {
    detailPanel.hidden = !open;
    detailToggle.setAttribute('aria-expanded', String(open));
}

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

exportButton.addEventListener('click', downloadExcel);

// 내려받기: 지금 화면의 필터 그대로(page·size는 보내지 않는다). 5만 건 초과 같은 오류는 api.js가 토스트로 알린다
async function downloadExcel() {
    exportButton.disabled = true;
    const query = commonQuery();
    if (statusSelect.value) {
        query.append('statuses', statusSelect.value);
    }
    try {
        const { blob, fileName } = await download('/api/v1/events/export', [...query.entries()]);
        saveFile(blob, fileName ?? 'events.xlsx');
    } catch (error) {
        console.error(error);
    } finally {
        exportButton.disabled = false;
    }
}

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

function applyFilters() {
    page = 0;
    refresh();
}

// 기간은 날짜 입력의 양끝 날짜를 포함한다(conventions.md '기간 조회'): from은 그날 00:00, to는 다음 날 00:00
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

async function refresh() {
    const seq = ++requestSeq;
    saveFiltersToUrl();
    const listQuery = commonQuery();
    if (statusSelect.value) {
        listQuery.append('statuses', statusSelect.value);
    }
    listQuery.set('page', String(page));
    listQuery.set('size', String(PAGE_SIZE));
    try {
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
    } catch {
        // 새 필터와 어긋난 이전 결과가 남지 않게 표와 카드를 비운다. 오류 알림은 목록 호출이 토스트로 한 번만 띄운다
        if (seq === requestSeq) {
            clearResult();
            totalEl.textContent = '목록을 불러오지 못했습니다.';
        }
    }
}

function clearResult() {
    rowsEl.replaceChildren();
    emptyEl.hidden = true;
    pagesEl.replaceChildren();
    for (const id of ['summary-total', 'summary-unchecked', 'summary-checked', 'summary-resolved']) {
        document.getElementById(id).textContent = '-';
    }
}

async function loadLevelNames() {
    try {
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        levelNames = Object.fromEntries(levels.map((level) => [level.level, level.name]));
    } catch {
        levelNames = {};
    }
}

// ---- 요약 카드 ----

function renderSummary(summary) {
    summaryTotalLabel.textContent = fromInput.value === toInput.value && fromInput.value === formatDate(new Date())
        ? '전체 (오늘)'
        : '전체';
    document.getElementById('summary-total').textContent = formatNumber(summary.total);
    document.getElementById('summary-unchecked').textContent = formatNumber(summary.unchecked);
    document.getElementById('summary-checked').textContent = formatNumber(summary.checked);
    document.getElementById('summary-resolved').textContent = formatNumber(summary.resolved);
}

// ---- 표 ----

function renderList(result) {
    rowsEl.replaceChildren(...result.items.map(buildRow));
    emptyEl.hidden = result.items.length > 0;
    totalEl.textContent = `총 ${formatNumber(result.totalElements)}건`;
    renderPages(result.page, result.totalPages);
}

function buildRow(event) {
    const row = el('div', 'tr ev-row');
    row.setAttribute('role', 'row');
    row.tabIndex = 0;
    row.dataset.eventId = event.id;
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
function actionControls(event) {
    const box = el('div', 'ev-actions');
    if (event.status === 'RESOLVED') {
        box.append(el('span', 'muted ev-handled', '처리됨'));
        return box;
    }
    if (event.status === 'UNCHECKED') {
        const check = el('button', 'btn secondary sm', '확인');
        check.type = 'button';
        check.addEventListener('click', () => markChecked(event, check));
        box.append(check);
    }
    const resolve = el('button', 'btn primary sm', '조치하기');
    resolve.type = 'button';
    resolve.addEventListener('click', () => {
        openActionDialog(actionTarget(event, resolve), refresh);
    });
    box.append(resolve);
    return box;
}

// [확인]은 창 없이 바로 저장한다. 성공이든 다른 관리자가 먼저 처리한 409든 목록·요약 카드를 다시 읽는다
async function markChecked(event, button) {
    button.disabled = true;
    await requestStatusChange(event.id, { expectedStatus: event.status, status: 'CHECKED' });
    await refresh();
}

// 조치 창 머리: 구역(캠 이상은 캠)·발생 시각·단계
function actionTarget(event, opener) {
    const place = [event.zoneName, event.cameraName].filter(Boolean).join(' · ');
    return {
        eventId: event.id,
        expectedStatus: event.status,
        summary: `${place} · ${formatDateTime(event.startedAt).slice(11)} · ${peakTitle(event)}`,
        opener,
    };
}

function endedText(event) {
    if (event.ongoing) {
        return '진행 중';
    }
    const started = formatDateTime(event.startedAt);
    const ended = formatDateTime(event.endedAt);
    // 같은 날 끝났으면 시각만
    return `${started.slice(0, 10) === ended.slice(0, 10) ? ended.slice(11) : ended} 종료`;
}

function typeChip(event) {
    const chipClass =
        event.type === 'CONGESTION' ? (LEVEL_CHIP[event.peakLevel] ?? 'l2') : event.type === 'SURGE' ? 'surge' : 'cam';
    return el('span', `chip ${chipClass}`, TYPE_LABEL[event.type] ?? event.type);
}

function regionAndCamera(event) {
    return [event.regionPath, event.cameraName].filter(Boolean).join(' · ');
}

// 혼잡=단계 이름 · 밀집도, 급증=증가율, 캠 이상='-'(screens.md SCR-A-010)
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

function peakCountText(event) {
    return event.peakCount === null || event.peakCount === undefined ? '' : `최고 ${formatNumber(event.peakCount)}명`;
}

function durationText(seconds) {
    const minutes = Math.floor(seconds / 60);
    if (minutes < 1) {
        return `${seconds}초`;
    }
    if (minutes < 60) {
        return `${minutes}분`;
    }
    const rest = minutes % 60;
    return rest === 0 ? `${Math.floor(minutes / 60)}시간` : `${Math.floor(minutes / 60)}시간 ${rest}분`;
}

function statusChip(status) {
    const info = STATUS[status] ?? { label: status, chip: 'off' };
    return el('span', `chip ${info.chip}`, info.label);
}

// ---- 상세 창 (SCR-A-011) ----

function bindDetail() {
    rowsEl.addEventListener('click', (event) => openDetailOf(event.target));
    rowsEl.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            openDetailOf(event.target);
        }
    });
    detailModal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeDetail));
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

function renderDetail(detail) {
    detailTypeChip.replaceChildren(typeChip(detail));
    detailTitle.textContent = `${detail.zoneName ?? '-'} · ${peakTitle(detail)}`;
    detailStatusChip.replaceChildren(statusChip(detail.status));

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
    detailInfo.replaceChildren(...rows.flatMap(([label, value]) => [el('dt', '', label), definition(value)]));

    detailHistories.replaceChildren(
        ...(detail.histories.length > 0
            ? detail.histories.map(buildHistory)
            : [el('p', 'muted ev-history-empty', '처리 이력이 없습니다')]),
    );
}

// 제목 끝: 혼잡=최고 단계 이름, 급증·캠 이상=종류 이름
function peakTitle(detail) {
    if (detail.type === 'CONGESTION') {
        return levelNames[detail.peakLevel] ?? `${detail.peakLevel}단계`;
    }
    return TYPE_LABEL[detail.type] ?? detail.type;
}

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
function peakNode(detail) {
    if (detail.type === 'CAM_FAULT') {
        return '-';
    }
    const fragment = document.createDocumentFragment();
    if (detail.type === 'CONGESTION') {
        fragment.append(el('span', `chip ${LEVEL_CHIP[detail.peakLevel] ?? 'l2'}`, peakTitle(detail)), ' ');
        fragment.append(`${formatDecimal(detail.peakValue, 2)}명/㎡`);
    } else {
        fragment.append(`+${formatDecimal(detail.peakValue, 0)}%`);
    }
    fragment.append(` · ${formatNumber(detail.peakCount)}명`);
    return fragment;
}

// 기준값 JSON은 이벤트 종류마다 키가 다르다(tables.md event.threshold_snapshot, FNC-A-033·034·035)
function thresholdText(detail) {
    const snapshot = detail.thresholdSnapshot ?? {};
    if (detail.type === 'CONGESTION' && has(snapshot.warnDensity) && has(snapshot.dangerDensity)) {
        return `주의 ${snapshot.warnDensity} · 위험 ${snapshot.dangerDensity} 명/㎡`;
    }
    if (detail.type === 'CAM_FAULT' && has(snapshot.timeoutSec)) {
        return `결과 없음 ${snapshot.timeoutSec}초 이상`;
    }
    if (detail.type === 'SURGE' && has(snapshot.window) && has(snapshot.rate) && has(snapshot.minDensity)) {
        return `${snapshot.window}분 전보다 +${snapshot.rate}% 이상 · 최소 밀집도 ${snapshot.minDensity}명/㎡`;
    }
    return '-';
}

function has(value) {
    return value !== null && value !== undefined;
}

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

function definition(value) {
    const node = el('dd');
    node.append(value);
    return node;
}

// ---- 페이지 ----

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

function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

function textWithSmall(main, small) {
    const fragment = document.createDocumentFragment();
    fragment.append(document.createTextNode(main ?? '-'));
    if (small) {
        fragment.append(el('small', '', small));
    }
    return fragment;
}

function formatDate(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function addDays(dateText, days) {
    const date = new Date(`${dateText}T00:00:00Z`);
    date.setUTCDate(date.getUTCDate() + days);
    return date.toISOString().slice(0, 10);
}
