// 통계 그래프 진입 파일 SCR-C-004(FNC-C-012): 대상·단위·기간·항목을 고르면 GET /api/v1/stats/series로 그린다.
// 대상·단위·기간은 주소 쿼리(targetType·targetId·interval·from·to)로 유지한다. 기본은 1h, 오늘(screens.md SCR-C-004).
// 시각은 브라우저 시각을 오프셋 없이 주고받는다(서버가 KST로 읽음). 끝 칸은 to에서 1분 뺀 시각을 보인다(#25에서 정함).
// 하위 비교(FNC-C-014)를 켜면 GET /api/v1/stats/children으로 고른 항목 하나를 하위 단위별 선으로 겹쳐 그린다(대상이 지역일 때만).
// 시연용 데이터 표시(FNC-C-024): 시연용 점의 칸은 옅은 배경, 하나라도 있으면 제목 옆 배지(하위 비교 응답엔 시연용 여부가 없어 숨김).
// 통합 그래프 아래 세 칸(결정 0016)은 같은 응답으로 그리고(chart/panel-charts.js), 하위 비교를 켜면 숨긴다.
// 단계별 들어가기(FNC-C-013): 점을 누르면 한 단계 아래 단위·기간으로 다시 그리고 이동 경로를 남긴다.
// 1시간 점은 1분 통계가 있을 때만 1분으로 내려간다(보관 기간 지남·시연용이면 '1분 데이터 없음'). 관리자 앱은 '1분 단위로 보기'·'다시보기'
// 중 고르고, 1분 점은 바로 다시보기(SCR-A-009)로 간다. 다시보기는 구역 영상이라 대상이 구역일 때만 열 수 있다.
import { ApiError, get, url } from '../common/api.js';
import { formatDateTime, formatDecimal, formatNumber } from '../common/format.js';
import { createPanelCharts, flowSum } from '../chart/panel-charts.js';
import { createSeriesChart } from '../chart/series-chart.js';
import { initExportModal, openExportModal } from '../export/export-modal.js';
import { crumbLabel, drillDown } from './stats-graph/drill.js';

// 서버 API 주소: 통합 시계열, 하위 단위별 비교
const SERIES_API = '/api/v1/stats/series';
const CHILDREN_API = '/api/v1/stats/children';
// 하위 비교 항목 코드(conventions.md '통계 항목') → 그래프 항목 키(SERIES_ITEMS)
const COMPARE_ITEM_KEYS = {
    COUNT: 'count',
    DENSITY: 'density',
    INFLOW: 'inflow',
    OUTFLOW: 'outflow',
    DWELL: 'dwellAvgSec',
};
// 지역 단계 이름. path 배열의 깊이(0 시·도, 1 시·군·구, 2 읍·면·동)와 대응한다
const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];
// 처음 들어오면 1시간 단위
const DEFAULT_INTERVAL = '1h';
// 1분(밀리초)
const MINUTE_MS = 60_000;
// 단위별 이름과 최대 조회 기간(conventions.md STATS_MAX_RANGE, 서버 StatInterval과 같은 값)
const INTERVALS = {
    '1m': { label: '1분', max: { days: 1 }, maxLabel: '1일' },
    '1h': { label: '1시간', max: { days: 31 }, maxLabel: '31일' },
    '1d': { label: '1일', max: { years: 1 }, maxLabel: '1년' },
    '1w': { label: '1주', max: { years: 3 }, maxLabel: '3년' },
    '1M': { label: '1월', max: { years: 5 }, maxLabel: '5년' },
    '1y': { label: '1년', max: { years: 10 }, maxLabel: '10년' },
};
// 서버가 필드 오류로 돌려주는 필드 이름 -> 화면 이름(오류 문구를 읽기 쉽게)
const FIELD_LABELS = {
    targetType: '대상',
    targetId: '대상',
    regionId: '대상',
    item: '항목',
    interval: '단위',
    from: '시작',
    to: '끝',
};
// api.js 기본 문구와 같다(ApiError가 아닌 예외에 쓴다)
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
// 점 선택 창의 안내 문구들
const NO_MINUTE_MESSAGE = '1분 데이터 없음';
const PLAYBACK_ZONE_ONLY_MESSAGE = '다시보기는 구역을 골랐을 때만 볼 수 있습니다.';
const LOAD_FAILED_MESSAGE = '불러오지 못했습니다.';
// 관리자 앱만 다시보기를 연결한다(공통 헤더와 같은 판단)
const IS_ADMIN = document.querySelector('[data-header="admin"]') !== null;

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);
// 화면 요소 모음: 지역 선택 3단계와 구역 선택, 단위 탭, 항목 체크박스, 하위 비교 컨트롤
const regionSelects = ['target-sido', 'target-sigungu', 'target-emd'].map(byId);
const zoneSelect = byId('target-zone');
const tabs = document.querySelectorAll('#interval-tabs [data-interval]');
const itemBoxes = document.querySelectorAll('#graph-items input[type="checkbox"]');
const itemGroup = byId('graph-items');
const compareToggle = byId('compare-toggle');
const compareItemSelect = byId('compare-item');
const omittedNotice = byId('compare-omitted');
// 기간 입력칸(시작·끝), 안내 문구(오류·데이터 없음·시연용 배지), 이동 경로, 점 선택 창
const fromInput = byId('period-from');
const endInput = byId('period-end');
const errorNotice = byId('graph-error');
const emptyNotice = byId('graph-empty');
const demoBadge = byId('demo-badge');
const drillPath = byId('drill-path');
const pointMenu = byId('point-menu');
// 통합 그래프 캔버스와 그 아래 패널 그래프 영역
const canvas = byId('series-chart');
const bandToggle = byId('band-toggle');
const panelsBox = byId('graph-panels');
const directionEmpty = byId('direction-empty');
// 아래 네 칸 그래프(인원 막대, 인원 선, 유입·유출, 방향)
const panels = createPanelCharts({
    level: byId('level-chart'),
    count: byId('count-chart'),
    flow: byId('flow-chart'),
    direction: byId('direction-chart'),
});
// 통합 그래프. 점을 누르면 onPointClick(단계별 들어가기)
const chart = createSeriesChart(canvas, {
    onPointClick,
    // 사용자 앱의 1분 점은 누를 수 없다(FNC-C-013 처리 순서 6)
    clickable: () => IS_ADMIN || interval !== '1m',
});

// 지역 목록을 한 번 받으면 다시 요청하지 않으려고 보관한다
const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록
const zonesCache = new Map(); // dongId -> 구역 목록
// 선택 상태: path 시·도부터 고른 지역들, zone 고른 구역
let path = []; // 시·도부터 고른 지역 목록
let zone = null; // 고른 구역(없으면 지역이 대상)
// 단위·기간 상태. from·to는 Date이고 to는 끝을 포함하지 않는다
let interval = DEFAULT_INTERVAL;
let from = null;
let to = null;
// items: 마지막으로 받은 통합 시계열 점들. comparing: 하위 비교 켜짐 여부. compareResult: 하위 비교 응답
let items = [];
let comparing = false;
let compareResult = { series: [], omitted: 0 };
// 지금 그린 점들의 구간 시작 시각(응답의 at 그대로). 점을 누르면 이 값으로 아래 단계를 정한다
let pointTimes = [];
// 이동 경로: 들어가기 전 화면부터 지금 화면까지 {interval, from, to, label, clickedInterval}. 들어가기 전에는 비어 있다
let trail = [];
// 조건을 빠르게 여러 번 바꾸면 마지막 요청의 결과만 그린다
let latestRequestId = 0;
let latestMenuRequestId = 0;

// 모듈이 불러와지면 곧바로 시작한다
start();

// 주소의 쿼리에서 단위·기간·대상을 읽어 화면을 초기화하고 첫 조회를 한다
async function start() {
    const params = new URLSearchParams(window.location.search);
    // 알 수 없는 단위는 기본 단위로
    interval = INTERVALS[params.get('interval')] ? params.get('interval') : DEFAULT_INTERVAL;
    const fromParam = parseLocal(params.get('from'));
    const toParam = parseLocal(params.get('to'));
    // 기간이 쿼리에 모두 있으면 그대로, 아니면 단위별 기본 기간
    if (fromParam && toParam) {
        from = fromParam;
        to = toParam;
    } else {
        setDefaultPeriod();
    }
    // 대상이 정해져야 선택칸·그래프를 그릴 수 있어 먼저 기다린다
    await selectTarget(params.get('targetType'), Number(params.get('targetId')));
    initTabs();
    initItems();
    // 최대·최소 띠 토글은 다시 읽지 않고 받은 값으로 다시 그린다
    bandToggle.addEventListener('change', render);
    initCompare();
    byId('period-apply').addEventListener('click', applyPeriod);
    initExportModal();
    byId('export-open').addEventListener('click', openExport);
    initPointMenu();
    renderTabs();
    renderPeriod();
    await load();
}

// ---- 대상(시·구·동·구역) ------------------------------------------------------------------------

// 하위 지역 목록. parentId가 null이면 시·도 목록. 한 번 받은 것은 캐시한다
async function children(parentId) {
    const key = parentId ?? 'root';
    if (!childrenCache.has(key)) {
        childrenCache.set(
            key,
            await get('/api/v1/regions', parentId === null ? undefined : { parentId }),
        );
    }
    return childrenCache.get(key);
}

// 읍·면·동 하나의 구역 목록(캐시)
async function zonesOf(dongId) {
    if (!zonesCache.has(dongId)) {
        zonesCache.set(dongId, await get('/api/v1/zones', { dongId }));
    }
    return zonesCache.get(dongId);
}

// 통계 메인과 같은 방법: 상위 지역을 되묻는 API가 없어 위에서부터 한 단계씩 내려가며 찾는다
// 지역 id가 들어 있는 시·도→시·군·구→읍·면·동 경로를 찾는다. 위에서부터 한 단계씩 후보를 넓히는 너비 우선 탐색
async function findPath(regionId) {
    // frontier: 지금 깊이까지의 후보 경로들. 처음에는 시·도 하나씩의 경로
    let frontier = (await children(null)).map((region) => [region]);
    for (let depth = 0; depth < LEVEL_LABELS.length && frontier.length > 0; depth++) {
        // 이번 깊이에서 찾는 id와 같은 지역이 있으면 그 경로가 답
        const hit = frontier.find((candidate) => candidate[depth].id === regionId);
        if (hit) {
            return hit;
        }
        // 마지막 깊이에서도 못 찾았으면 더 내려갈 곳이 없다
        if (depth === LEVEL_LABELS.length - 1) {
            break;
        }
        const next = [];
        // 각 후보의 하위 지역을 이어 붙여 다음 깊이의 후보를 만든다
        for (const candidate of frontier) {
            const kids = await children(candidate[depth].id);
            kids.forEach((kid) => next.push([...candidate, kid]));
        }
        frontier = next;
    }
    return null;
}

// 주소에서 온 대상(구역/지역)을 화면 상태(zone, path)로 바꾼다. 못 찾으면 처음 지역을 고른다
async function selectTarget(targetType, targetId) {
    let found = null;
    zone = null;
    if (targetType === 'ZONE' && targetId) {
        // 구역은 소속 동을 알아야 경로를 찾을 수 있어 전체 구역에서 찾는다
        zone = (await get('/api/v1/zones')).find((candidate) => candidate.id === targetId) ?? null;
        if (!zone) {
            // 목록에는 삭제되지 않은 구역만 있다. 삭제된 구역의 지난 통계를 주소로 열면 단건 조회로 찾아 '(삭제됨)'을 붙인다(FNC-A-017, #183)
            zone = await findDeletedZone(targetId);
        }
        found = zone ? await findPath(zone.dongId) : null;
        if (!found) {
            zone = null;
        }
    } else if (targetType === 'REGION' && targetId) {
        found = await findPath(targetId);
    }
    // 대상을 못 찾았을 때의 기본값
    if (!found) {
        // 처음엔 시 단위(통계 메인과 같음)
        const top = await children(null);
        found = top.length > 0 ? [top[0]] : [];
    }
    path = found;
    await renderTargetSelects();
}

// 지역 선택 3단계와 구역 선택의 선택지를 현재 선택(path, zone)에 맞게 다시 채운다
async function renderTargetSelects() {
    for (let depth = 0; depth < regionSelects.length; depth++) {
        const select = regionSelects[depth];
        // 깊이 0은 시·도 목록, 그 아래는 상위 선택이 있을 때만 그 하위 목록
        const options =
            depth === 0
                ? await children(null)
                : path[depth - 1]
                  ? await children(path[depth - 1].id)
                  : [];
        // 첫 칸에는 '전체' 선택지(상위 단계가 아닌 하위 단계에서만)
        select.replaceChildren(
            ...optionList(options, depth > 0 ? `${LEVEL_LABELS[depth]} 전체` : null),
        );
        // 상위가 선택되지 않았으면 하위 선택은 비활성
        select.value = path[depth] ? String(path[depth].id) : '';
        select.disabled = options.length === 0 || (depth > 0 && !path[depth - 1]);
        // 값이 바뀌면 그 아래 단계를 지우고 다시 조회한다
        select.onchange = () => onRegionChange(depth, select.value);
    }
    // 가장 아래 단계(읍·면·동)가 정해졌을 때만 구역 선택지가 생긴다
    const dong = path[LEVEL_LABELS.length - 1];
    const active = dong ? await zonesOf(dong.id) : [];
    // 삭제된 구역을 보는 중이면 그 구역도 선택 칸에 '(삭제됨)'으로 넣는다(다른 구역을 고르면 사라진다)
    const zones = zone && zone.deleted && zone.dongId === dong?.id ? [...active, zone] : active;
    zoneSelect.replaceChildren(...optionList(zones, '구역 전체'));
    zoneSelect.value = zone ? String(zone.id) : '';
    zoneSelect.disabled = zones.length === 0;
    zoneSelect.onchange = () => onZoneChange(zones, zoneSelect.value);
    renderCompareAvailability();
}

// 삭제된 구역이면 그 구역, 아니거나 없으면 null
// 삭제된 구역의 지난 통계를 보기 위한 단건 조회. 실패하면 null로 두어 화면이 기본 대상으로 돌아가게 한다
async function findDeletedZone(zoneId) {
    try {
        const found = await get(`/api/v1/zones/${zoneId}`, undefined, { toast: false });
        return found.deleted ? found : null;
    } catch {
        return null;
    }
}

// <option> 목록을 만든다. allLabel이 있으면 맨 앞에 값이 빈 '전체' 선택지를 넣는다
function optionList(entries, allLabel) {
    const list = entries.map((entry) => {
        const option = document.createElement('option');
        option.value = String(entry.id);
        option.textContent = entry.deleted ? `${entry.name} (삭제됨)` : entry.name;
        return option;
    });
    if (allLabel) {
        const all = document.createElement('option');
        all.value = '';
        all.textContent = allLabel;
        list.unshift(all);
    }
    return list;
}

// 지역 선택이 바뀌었을 때: depth 아래 선택을 지우고 새 선택을 path에 넣는다
async function onRegionChange(depth, value) {
    // depth 위의 선택은 유지한다
    const base = path.slice(0, depth);
    // 빈 값('전체')이면 그 단계 이하는 선택하지 않은 상태
    if (value !== '') {
        const options = depth === 0 ? await children(null) : await children(path[depth - 1].id);
        base.push(options.find((region) => String(region.id) === value));
    }
    path = base;
    zone = null;
    // 대상이 바뀌면 들어가기 기록은 끝난다
    clearTrail();
    await renderTargetSelects();
    await load();
}

// 구역 선택이 바뀌었을 때(빈 값이면 구역 전체=지역 대상)
async function onZoneChange(zones, value) {
    const previous = zone;
    zone = zones.find((candidate) => String(candidate.id) === value) ?? null;
    if (previous?.deleted && zone?.id !== previous.id) {
        // 삭제된 구역을 떠나면 선택 칸에서 '(삭제됨)' 항목을 뺀다
        await renderTargetSelects();
    }
    clearTrail();
    renderCompareAvailability();
    await load();
}

// 지금 조회 대상: 구역이 있으면 구역, 없으면 선택된 가장 아래 지역
function currentTarget() {
    if (zone) {
        return { targetType: 'ZONE', targetId: zone.id };
    }
    return { targetType: 'REGION', targetId: path[path.length - 1].id };
}

// 엑셀 다운로드 창(SCR-C-006)에 현재 대상·단위·기간을 넘긴다. 구역이면 소속 동도 범위로 고를 수 있게 함께 넘긴다
// 엑셀 다운로드 창에 현재 조건을 넘긴다
function openExport() {
    if (path.length === 0) {
        return;
    }
    const region = path[path.length - 1];
    openExportModal({
        zone: zone ? { id: zone.id, name: zone.name, deleted: Boolean(zone.deleted) } : null,
        region: { id: region.id, name: region.name },
        interval,
        from,
        to,
    });
}

// ---- 단위·기간·항목 ----------------------------------------------------------------------------

// 단위 탭: 단위를 바꾸면 기간을 기본값으로 되돌리고 다시 조회한다
function initTabs() {
    tabs.forEach((tab) => {
        tab.addEventListener('click', () => {
            interval = tab.dataset.interval;
            // 단위를 바꾸면 기간을 그 단위의 기본값으로(screens.md SCR-C-004 단위)
            setDefaultPeriod();
            clearTrail();
            renderTabs();
            renderPeriod();
            load();
        });
    });
}

// 선택된 탭을 강조하고 접근성 상태(aria-selected)를 맞춘다
function renderTabs() {
    tabs.forEach((tab) => {
        const on = tab.dataset.interval === interval;
        tab.classList.toggle('on', on);
        tab.setAttribute('aria-selected', String(on));
    });
}

// 1m: 최근 1시간, 1h: 오늘, 1d: 최근 30일, 1w: 최근 12주, 1M: 최근 12개월, 1y: 최근 5년
// 단위별 기본 기간을 정한다. to는 항상 '다음 경계'(끝을 포함하지 않음)
function setDefaultPeriod() {
    const now = new Date();
    // 오늘 자정과 내일 자정
    const tomorrow = startOfDay(now);
    tomorrow.setDate(tomorrow.getDate() + 1);
    switch (interval) {
        case '1m': {
            // 현재 분의 끝부터 60분 전까지
            to = new Date(Math.floor(now.getTime() / MINUTE_MS) * MINUTE_MS + MINUTE_MS);
            from = new Date(to.getTime() - 60 * MINUTE_MS);
            break;
        }
        case '1h':
            // 오늘 0시부터 내일 0시 전까지
            from = startOfDay(now);
            to = tomorrow;
            break;
        case '1d':
            to = tomorrow;
            from = shift(tomorrow, { days: -30 });
            break;
        case '1w': {
            // 1w는 월요일 시작(conventions.md '통계 단위')
            const monday = startOfDay(now);
            // (요일+6)%7: 일요일(0)->6, 월요일(1)->0. 이번 주 월요일 날짜로 옮긴다
            monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
            // 이번 주를 포함해 12주(11주 전 월요일부터)
            from = shift(monday, { days: -7 * 11 });
            to = tomorrow;
            break;
        }
        case '1M':
            // 이번 달 1일에서 11개월 전 = 최근 12개월
            from = shift(new Date(now.getFullYear(), now.getMonth(), 1), { months: -11 });
            to = tomorrow;
            break;
        default:
            // 올해 포함 5년
            from = new Date(now.getFullYear() - 4, 0, 1);
            to = tomorrow;
    }
}

function renderPeriod() {
    // 시작은 그대로, 끝은 마지막 분을 보이도록 to에서 1분 뺀 값을 입력칸에 넣는다
    fromInput.value = toInputValue(from);
    endInput.value = toInputValue(new Date(to.getTime() - MINUTE_MS));
}

// '적용' 버튼: 입력칸의 시각을 읽어 기간을 바꾸고 다시 조회한다
function applyPeriod() {
    const newFrom = parseLocal(fromInput.value);
    const end = parseLocal(endInput.value);
    // 비었거나 형식이 틀리면 안내만 하고 조회하지 않는다
    if (!newFrom || !end) {
        showError('시작·끝 시각을 입력해 주세요.');
        return;
    }
    from = newFrom;
    // 끝 칸은 '마지막 분'이므로 to는 거기에 1분을 더한다
    to = new Date(end.getTime() + MINUTE_MS);
    clearTrail();
    load();
}

// 항목은 하나 이상 골라야 한다(screens.md SCR-C-004 항목). 다시 읽지 않고 받은 값으로 다시 그린다
// 항목 체크박스. 마지막 하나를 끄려 하면 다시 켠다
function initItems() {
    itemBoxes.forEach((box) => {
        box.addEventListener('change', () => {
            if (selectedItems().length === 0) {
                box.checked = true;
                return;
            }
            render();
        });
    });
}

// 체크된 항목의 키(count, density, inflow, outflow, dwellAvgSec)
function selectedItems() {
    return [...itemBoxes].filter((box) => box.checked).map((box) => box.value);
}

// ---- 하위 비교(FNC-C-014) ----------------------------------------------------------------------

function initCompare() {
    // 하위 비교 켜짐/꺼짐
    compareToggle.addEventListener('change', () => {
        comparing = compareToggle.checked;
        renderCompareControls();
        load();
    });
    // 항목마다 서버가 값을 골라 주므로 항목을 바꾸면 다시 조회한다
    compareItemSelect.addEventListener('change', load);
    renderCompareControls();
}

// 하위 비교는 대상이 지역일 때만(screens.md SCR-C-004 하위 비교). 구역을 고르면 끈다
// 하위 비교는 지역이 대상일 때만 가능하다
function renderCompareAvailability() {
    const available = !zone && path.length > 0;
    compareToggle.disabled = !available;
    compareToggle.closest('label').title = available ? '' : '대상이 지역일 때만 쓸 수 있습니다';
    // 구역을 고르는 순간 비교 중이면 끈다
    if (!available && comparing) {
        comparing = false;
        compareToggle.checked = false;
        renderCompareControls();
    }
}

// 켜면 항목 선택을 보이고, 위쪽 항목 체크박스는 쓰지 않으므로 흐리게 한다
function renderCompareControls() {
    compareItemSelect.hidden = !comparing;
    itemGroup.classList.toggle('is-disabled', comparing);
    itemGroup.setAttribute('aria-disabled', String(comparing));
    omittedNotice.hidden = true;
}

// ---- 단계별 들어가기(FNC-C-013) ---------------------------------------------------------------

// 통합 그래프의 점을 눌렀을 때. index는 점 순번, position은 캔버스 안 위치
async function onPointClick(index, position) {
    // 점이 대표하는 구간의 시작 시각(서버 응답 at 그대로)
    const atText = pointTimes[index];
    if (!atText) {
        return;
    }
    const at = new Date(atText);
    // 1분 점은 더 내려갈 단계가 없다
    if (interval === '1m') {
        // 사용자 앱은 동작 없음, 관리자 앱은 다시보기(처리 순서 6)
        if (!IS_ADMIN) {
            return;
        }
        // 다시보기는 구역 영상이라 구역이 대상일 때만
        if (zone) {
            window.location.href = playbackUrl(atText);
        } else {
            showPointMenu(index, position, [], PLAYBACK_ZONE_ONLY_MESSAGE);
        }
        return;
    }
    // 1시간 점이 아니면(일·주·월·년) 바로 한 단계 아래로 들어간다
    if (interval !== '1h') {
        drillInto(at);
        return;
    }
    // 1시간 점: 1분 통계가 있어야 내려간다(업무 규칙: 보관 기간 지남·시연용 구간은 1분 데이터 없음)
    // 연달아 눌러도 마지막 요청의 응답만 처리하려는 번호
    const requestId = ++latestMenuRequestId;
    // 사용자 앱은 1분 데이터가 있으면 바로 들어가고 없으면 안내만 보인다
    if (!IS_ADMIN) {
        const available = await hasMinuteData(at);
        if (requestId !== latestMenuRequestId) {
            return;
        }
        if (available) {
            drillInto(at);
        } else {
            showPointMenu(
                index,
                position,
                [],
                available === null ? LOAD_FAILED_MESSAGE : NO_MINUTE_MESSAGE,
            );
        }
        return;
    }
    // 관리자 앱: '1분 단위로 보기' / '다시보기'(1시간 점 선택 창). 1분 버튼은 1분 통계를 확인한 뒤 켠다
    // 관리자: 선택 창에 '1분 단위로 보기'(1분 통계 확인 전까지 비활성)와 '다시보기'를 보인다
    const minuteButton = menuButton('1분 단위로 보기', () => drillInto(at));
    minuteButton.setAttribute('aria-disabled', 'true');
    const playbackLink = menuLink('다시보기', zone ? playbackUrl(atText) : null);
    showPointMenu(
        index,
        position,
        [minuteButton, playbackLink],
        zone ? null : PLAYBACK_ZONE_ONLY_MESSAGE,
    );
    // 창을 띄운 뒤 1분 통계가 있는지 확인해 버튼을 켜거나 이유를 보인다
    const available = await hasMinuteData(at);
    // 그 사이 다른 점을 누르거나 창을 닫았으면 무시한다
    if (requestId !== latestMenuRequestId || pointMenu.hidden) {
        return;
    }
    if (available) {
        minuteButton.removeAttribute('aria-disabled');
    } else {
        addMenuNotice(available === null ? LOAD_FAILED_MESSAGE : NO_MINUTE_MESSAGE);
    }
}

// 누른 점의 한 단계 아래로 들어가고 이동 경로에 남긴다
function drillInto(at) {
    // 한 단계 아래 단위·기간을 계산한다(drill.js). 아래가 없으면 할 일 없음
    const next = drillDown(interval, at);
    if (!next) {
        return;
    }
    // 처음 들어갈 때는 지금 화면을 이동 경로의 첫 칸으로 먼저 넣는다
    if (trail.length === 0) {
        trail.push({
            interval,
            from,
            to,
            label: `${INTERVALS[interval].label} 단위`,
            clickedInterval: null,
        });
    }
    // 새 칸은 방금 누른 점 기준의 이름과 단위를 갖는다
    const parent = trail[trail.length - 1];
    trail.push({
        ...next,
        label: crumbLabel(interval, at, parent.clickedInterval),
        clickedInterval: interval,
    });
    showView(next);
}

// 이동 경로의 한 칸을 누르면 그 단계로 돌아간다(후속 동작). 맨 앞 칸이면 경로를 지운다
function backTo(index) {
    // index 칸의 화면으로 돌아간다. 맨 앞이면 경로를 지운다
    const view = trail[index];
    trail = index === 0 ? [] : trail.slice(0, index + 1);
    showView(view);
}

// 단위·기간을 바꾸고 화면 요소와 그래프를 다시 그린다
function showView(view) {
    interval = view.interval;
    from = view.from;
    to = view.to;
    renderTrail();
    renderTabs();
    renderPeriod();
    load();
}

// 단위·기간·대상을 직접 바꾸면 들어간 단계 기록은 끝난다
// 경로를 비운다
function clearTrail() {
    trail = [];
    renderTrail();
}

// '1년 단위 › 2026년 › 10월 › 21일 › 17시'(마지막 칸이 지금 화면, 굵게)
function renderTrail() {
    // 칸 사이에 '›'를 넣는다
    const parts = [];
    trail.forEach((step, index) => {
        if (index > 0) {
            const separator = document.createElement('span');
            separator.setAttribute('aria-hidden', 'true');
            separator.textContent = '›';
            parts.push(separator);
        }
        // 마지막 칸이 지금 화면이므로 굵게 보이고, 나머지는 누르면 돌아가는 버튼
        if (index === trail.length - 1) {
            const current = document.createElement('b');
            current.setAttribute('aria-current', 'page');
            current.textContent = step.label;
            parts.push(current);
        } else {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = step.label;
            button.addEventListener('click', () => backTo(index));
            parts.push(button);
        }
    });
    drillPath.replaceChildren(...parts);
}

// 그 시간에 받은 1분 통계가 하나라도 있으면 true, 없으면 false, 읽지 못하면 null
// 그 시각의 1시간 구간에서 1분 단위 통계를 요청해 표본(sampleSec)이 있는 점이 있는지 확인한다
async function hasMinuteData(at) {
    // 그 점이 속한 1시간 구간
    const hour = drillDown('1h', at);
    try {
        const response = await get(
            SERIES_API,
            {
                ...currentTarget(),
                interval: '1m',
                from: toInputValue(hour.from),
                to: toInputValue(hour.to),
            },
            { toast: false },
        );
        return response.items.some((point) => point.sampleSec > 0);
    } catch {
        return null;
    }
}

// 다시보기 화면 주소(구역 id와 시각)
function playbackUrl(atText) {
    return `${url('/playback')}?zoneId=${zone.id}&at=${encodeURIComponent(atText)}`;
}

// 점 선택 창의 버튼. 비활성(aria-disabled)이면 눌러도 동작하지 않는다
function menuButton(label, onClick) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = label;
    button.addEventListener('click', () => {
        if (button.getAttribute('aria-disabled') !== 'true') {
            onClick();
        }
    });
    return button;
}

// 주소가 없으면(대상이 지역) 누를 수 없는 링크로 둔다
// 링크 형태 버튼(다시보기)
function menuLink(label, href) {
    const link = document.createElement('a');
    link.className = 'btn secondary sm';
    link.textContent = label;
    if (href) {
        link.href = href;
    } else {
        link.setAttribute('aria-disabled', 'true');
    }
    return link;
}

function initPointMenu() {
    // 그래프 밖을 누르거나 Esc를 누르면 닫는다. 그래프 안을 누르면 점 클릭이 새로 띄운다
    document.addEventListener('click', (event) => {
        if (!pointMenu.hidden && !pointMenu.contains(event.target) && event.target !== canvas) {
            hidePointMenu();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            hidePointMenu();
        }
    });
}

// 누른 점 옆에 구간·값 요약과 버튼(또는 안내)을 띄운다. 오른쪽 끝에서는 점 왼쪽에 띄운다
// 점 선택 창을 채우고 위치를 정한다
function showPointMenu(index, position, actions, notice) {
    // 제목은 구간, 요약은 값(하위 비교 중이면 없음)
    byId('point-menu-title').textContent = tooltipTitle(pointTimes[index]);
    const summary = byId('point-menu-summary');
    summary.textContent = comparing ? '' : pointSummary(items[index]);
    summary.hidden = summary.textContent === '';
    byId('point-menu-actions').replaceChildren(...actions);
    const noticeLine = byId('point-menu-notice');
    noticeLine.textContent = notice ?? '';
    noticeLine.hidden = !notice;
    pointMenu.hidden = false;
    // 창의 기준 박스와 눌린 위치(캔버스 좌표를 박스 좌표로)
    const box = pointMenu.parentElement;
    const x = canvas.offsetLeft + position.x;
    const y = canvas.offsetTop + position.y;
    const gap = 12;
    // 오른쪽에 공간이 없으면 점의 왼쪽에, 있으면 오른쪽에 띄운다
    const left =
        x + gap + pointMenu.offsetWidth > box.clientWidth
            ? x - gap - pointMenu.offsetWidth
            : x + gap;
    // 박스 밖으로 나가지 않게 0 이상, 박스 높이 이내로 맞춘다
    pointMenu.style.left = `${Math.max(left, 0)}px`;
    pointMenu.style.top = `${Math.max(Math.min(y - pointMenu.offsetHeight / 2, box.clientHeight - pointMenu.offsetHeight), 0)}px`;
}

// 이미 있는 안내 뒤에 문구를 덧붙인다
function addMenuNotice(text) {
    const noticeLine = byId('point-menu-notice');
    noticeLine.textContent = noticeLine.hidden ? text : `${noticeLine.textContent} ${text}`;
    noticeLine.hidden = false;
}

function hidePointMenu() {
    // 진행 중인 1분 통계 확인의 결과가 이 창에 반영되지 않게 번호를 올린다
    latestMenuRequestId++;
    pointMenu.hidden = true;
}

// '인원 평균 1,204명 · 밀집도 0.32'(시안). 값이 없으면 '데이터 없음'
// 점 요약 문구. 인원 평균이 없으면 '데이터 없음'
function pointSummary(point) {
    const count = point ? valueOf(point, 'countAvg') : null;
    if (count === null) {
        return '데이터 없음';
    }
    const density = valueOf(point, 'density');
    const densityText = density === null ? '' : ` · 밀집도 ${formatDecimal(density, 2)}`;
    return `인원 평균 ${formatNumber(Math.round(count))}명${densityText}`;
}

// ---- 조회·그리기 ----------------------------------------------------------------------------------

/** 화면에서 먼저 검사한다. 틀리면 문구를 보이고 조회하지 않는다. */
// 단위별 최대 조회 기간과 시작<끝을 서버에 묻기 전에 확인한다. 문제가 없으면 null
function validatePeriod() {
    if (to <= from) {
        return '끝 시각은 시작 시각보다 뒤여야 합니다.';
    }
    const spec = INTERVALS[interval];
    if (to > shift(from, spec.max)) {
        return `${spec.label} 단위는 최대 ${spec.maxLabel}까지 조회할 수 있습니다.`;
    }
    return null;
}

async function load() {
    // 대상(path)이 아직 정해지지 않았으면 조회하지 않는다
    if (path.length === 0) {
        return;
    }
    const invalid = validatePeriod();
    if (invalid) {
        showError(invalid);
        return;
    }
    // 조회 조건을 만든다. 시각은 오프셋 없이 'yyyy-MM-ddTHH:mm'
    const target = currentTarget();
    const query = { ...target, interval, from: toInputValue(from), to: toInputValue(to) };
    // 지금 보는 조건을 주소 쿼리에 남겨 새로고침·공유 때 같은 화면이 나오게 한다(페이지 이동은 없음)
    const url = new URL(window.location.href);
    Object.entries(query).forEach(([name, value]) => url.searchParams.set(name, String(value)));
    window.history.replaceState(null, '', url);

    // 조건을 빠르게 바꿀 때 늦게 오는 응답을 버리려는 번호
    const requestId = ++latestRequestId;
    // 이전 오류 문구와 점 선택 창을 지운다
    hideError();
    hidePointMenu();
    // 하위 비교와 일반 조회는 API와 응답 모양이 다르다
    try {
        // 오류는 그래프 위 문구로 보이므로 api.js의 토스트는 끈다
        if (comparing) {
            const childrenQuery = {
                regionId: target.targetId,
                item: compareItemSelect.value,
                interval,
                from: query.from,
                to: query.to,
            };
            const response = await get(CHILDREN_API, childrenQuery, { toast: false });
            // 최신 요청의 응답일 때만 그린다
            if (requestId === latestRequestId) {
                compareResult = response;
                render();
            }
        } else {
            const response = await get(SERIES_API, query, { toast: false });
            if (requestId === latestRequestId) {
                items = response.items;
                render();
            }
        }
    } catch (error) {
        // 실패해도 최신 요청이면 빈 그래프를 그리고 오류 문구를 보인다
        if (requestId === latestRequestId) {
            items = [];
            compareResult = { series: [], omitted: 0 };
            render();
            showError(errorText(error));
        }
    }
}

// 현재 상태(items 또는 compareResult)를 화면에 그린다
function render() {
    // '최대·최소 띠'는 통합 그래프의 인원 선에만 붙으므로 인원을 끄거나 하위 비교 중이면 쓸 수 없다
    const bandUsable = !comparing && selectedItems().includes('count');
    bandToggle.disabled = !bandUsable;
    bandToggle.closest('label').title = bandUsable
        ? ''
        : '인원 항목을 켜고 하위 비교를 끈 상태에서만 쓸 수 있습니다';
    // 하위 비교는 별도 함수로 그린다
    if (comparing) {
        renderCompare();
        return;
    }
    omittedNotice.hidden = true;
    panelsBox.hidden = false;
    // 고른 항목별로 그래프 데이터를 만든다
    const keys = selectedItems();
    const seriesByItem = {};
    keys.forEach((key) => {
        // 인원은 평균 선, '최대·최소 띠'를 켜면 최대~최소 띠도
        seriesByItem[key] =
            key === 'count' && bandToggle.checked
                ? {
                      values: items.map((point) => valueOf(point, 'countAvg')),
                      high: items.map((point) => valueOf(point, 'countMax')),
                      low: items.map((point) => valueOf(point, 'countMin')),
                  }
                : {
                      values: items.map((point) =>
                          valueOf(point, key === 'count' ? 'countAvg' : key),
                      ),
                  };
    });
    // 점마다 구간 시작 시각을 기억해 점을 눌렀을 때 쓴다
    pointTimes = items.map((point) => point.at);
    // 시연용 점(FNC-C-024)은 옅은 배경, 하나라도 있으면 '시연용 데이터 포함' 배지
    const demo = items.map((point) => Boolean(point.isDemo));
    demoBadge.hidden = !demo.some(Boolean);
    // x축 글자와 툴팁 제목
    const labels = items.map((point) => axisLabel(point.at));
    const titles = items.map((point) => tooltipTitle(point.at));
    // 통합 그래프를 그린다
    chart.update(labels, titles, seriesByItem, demo);
    // 아래 네 칸을 그리고, 방향 데이터가 있는지 받아 '방향 없음' 문구를 정한다
    const hasFlow = panels.update(labels, titles, items);
    directionEmpty.hidden = hasFlow;
    // 오류 문구가 보이는 동안에는 '데이터 없음'을 같이 보이지 않는다
    emptyNotice.hidden = !errorNotice.hidden || items.some((point) => point.sampleSec > 0);
}

// 하위 단위마다 한 선. 하위 단위의 점은 모두 같은 구간이라 x축은 첫 단위의 점으로 만든다
function renderCompare() {
    // 하위 단위별 선 하나씩. series[0]의 점으로 x축을 만든다
    const { series, omitted } = compareResult;
    const points = series.length > 0 ? series[0].points : [];
    pointTimes = points.map((point) => point.at);
    // 하위 비교 응답(/stats/children)에는 시연용 여부가 없어 배지를 숨긴다. 아래 세 칸은 한 대상의 그래프라 숨긴다
    demoBadge.hidden = true;
    panelsBox.hidden = true;
    // 고른 항목 한 개를 하위 단위마다 다른 색 선으로 겹쳐 그린다
    chart.compare(
        points.map((point) => axisLabel(point.at)),
        points.map((point) => tooltipTitle(point.at)),
        COMPARE_ITEM_KEYS[compareItemSelect.value],
        series.map((child) => ({
            name: child.name,
            values: child.points.map((point) =>
                point.value === null ? null : Number(point.value),
            ),
        })),
    );
    // 10개를 넘으면 인원 평균 상위 10개만 온다(FNC-C-014 업무 규칙)
    // 서버가 상위 10개만 보내고 나머지 수를 알려 준다
    omittedNotice.textContent = `나머지 ${omitted}개 생략`;
    omittedNotice.hidden = !(omitted > 0);
    // 값이 하나라도 있는지(전부 null이면 '데이터 없음' 문구를 보인다)
    const hasValue = series.some((child) => child.points.some((point) => point.value !== null));
    emptyNotice.hidden = !errorNotice.hidden || hasValue;
}

// 유입·유출은 방향(N·E·S·W) 합. 값이 없는 점은 null(선 끊김)
// 점에서 값 하나를 꺼내 숫자나 null로 돌려준다
function valueOf(point, key) {
    if (key === 'inflow' || key === 'outflow') {
        return flowSum(point[key]);
    }
    const value = point[key];
    return value === null || value === undefined ? null : Number(value);
}

// x축: 1m·1h는 시각(하루를 넘으면 날짜도), 1d·1w는 월-일, 1M은 연-월, 1y는 연
function axisLabel(at) {
    // 'yyyy-MM-dd HH:mm' 형태의 글자를 잘라 쓴다
    const text = formatDateTime(at); // yyyy-MM-dd HH:mm
    switch (interval) {
        case '1m':
        case '1h':
            // 기간이 하루를 넘으면 날짜까지, 아니면 시각만
            return to.getTime() - from.getTime() > 24 * 60 * MINUTE_MS
                ? text.slice(5)
                : text.slice(11);
        case '1d':
        case '1w':
            return text.slice(5, 10);
        case '1M':
            return text.slice(0, 7);
        default:
            return text.slice(0, 4);
    }
}

// 툴팁 제목: 구간(예: 17:00 ~ 18:00)
function tooltipTitle(at) {
    // 구간의 시작을 Date로
    const start = new Date(at);
    const text = formatDateTime(start);
    switch (interval) {
        case '1m':
            // 1분: '시작 ~ 끝 시각'
            return `${text} ~ ${formatDateTime(new Date(start.getTime() + MINUTE_MS)).slice(11)}`;
        case '1h':
            return `${text} ~ ${formatDateTime(new Date(start.getTime() + 60 * MINUTE_MS)).slice(11)}`;
        case '1d':
            return text.slice(0, 10);
        case '1w':
            // 1주: 시작 날짜 ~ 6일 뒤(일요일)
            return `${text.slice(0, 10)} ~ ${formatDateTime(shift(start, { days: 6 })).slice(5, 10)}`;
        case '1M':
            return text.slice(0, 7);
        default:
            return text.slice(0, 4);
    }
}

function errorText(error) {
    // ApiError가 아닌 예외는 콘솔에 남기고 기본 문구를 보인다
    if (!(error instanceof ApiError)) {
        console.error(error);
        return DEFAULT_ERROR_MESSAGE;
    }
    // 필드별 오류는 '필드: 이유'로 이어 붙인다
    if (error.fieldErrors.length > 0) {
        const details = error.fieldErrors.map(
            (fieldError) =>
                `${FIELD_LABELS[fieldError.field] ?? fieldError.field}: ${fieldError.message}`,
        );
        return `${error.message} (${details.join(' / ')})`;
    }
    return error.message;
}

// 오류 문구를 보이고 '데이터 없음'은 숨긴다
function showError(message) {
    errorNotice.textContent = message;
    errorNotice.hidden = false;
    emptyNotice.hidden = true;
}

function hideError() {
    errorNotice.hidden = true;
    errorNotice.textContent = '';
}

// ---- 시각 도우미 -------------------------------------------------------------------------------

// 날짜의 자정(00:00)
function startOfDay(date) {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

// 서버 Period/plus와 같게: 연·월을 더해 그 달에 없는 날이면 말일로 줄이고(2024-02-29 + 1년 = 2025-02-28), 일은 그 뒤에 더한다
// 연·월·일을 더하되 그 달에 없는 날이면 말일로 줄인다(서버 Period와 같은 규칙)
function shift(date, { years = 0, months = 0, days = 0 }) {
    const result = new Date(date.getTime());
    // 원래 '일'을 기억하고 1일로 옮겨 연·월을 먼저 더한다
    const day = result.getDate();
    result.setDate(1);
    result.setFullYear(result.getFullYear() + years, result.getMonth() + months, 1);
    // 더한 달의 말일(다음 달 0일)
    const lastDay = new Date(result.getFullYear(), result.getMonth() + 1, 0).getDate();
    result.setDate(Math.min(day, lastDay) + days);
    return result;
}

// 한 자리 숫자를 두 자리로
const pad = (value) => String(value).padStart(2, '0');

// datetime-local 칸과 주소 쿼리에 쓰는 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

// 'yyyy-MM-dd', 'yyyy-MM-ddTHH:mm', 'yyyy-MM-ddTHH:mm:ss'를 브라우저 시각으로 읽는다. 틀리면 null
function parseLocal(text) {
    // 형식 'yyyy-MM-dd' 또는 시각(초는 선택)까지. 시·분·초가 없으면 0으로
    const match = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec(text ?? '');
    if (!match) {
        return null;
    }
    const [, year, month, day, hour = '0', minute = '0', second = '0'] = match;
    // 월은 0부터 시작하므로 1을 뺀다
    return new Date(
        Number(year),
        Number(month) - 1,
        Number(day),
        Number(hour),
        Number(minute),
        Number(second),
    );
}
