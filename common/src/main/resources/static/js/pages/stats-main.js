// 통계 메인(대시보드) 진입 파일 SCR-C-003: 지역 필터(FNC-C-008), 통계 패널(FNC-C-009·016·020·025·030), 지도, 캠 패널(관리자 앱).
// 지역은 주소 쿼리 regionId로 유지한다. 새 1분 통계는 통계 SSE(FNC-C-022)가 알려 주면 화면 값을 새로 읽고,
// 연결이 끊겨 있거나 한참 소식이 없으면 60초마다 읽는다.
import { del, get, url } from '../common/api.js';
import { initCameraForm } from '../camera/camera-form.js';
import { initExportModal, openExportModal } from '../export/export-modal.js';
import { initLiveView, openLiveView, updateLiveView } from '../live/live-view.js';
import { nowTotal, onNowCounts, setNowCounts } from '../common/now-count.js';
import { showToast } from '../common/toast.js';
import { createRegionMap, createStatusLayer } from '../map/status-map.js';
import { createAbnormalPanel } from './stats-main/abnormal.js';
import { createAuxMetricsPanel } from './stats-main/aux-metrics.js';
import { createDeck } from './stats-main/deck.js';
import { createHourlyLevelsPanel } from './stats-main/hourly-levels.js';
import { createVisitPatternPanel } from './stats-main/visit-pattern.js';
import { createPeakHoursPanel } from './stats-main/peak-hours.js';
import { createConfirmDialog } from './stats-main/confirm-dialog.js';
import { createMapMenu } from './stats-main/map-menu.js';
import { createZoneEdit } from './stats-main/zone-edit.js';
import { createZoneRegister } from './stats-main/zone-register.js';
import { createTrendPanel } from './stats-main/trend.js';
import { createFlowTool } from './stats-main/flow-tool.js';
import { createHeatmapTool } from './stats-main/heatmap-tool.js';
import {
    renderGraphLink,
    renderLevelRules,
    renderMapLegend,
    renderSummary,
    renderSummaryCount,
    renderTarget,
    renderZonePanel,
} from './stats-main/panel.js';

// 통계를 직접 다시 읽는 주기(60초). 실시간 연결이 끊겨 있을 때의 안전장치다
const REFRESH_MS = 60_000;
// 연결돼 있어도 이 시간 동안 새로 읽은 적이 없으면 스트림이 멈춘 것으로 보고 직접 읽는다
const STALE_MS = 150_000;
// 새 분 소식이 연달아 와도(여러 구역 저장) 한 번만 읽는다
const STREAM_DEBOUNCE_MS = 300;
// 지역 단계 이름(깊이 0 시·도, 1 시·군·구, 2 읍·면·동)
const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];
// 서버가 읍·면·동 단계를 나타내는 코드. 이 단계에서만 구역을 다룬다
const EMD = 'EMD';

// 관리자 앱 여부(템플릿의 data-admin). 관리자 전용 기능(캠 패널·우클릭 메뉴·구역 등록)을 켠다
const isAdmin = document.getElementById('dash').dataset.admin === 'true';
// 지역 선택칸 세 개(시·도, 시·군·구, 읍·면·동)
const selects = ['filter-sido', 'filter-sigungu', 'filter-emd'].map((id) =>
    document.getElementById(id),
);
// 지도 왼쪽 위 '상위 지역으로' 버튼
const backButton = document.getElementById('map-back');

// 화면 상태: 혼잡 단계 정의(levels), 선택 경로(path), 지도·핀 층·캠 패널(deck)
let levels = [];
let path = []; // 시·도부터 고른 지역 목록. 마지막이 통계 대상
let map = null;
let layer = null;
let deck = null;
// 오른쪽 패널의 각 영역(이상 징후, 시간대별 혼잡, 방문 패턴, 피크 시간, 추이)과 지도 도구(이동 흐름, 히트맵)
const abnormalPanel = createAbnormalPanel();
const hourlyLevelsPanel = createHourlyLevelsPanel();
const visitPatternPanel = createVisitPatternPanel();
const peakHoursPanel = createPeakHoursPanel();
const trendPanel = createTrendPanel();
const flowTool = createFlowTool();
const heatmapTool = createHeatmapTool();
// 지도 우클릭 메뉴(관리자 앱만): 구역 위·빈 곳을 우클릭하면 구역·캠 수정·등록·삭제 항목이 뜬다
// 메뉴가 닫히면 붙들어 둔 구역 강조도 푼다
const mapMenu = isAdmin ? createMapMenu({ onClose: () => layer?.releaseZone() }) : null;
// 확인 창(구역 삭제·캠 삭제)
const confirmDialog = isAdmin ? createConfirmDialog() : null;
// 구역 등록(관리자 앱만, 동 단계). 등록하면 지도·통계를 다시 읽는다
const zoneRegister = isAdmin ? createZoneRegister({ onRegistered: onZoneRegistered }) : null;
// 구역 수정(관리자 앱만, 동 단계): 저장하거나 취소하면 지도·통계를 다시 읽는다(취소하면 원래 모양)
const zoneEdit = isAdmin
    ? createZoneEdit({
          getLayer: () => layer,
          dongName: () => path[path.length - 1].name,
          onChanged: () => refresh(),
          // 구역을 수정하는 동안에는 하단 안내 문구('빈 곳을 우클릭하면 구역을 등록할 수 있습니다')를 숨긴다
          onActiveChange: (active) => zoneRegister?.setSuppressed(active),
      })
    : null;
// 동 단계에서 지도에 그려진 구역(GET /zones). 우클릭 메뉴가 구역 이름·면적을 쓴다
let zoneShapeList = [];
// 캠 등록 창(관리자 앱만): 캠이 없는 구역의 핀을 누르면 연다. 저장하면 지도·캠 패널을 다시 읽는다
const cameraForm = isAdmin ? initCameraForm({ onSaved: () => refresh() }) : null;
// 보조 지표 패널, 구역 목록과 탭 상태
let auxPanel = null;
let zoneItems = [];
let zoneTab = 'count';
// 지금 지도를 그린 지역 id. 같은 지역이면 지도를 다시 만들지 않으려고 비교한다
let renderedRegionId = null;
// 실시간 연결(SSE)과 연결 상태, 소식이 몰릴 때 한 번만 읽으려는 타이머, 마지막으로 읽은 시각
let stream = null;
let streamConnected = false;
let streamTimer = null;
let lastRefreshAt = 0;
let lastSummary = null; // 마지막으로 읽은 요약. '현재 인원' 칸만 5초마다 다시 쓸 때 되돌릴 값을 얻는다
const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록

// 모듈이 불러와지면 바로 시작한다
start();

// 첫 화면 준비: 혼잡 단계 정의를 읽고, 각 패널·도구를 초기화한 뒤 주소의 지역을 연다
async function start() {
    // 혼잡 단계 이름·색·기준(서버 값). 범례·패널이 같은 값을 쓴다
    levels = await get('/api/v1/meta/levels');
    auxPanel = createAuxMetricsPanel(levels);
    renderMapLegend(levels);
    renderLevelRules(levels);
    initPanelToggle();
    initZoneTabs();
    initExportModal();
    document.getElementById('export-open').addEventListener('click', openExport);
    // 브라우저 뒤로 가기·앞으로 가기를 하면 주소의 지역으로 다시 맞춘다
    window.addEventListener('popstate', () => selectFromUrl());
    // 관리자 앱: 실시간 보기 창과 캠 패널(deck)을 준비한다
    if (isAdmin) {
        initLiveView();
        deck = createDeck({
            levels,
            // 캠 목록을 다시 읽는 함수, 실시간 보기를 여는 함수, 크기가 바뀌면 지도를 맞추는 함수를 건넨다
            onReload: loadCameras,
            onOpenLive: openLiveView,
            onResize: () => {
                layer?.refreshSize();
                updateVisibleZones();
            },
        });
    }
    // 5초 현재 인원이 오거나 만료될 때 요약의 '현재 인원'만 다시 쓴다
    // 요약 숫자의 '현재 인원' 칸을 5초 값 또는 마지막으로 읽은 요약으로 다시 쓴다
    onNowCounts(() => renderSummaryCount(lastSummary, nowTotal()));
    // 주소의 지역을 열고 처음 그린다
    await selectFromUrl();
    // 60초마다 읽을 필요가 있는지 확인한다(연결이 끊겼거나 소식이 한참 없을 때만 읽는다)
    window.setInterval(pollIfNeeded, REFRESH_MS);
}

// ---- 실시간 통계 갱신 (FNC-C-022) -------------------------------------------------------------

// 지역이 바뀌면 그 지역으로 다시 연결한다. 끊기면 EventSource가 retry(5초)로 알아서 다시 연결한다
// 지역 id로 통계 연결(SSE)을 새로 연다. 이전 연결은 닫는다
function connectStream(regionId) {
    // 지역이 바뀌었으니 이전 연결을 닫는다
    stream?.close();
    streamConnected = false;
    // regionId를 쿼리로 넘겨 그 지역의 통계 소식을 받는다
    const source = new EventSource(url(`/api/v1/stats/stream?regionId=${regionId}`));
    // 늦게 도착한 소식이 옛 연결 것인지 가려내려고 현재 연결을 기억해 둔다
    stream = source;
    // 연결되면 '연결 끊김' 표시를 숨기고, 오류가 나면 보인다
    source.addEventListener('open', () => setStreamConnected(true));
    source.addEventListener('error', () => setStreamConnected(false));
    // 5초마다 현재 인원만 온다(FNC-C-022). 요약·지도·캠 목록을 다시 읽지 않고 숫자 칸만 바꾼다
    // now 이벤트: 5초마다 오는 현재 인원. 숫자 칸만 바꾸고 다른 화면은 다시 읽지 않는다
    source.addEventListener('now', (event) => {
        // 이미 다른 지역으로 옮겨 닫힌 연결의 소식은 무시한다
        if (stream !== source) {
            return;
        }
        try {
            setNowCounts(JSON.parse(event.data).items ?? []);
        } catch {
            // 깨진 소식은 건너뛴다. 다음 5초에 다시 온다
        }
    });
    // stat 이벤트: 새 1분 통계가 저장됐다는 알림. 여러 구역이 연달아 저장돼도 한 번만 읽도록 잠깐 기다렸다 읽는다
    source.addEventListener('stat', () => {
        // 이미 다른 지역으로 옮겨 닫힌 연결의 늦은 소식은 무시한다
        if (stream !== source) {
            return;
        }
        // 이전에 걸어 둔 타이머를 취소하고 다시 건다(디바운스)
        window.clearTimeout(streamTimer);
        streamTimer = window.setTimeout(refreshSafely, STREAM_DEBOUNCE_MS);
    });
}

// 연결 상태를 기록하고 끊겼을 때만 경고 표시를 보인다
function setStreamConnected(connected) {
    streamConnected = connected;
    document.getElementById('stream-status').hidden = connected;
}

// 60초마다 호출: 연결이 끊겼거나 150초 동안 새로 읽은 적이 없으면 직접 읽는다
function pollIfNeeded() {
    if (!streamConnected || Date.now() - lastRefreshAt > STALE_MS) {
        refreshSafely();
    }
}

// 읽기를 시도하되 실패해도 예외를 밖으로 내보내지 않는다(타이머·이벤트 처리기가 멈추지 않게)
async function refreshSafely() {
    try {
        await refresh();
    } catch {
        // 읽기에 실패해도 다음 소식이나 60초 확인에 다시 읽는다. 오류 토스트는 api.js가 띄운다
    }
}

// ---- 지역 필터 -------------------------------------------------------------------------------

// 하위 지역 목록(캐시). parentId가 null이면 시·도
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

// 주소의 regionId로 시·구·동 경로를 찾는다. 상위 지역을 되묻는 API가 없어 위에서부터 한 단계씩 내려가며 찾는다
// 지역 id가 들어 있는 시·구·동 경로를 너비 우선으로 찾는다(stats-graph.js의 findPath와 같은 방식)
async function findPath(regionId) {
    // 처음 후보는 시·도 하나씩의 경로
    let frontier = (await children(null)).map((region) => [region]);
    for (let depth = 0; depth < LEVEL_LABELS.length && frontier.length > 0; depth++) {
        // 이번 깊이에 목표 지역이 있으면 그 경로를 돌려준다
        const hit = frontier.find((candidate) => candidate[depth].id === regionId);
        if (hit) {
            return hit;
        }
        // 마지막 깊이를 확인했으면 더 내려가지 않는다
        if (depth === LEVEL_LABELS.length - 1) {
            break;
        }
        const next = [];
        // 각 후보의 하위 지역을 이어 다음 깊이의 후보를 만든다
        for (const candidate of frontier) {
            const kids = await children(candidate[depth].id);
            kids.forEach((kid) => next.push([...candidate, kid]));
        }
        frontier = next;
    }
    return null;
}

// 주소의 regionId를 읽어 경로를 찾고 화면에 적용한다. 새로 고침·뒤로 가기에서 같은 지역이 열린다
async function selectFromUrl() {
    const regionId = Number(new URLSearchParams(window.location.search).get('regionId'));
    let found = regionId ? await findPath(regionId) : null;
    if (!found) {
        // 처음엔 시 단위
        const top = await children(null);
        found = top.length > 0 ? [top[0]] : [];
    }
    await applyPath(found, false);
}

// 선택 경로를 적용한다. pushUrl이면 주소에도 남겨 뒤로 가기가 되게 한다
async function applyPath(newPath, pushUrl) {
    path = newPath;
    // 빈 경로면 할 일이 없다
    if (path.length === 0) {
        return;
    }
    // 통계 대상은 경로의 마지막(가장 아래) 지역
    const target = path[path.length - 1];
    if (pushUrl) {
        const url = new URL(window.location.href);
        url.searchParams.set('regionId', String(target.id));
        window.history.pushState(null, '', url);
    }
    // 선택칸을 새 경로에 맞게 다시 채우고, 지도와 통계를 읽는다
    await renderFilter();
    await loadRegion();
}

// 선택칸 세 개를 현재 경로에 맞게 다시 채운다(상위가 비어 있으면 하위는 비활성)
async function renderFilter() {
    for (let depth = 0; depth < selects.length; depth++) {
        const select = selects[depth];
        const options =
            depth === 0
                ? await children(null)
                : path[depth - 1]
                  ? await children(path[depth - 1].id)
                  : [];
        select.replaceChildren(...optionList(options, depth));
        select.value = path[depth] ? String(path[depth].id) : '';
        select.disabled = options.length === 0 || (depth > 0 && !path[depth - 1]);
        select.onchange = () => onFilterChange(depth, select.value);
    }
}

// <option> 목록. 시·도 아래 단계에는 맨 앞에 '전체'(값 빈 문자열)를 둔다
function optionList(regions, depth) {
    const list = regions.map((region) => {
        const option = document.createElement('option');
        option.value = String(region.id);
        option.textContent = region.name;
        return option;
    });
    if (depth > 0) {
        const all = document.createElement('option');
        all.value = '';
        all.textContent = `${LEVEL_LABELS[depth]} 전체`;
        list.unshift(all);
    }
    return list;
}

// 시 → 구 → 동 순으로 고르고, 고른 가장 아래 단위가 통계 대상
// 칸에서 지역을 고르면 그 아래 단계 선택은 지우고 새 경로를 적용한다
async function onFilterChange(depth, value) {
    const base = path.slice(0, depth);
    if (value !== '') {
        const options = depth === 0 ? await children(null) : await children(path[depth - 1].id);
        base.push(options.find((region) => String(region.id) === value));
    }
    await applyPath(base, true);
}

// 엑셀 다운로드 창(SCR-C-006)에 현재 지역을 넘긴다. 단위·기간은 통계 그래프의 기본값(1h, 오늘)
// 엑셀 다운로드 창에 현재 지역과 오늘 하루(1시간 단위)를 기본 조건으로 넘긴다
function openExport() {
    const target = path[path.length - 1];
    if (!target) {
        return;
    }
    // 오늘 0시부터 내일 0시 전까지
    const today = new Date();
    const from = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    const to = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    openExportModal({ region: { id: target.id, name: target.name }, interval: '1h', from, to });
}

// ---- 값 읽기·그리기 --------------------------------------------------------------------------

// 지역이 바뀌었을 때(또는 처음) 지도·층·패널을 새 지역에 맞게 만든다
async function loadRegion() {
    const target = path[path.length - 1];
    // '위로' 버튼과 통계 그래프 링크는 지역마다 달라진다
    renderBackButton();
    renderGraphLink(target.id);
    // 지역이 그대로이면(같은 지역 새로 고침) 지도를 새로 만들지 않고 값만 갱신한다
    const resetView = renderedRegionId !== target.id;
    if (resetView) {
        // 지역에 맞는 지도를 새로 만든다(이전 지도는 없앤다)
        map = createRegionMap(document.getElementById('dash-map'), target, map);
        // 지도 위 경계·핀 층. 각 처리기를 연결한다
        layer = createStatusLayer(map, {
            onSelectRegion: selectChildRegion,
            onSelectSibling: selectSiblingRegion,
            onZoneMenu: isAdmin ? openZoneMenu : undefined,
            onEmptyMenu: isAdmin ? openEmptyMenu : undefined,
            // 구역을 그리거나 고치는 중에는 지도의 지역·구역 클릭과 우클릭을 무시한다
            isBusy: isMapBusy,
            onViewStart: () => mapMenu?.close(),
            onViewChange: updateVisibleZones,
            // 관리자 앱: 구역 핀을 누르면 그 구역 캠의 실시간 보기 창(SCR-A-008)
            // 사용자 앱: onPinClick이 없으면 구역 정보 팝업(인원·밀집도·단계, SCR-C-003)
            onPinClick: isAdmin ? openLiveForZone : undefined,
            levels,
        });
        // 지금 지도를 그린 지역 기록. 이후 패널·도구에 새 지역을 알린다
        renderedRegionId = target.id;
        // 구역 수정·등록 도구는 새 지도에 맞게 초기화한다
        zoneEdit?.reset();
        zoneRegister?.attach(map);
        // 새 지역의 통계 연결(SSE)을 연다
        connectStream(target.id);
        abnormalPanel.setRegion(target.id);
        hourlyLevelsPanel.setRegion(target.id);
        visitPatternPanel.setRegion(target.id);
        peakHoursPanel.setRegion(target.id);
        trendPanel.setRegion(target.id);
        flowTool.setRegion(map, target.id, target.level === EMD);
        heatmapTool.setRegion(layer, target.id);
        auxPanel.setRegion(target.id);
    }
    // 값(요약·구역·경계·핀)을 읽어 그린다
    await refresh();
}

// 지도 뒤로가기: 누르면 한 단계 위 지역으로 올라간다(동 → 구 → 시). 가장 위 단계에서는 숨긴다
// 지도 뒤로가기: 한 단계 위 지역으로 올라간다. 시 단계에서는 숨긴다
backButton.addEventListener('click', () => {
    if (path.length > 1) {
        applyPath(path.slice(0, -1), true);
    }
});

// 한 단계 위 지역이 있을 때만 버튼을 보이고 이름을 붙인다
function renderBackButton() {
    const parent = path.length > 1 ? path[path.length - 2] : null;
    backButton.hidden = parent === null;
    if (parent) {
        backButton.textContent = `← ${parent.name}`;
    }
}

// 지도에서 하위 지역을 눌렀을 때 그 하위 지역으로 내려간다
async function selectChildRegion(id) {
    const target = path[path.length - 1];
    const child = (await children(target.id)).find((region) => region.id === id);
    if (child) {
        await applyPath([...path, child], true);
    }
}

// 구역을 등록하면 지도 경계·핀·통계·캠 패널을 다시 읽는다. 다른 동에 등록됐으면 그 동을 골라야 보인다고 알린다
// 방금 등록된 구역이 보고 있는 동이 아니면 지도에 안 보인다고 알린다
async function onZoneRegistered(created) {
    const target = path[path.length - 1];
    if (target.level === EMD && created.dongId !== target.id) {
        showToast('다른 동에 등록되었습니다. 해당 동을 선택하면 지도에 보입니다.');
    }
    await refresh();
}

// ---- 지도 우클릭 메뉴(관리자 앱, 동 단계) -------------------------------------------------
// 항목은 기능마다 이슈를 나눠 더한다(구역 등록·수정·삭제, 캠 수정·삭제). 항목이 없으면 메뉴를 띄우지 않는다

// 구역을 그리거나 고치는 중이면 true
// 구역 등록·수정 중에는 지도 클릭이 다른 일을 하지 않도록 판단하는 기준
function isMapBusy() {
    return zoneRegister?.isActive() === true || zoneEdit?.isActive() === true;
}

// 구역을 우클릭했을 때. 동 단계이고 구역 모양 정보가 있을 때만 메뉴를 연다
function openZoneMenu(zoneId, event) {
    const zone = zoneShapeList.find((candidate) => candidate.id === zoneId);
    if (zone && path[path.length - 1].level === EMD) {
        // 메뉴가 떠 있는 동안 그 구역이 강조된 채로 남게 한다
        // 메뉴가 열려 있는 동안 그 구역을 계속 강조한다
        layer.holdZone(zoneId);
        mapMenu.open({
            x: event.clientX,
            y: event.clientY,
            title: zone.name,
            items: buildZoneMenu(zone),
        });
    }
}

// 빈 곳을 우클릭했을 때. 동 단계일 때만 메뉴를 연다
function openEmptyMenu(latLng, event) {
    const target = path[path.length - 1];
    if (target.level === EMD) {
        mapMenu.open({
            x: event.clientX,
            y: event.clientY,
            title: target.name,
            items: buildEmptyMenu(),
        });
    }
}

/** 구역 위 우클릭 메뉴 항목 */
// 구역 우클릭 메뉴 항목을 만든다: 구역 수정, 구역 삭제, (캠이 있으면) 캠 수정·캠 삭제
function buildZoneMenu(zone) {
    const items = [{ label: '구역 수정', onClick: () => zoneEdit.begin(zone) }];
    // 가동 중이거나 가동을 요청한 캠이 있는 구역은 지울 수 없다(구역 관리 화면과 같은 규칙)
    // 이 구역에 설치된 캠(없으면 undefined)
    const camera = deck?.cameraByZone(zone.id);
    // 가동 중·준비 중이거나 가동 요청이 남은 캠이 있으면 구역을 지울 수 없다
    const running =
        camera &&
        (camera.status === 'RUNNING' || camera.status === 'PREPARING' || camera.runRequested);
    items.push({
        label: '구역 삭제',
        danger: true,
        disabled: Boolean(running),
        hint: ZONE_DELETE_BLOCKED,
        onClick: () => askDeleteZone(zone),
    });
    if (camera) {
        // 캠 수정(FNC-A-019): 정지 상태일 때만 고칠 수 있다(캠 관리 화면과 같은 규칙). 대시보드 위에 캠 수정 창을 연다
        // 캠 수정은 정지 상태이고 가동 요청이 없을 때만 가능하다
        items.push({
            label: '캠 수정',
            disabled: camera.status !== 'STOPPED' || Boolean(camera.runRequested),
            hint: CAMERA_EDIT_BLOCKED,
            onClick: () => cameraForm.openEdit(camera),
        });
        // 캠 삭제(FNC-A-023): 정지 상태일 때만. 가동 요청 상태면 그 안내를 보인다
        items.push({
            label: '캠 삭제',
            danger: true,
            disabled: camera.status !== 'STOPPED' || Boolean(camera.runRequested),
            // 가동 요청 안내는 정지 상태인데 요청만 남은 때(캠 관리 화면과 같은 규칙). 가동 중·준비 중이면 정지부터 하라고 안내한다
            hint:
                camera.status === 'STOPPED' && camera.runRequested
                    ? CAMERA_RUN_PENDING
                    : CAMERA_DELETE_BLOCKED,
            onClick: () => askDeleteCamera(camera),
        });
    }
    return items;
}

// 메뉴 항목이 비활성일 때 보이는 이유 문구
const CAMERA_DELETE_BLOCKED = '정지 상태에서만 삭제할 수 있습니다. 먼저 정지해 주세요.';
const CAMERA_RUN_PENDING = '가동 요청 처리 중입니다. 잠시 뒤 다시 시도하거나 정지해 주세요';
const CAMERA_EDIT_BLOCKED = '정지 상태에서만 고칠 수 있습니다. 먼저 정지해 주세요.';
const ZONE_DELETE_BLOCKED =
    '가동 중이거나 가동을 요청한 캠이 있어 삭제할 수 없습니다. 캠을 먼저 정지해 주세요.';

// 구역 삭제(FNC-A-017): 확인 뒤 DELETE. 삭제되면 지도·핀·통계·캠 패널을 다시 읽는다
// 확인 창을 열고, 확인하면 구역을 서버에서 삭제한다
function askDeleteZone(zone) {
    confirmDialog.open({
        title: '구역 삭제',
        message: `'${zone.name}' 구역을 삭제할까요? 가동 이력이 있으면 기록은 남고 목록에서만 사라집니다.`,
        okLabel: '삭제',
        // 서버가 가동 중이라며 거절하면 이 문구를 창에 보인다
        errorMessages: { CAMERA_RUNNING: ZONE_DELETE_BLOCKED },
        // 서버가 거절하면(가동 중, 이미 지워짐) 화면 값이 낡았다는 뜻이라 다시 읽어 메뉴 항목을 새로 맞춘다
        onRejected: () => refreshSafely(),
        onConfirm: async () => {
            await del(`/api/v1/manage/zones/${zone.id}`, { toast: false });
            showToast(`'${zone.name}' 구역을 삭제했습니다.`);
            // 삭제는 끝났으니, 그 뒤 다시 읽기가 실패해도 확인 창에 오류로 보이지 않게 한다(다시 눌러 404가 나지 않게)
            await refreshSafely();
        },
    });
}

// 캠 삭제(FNC-A-023): 확인 뒤 DELETE. 삭제되면 구역은 남고 캠 패널·핀만 다시 읽는다
// 확인 창을 열고, 확인하면 캠을 서버에서 삭제한다
function askDeleteCamera(camera) {
    confirmDialog.open({
        title: '캠 삭제',
        message: '이 캠을 삭제할까요? 가동한 적이 있으면 지난 녹화·통계는 남습니다.',
        okLabel: '삭제',
        errorMessages: { CAMERA_NOT_STOPPED: CAMERA_DELETE_BLOCKED },
        // 서버가 거절하면(그 사이 가동됨 등) 화면 값이 낡았다는 뜻이라 다시 읽어 메뉴 항목을 새로 맞춘다
        onRejected: () => refreshSafely(),
        onConfirm: async () => {
            await del(`/api/v1/manage/cameras/${camera.id}`, { toast: false });
            showToast(`'${camera.name}' 캠을 삭제했습니다.`);
            // 삭제는 끝났으니, 그 뒤 다시 읽기가 실패해도 확인 창에 오류로 보이지 않게 한다
            await refreshSafely();
        },
    });
}

/** 지금 동 안의 빈 곳 우클릭 메뉴 항목 */
// 빈 곳 우클릭 메뉴: 구역 등록 하나
function buildEmptyMenu() {
    const items = [{ label: '구역 등록', onClick: () => zoneRegister.begin() }];
    return items;
}

// 동 단계에서 이웃 동 윤곽선을 누르면 그 동으로 이동한다
// 이웃 동으로 이동: 상위 지역의 하위 목록에서 눌린 동을 찾는다
async function selectSiblingRegion(id) {
    const parent = path.length >= 2 ? path[path.length - 2] : null;
    const sibling = parent ? (await children(parent.id)).find((region) => region.id === id) : null;
    if (sibling) {
        await applyPath([...path.slice(0, -1), sibling], true);
    }
}

// 현재 지역의 요약·구역 상태·하위 지역 상태·구역 모양을 한 번에 읽어 화면을 갱신한다
async function refresh() {
    const target = path[path.length - 1];
    // 동 단계(읍·면·동)에서만 구역 모양(경계 도형)을 읽는다
    const isDong = target.level === EMD;
    // 네 요청을 병렬로 보낸다. 구역 모양은 동일 때만 읽고 아니면 빈 배열
    const [summary, zones, child, zoneShapes] = await Promise.all([
        get('/api/v1/dashboard/summary', { targetType: 'REGION', targetId: target.id }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'ZONE' }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'CHILD' }),
        isDong ? get('/api/v1/zones', { dongId: target.id }) : Promise.resolve([]),
    ]);
    // 읽기에 성공했을 때만 기록한다. 실패하면 다음 60초 확인이 바로 다시 읽는다
    // 읽은 시각을 기록한다(60초 확인이 직접 읽을지 판단하는 기준)
    lastRefreshAt = Date.now();
    // 읽는 동안 지역이 바뀌었으면 이 결과를 버린다(옛 지역 값을 새 지도에 그리지 않게)
    if (target !== path[path.length - 1]) {
        return; // 읽는 동안 지역이 바뀌었다
    }
    // 읽은 값을 화면 상태에 저장한다
    zoneItems = zones.items;
    zoneShapeList = zoneShapes;
    // 패널을 다시 그린다: 대상 이름·캠 수, 요약, 구역 목록(탭에 따라)
    renderTarget(target.name, summary.cameraCount);
    lastSummary = summary;
    renderSummary(summary, levels);
    renderZonePanel(zoneItems, zoneTab, levels);
    // 보조 지표(FNC-C-030): 펼쳐 있는 영역만 시계열을 다시 읽는다. 읽기 실패는 영역 안에 문구로 보인다
    auxPanel.refresh(zoneItems);
    // 시간대별 혼잡 단계(FNC-C-018): 현재 시간 막대가 1분 통계로 바뀐다
    hourlyLevelsPanel.refresh();

    // 하위 지역 id -> 통계 항목 표. 경계를 칠할 때 id로 통계를 찾는다
    const statusById = new Map(child.items.map((item) => [item.id, item]));
    // 구역 모양을 고치는 중이면 경계를 다시 그리지 않는다(고치는 도형이 지워지지 않게)
    if (zoneEdit?.isActive()) {
        // 구역 모양을 고치는 중에는 경계를 다시 그리지 않는다(고치는 모양이 지워지지 않게). 핀·패널만 갱신한다
    } else if (isDong) {
        // 동이면 하위 단위가 구역이라 구역 경계를 칠한다
        layer.showBoundaries(
            zoneShapes.map((zone) => ({
                id: zone.id,
                name: zone.name,
                boundary: zone.boundary,
                item: statusById.get(zone.id),
                zoom: true,
            })),
            true,
        );
    } else {
        const kids = await children(target.id);
        layer.showBoundaries(
            kids.map((region) => ({
                id: region.id,
                name: region.name,
                boundary: region.boundary,
                item: statusById.get(region.id),
            })),
            true,
        );
    }
    // 하위 단위로 내려가도 지역 경계가 남도록, 지금 지역(동이면 이웃 동도)의 윤곽선을 함께 그린다
    // 동이면 같은 상위의 이웃 동도 윤곽선으로 보여 주려고 목록을 구한다
    const parent = path.length >= 2 ? path[path.length - 2] : null;
    const siblings =
        isDong && parent
            ? (await children(parent.id)).filter((region) => region.id !== target.id)
            : [];
    if (target !== path[path.length - 1]) {
        return; // 이웃 동 목록을 읽는 동안 지역이 바뀌었다(새 지도에 옛 윤곽선을 그리지 않는다)
    }
    // 윤곽선을 그리고 핀·도구를 갱신한다
    layer.showOutlines(target, siblings);
    // 구역 등록은 동 단계에서만 가능하다
    zoneRegister?.setAvailable(isDong);
    layer.showPins(zoneItems);
    // 이동 흐름(FNC-C-010): 켜져 있으면 구역 중심점으로 화살표를 다시 그린다
    flowTool.refresh(zoneItems);
    // 관리자 앱은 캠 목록도 갱신한다
    if (isAdmin) {
        await loadCameras();
    }
    // 지도 영역 안에 있는 구역만 캠 패널에 보이도록 갱신한다
    updateVisibleZones();
}

// 이 지역의 캠 목록을 읽어 캠 패널(deck)과 열려 있는 실시간 보기 창에 반영한다
async function loadCameras() {
    const target = path[path.length - 1];
    const cameras = await get('/api/v1/manage/cameras', { regionId: target.id });
    deck.setCameras(cameras);
    // 열려 있는 실시간 보기 창의 '현재 통계'도 새 분 통계로 바꾼다
    updateLiveView(cameras);
    updateVisibleZones();
}

// 구역 핀: 그 구역의 캠이 가동 중이면 실시간 보기 창을 연다
// 핀을 눌렀을 때: 캠이 없으면 등록 창, 가동 중이 아니면 안내, 가동 중이면 실시간 보기를 연다
function openLiveForZone(zoneId) {
    if (isMapBusy()) {
        return;
    }
    const camera = deck?.cameraByZone(zoneId);
    if (!camera) {
        // 캠 관리로 이동하지 않고 대시보드 위에서 그 구역을 고른 캠 등록 창을 연다(SCR-A-007)
        cameraForm.openRegister(zoneId);
    } else if (camera.status !== 'RUNNING') {
        showToast('가동 중인 캠이 아니라 실시간 영상을 볼 수 없습니다.');
    } else {
        openLiveView(camera);
    }
}

// 지도에 보이는 구역 id를 캠 패널에 알려 준다
function updateVisibleZones() {
    if (deck && layer) {
        deck.setVisibleZones(layer.visibleZoneIds());
    }
}

// ---- 패널 ------------------------------------------------------------------------------------

// 구역 목록 탭: 누른 탭의 버튼만 강조하고 목록을 다시 그린다
function initZoneTabs() {
    const buttons = document.querySelectorAll('[data-zone-tab]');
    buttons.forEach((button) => {
        button.addEventListener('click', () => {
            zoneTab = button.dataset.zoneTab;
            buttons.forEach((other) => {
                const on = other === button;
                other.classList.toggle('primary', on);
                other.classList.toggle('secondary', !on);
            });
            renderZonePanel(zoneItems, zoneTab, levels);
        });
    });
}

// 접으면 지도가 넓어지고 왼쪽 끝에 '통계' 버튼만 남는다
// 통계 패널 접기·펴기. 패널이 접히면 지도가 넓어지므로 지도 크기를 다시 계산한다
function initPanelToggle() {
    const panel = document.getElementById('stat-panel');
    const open = document.getElementById('panel-open');
    const setCollapsed = (collapsed) => {
        panel.hidden = collapsed;
        open.hidden = !collapsed;
        layer?.refreshSize();
    };
    document.getElementById('panel-collapse').addEventListener('click', () => setCollapsed(true));
    open.addEventListener('click', () => setCollapsed(false));
}
