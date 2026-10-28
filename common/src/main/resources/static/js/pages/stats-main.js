// 통계 메인(대시보드) 진입 파일 SCR-C-003: 지역 필터(FNC-C-008), 통계 패널(FNC-C-009·016·020·025·030), 지도, 캠 패널(관리자 앱).
// 지역은 주소 쿼리 regionId로 유지한다. 새 1분 통계는 통계 SSE(FNC-C-022)가 알려 주면 화면 값을 새로 읽고,
// 연결이 끊겨 있거나 한참 소식이 없으면 60초마다 읽는다.
import { get, url } from '../common/api.js';
import { initExportModal, openExportModal } from '../export/export-modal.js';
import { initLiveView, openLiveView, updateLiveView } from '../live/live-view.js';
import { showToast } from '../common/toast.js';
import { createRegionMap, createStatusLayer } from '../map/status-map.js';
import { createAbnormalPanel } from './stats-main/abnormal.js';
import { createAuxMetricsPanel } from './stats-main/aux-metrics.js';
import { createDeck } from './stats-main/deck.js';
import { createHourlyLevelsPanel } from './stats-main/hourly-levels.js';
import { createVisitPatternPanel } from './stats-main/visit-pattern.js';
import { createPeakHoursPanel } from './stats-main/peak-hours.js';
import { createMapMenu } from './stats-main/map-menu.js';
import { createTrendPanel } from './stats-main/trend.js';
import { createFlowTool } from './stats-main/flow-tool.js';
import { createHeatmapTool } from './stats-main/heatmap-tool.js';
import {
    renderGraphLink,
    renderLevelRules,
    renderMapLegend,
    renderSummary,
    renderTarget,
    renderZonePanel,
} from './stats-main/panel.js';

const REFRESH_MS = 60_000;
// 연결돼 있어도 이 시간 동안 새로 읽은 적이 없으면 스트림이 멈춘 것으로 보고 직접 읽는다
const STALE_MS = 150_000;
// 새 분 소식이 연달아 와도(여러 구역 저장) 한 번만 읽는다
const STREAM_DEBOUNCE_MS = 300;
const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];
const EMD = 'EMD';

const isAdmin = document.getElementById('dash').dataset.admin === 'true';
const selects = ['filter-sido', 'filter-sigungu', 'filter-emd'].map((id) => document.getElementById(id));

let levels = [];
let path = []; // 시·도부터 고른 지역 목록. 마지막이 통계 대상
let map = null;
let layer = null;
let deck = null;
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
// 동 단계에서 지도에 그려진 구역(GET /zones). 우클릭 메뉴가 구역 이름·면적을 쓴다
let zoneShapeList = [];
let auxPanel = null;
let zoneItems = [];
let zoneTab = 'count';
let renderedRegionId = null;
let stream = null;
let streamConnected = false;
let streamTimer = null;
let lastRefreshAt = 0;
const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록

start();

async function start() {
    levels = await get('/api/v1/meta/levels');
    auxPanel = createAuxMetricsPanel(levels);
    renderMapLegend(levels);
    renderLevelRules(levels);
    initPanelToggle();
    initZoneTabs();
    initExportModal();
    document.getElementById('export-open').addEventListener('click', openExport);
    window.addEventListener('popstate', () => selectFromUrl());
    if (isAdmin) {
        initLiveView();
        deck = createDeck({
            levels,
            onReload: loadCameras,
            onOpenLive: openLiveView,
            onResize: () => {
                layer?.refreshSize();
                updateVisibleZones();
            },
        });
    }
    await selectFromUrl();
    window.setInterval(pollIfNeeded, REFRESH_MS);
}

// ---- 실시간 통계 갱신 (FNC-C-022) -------------------------------------------------------------

// 지역이 바뀌면 그 지역으로 다시 연결한다. 끊기면 EventSource가 retry(5초)로 알아서 다시 연결한다
function connectStream(regionId) {
    stream?.close();
    streamConnected = false;
    const source = new EventSource(url(`/api/v1/stats/stream?regionId=${regionId}`));
    stream = source;
    source.addEventListener('open', () => setStreamConnected(true));
    source.addEventListener('error', () => setStreamConnected(false));
    source.addEventListener('stat', () => {
        // 이미 다른 지역으로 옮겨 닫힌 연결의 늦은 소식은 무시한다
        if (stream !== source) {
            return;
        }
        window.clearTimeout(streamTimer);
        streamTimer = window.setTimeout(refreshSafely, STREAM_DEBOUNCE_MS);
    });
}

function setStreamConnected(connected) {
    streamConnected = connected;
    document.getElementById('stream-status').hidden = connected;
}

function pollIfNeeded() {
    if (!streamConnected || Date.now() - lastRefreshAt > STALE_MS) {
        refreshSafely();
    }
}

async function refreshSafely() {
    try {
        await refresh();
    } catch {
        // 읽기에 실패해도 다음 소식이나 60초 확인에 다시 읽는다. 오류 토스트는 api.js가 띄운다
    }
}

// ---- 지역 필터 -------------------------------------------------------------------------------

async function children(parentId) {
    const key = parentId ?? 'root';
    if (!childrenCache.has(key)) {
        childrenCache.set(key, await get('/api/v1/regions', parentId === null ? undefined : { parentId }));
    }
    return childrenCache.get(key);
}

// 주소의 regionId로 시·구·동 경로를 찾는다. 상위 지역을 되묻는 API가 없어 위에서부터 한 단계씩 내려가며 찾는다
async function findPath(regionId) {
    let frontier = (await children(null)).map((region) => [region]);
    for (let depth = 0; depth < LEVEL_LABELS.length && frontier.length > 0; depth++) {
        const hit = frontier.find((candidate) => candidate[depth].id === regionId);
        if (hit) {
            return hit;
        }
        if (depth === LEVEL_LABELS.length - 1) {
            break;
        }
        const next = [];
        for (const candidate of frontier) {
            const kids = await children(candidate[depth].id);
            kids.forEach((kid) => next.push([...candidate, kid]));
        }
        frontier = next;
    }
    return null;
}

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

async function applyPath(newPath, pushUrl) {
    path = newPath;
    if (path.length === 0) {
        return;
    }
    const target = path[path.length - 1];
    if (pushUrl) {
        const url = new URL(window.location.href);
        url.searchParams.set('regionId', String(target.id));
        window.history.pushState(null, '', url);
    }
    await renderFilter();
    await loadRegion();
}

async function renderFilter() {
    for (let depth = 0; depth < selects.length; depth++) {
        const select = selects[depth];
        const options = depth === 0 ? await children(null) : path[depth - 1] ? await children(path[depth - 1].id) : [];
        select.replaceChildren(...optionList(options, depth));
        select.value = path[depth] ? String(path[depth].id) : '';
        select.disabled = options.length === 0 || (depth > 0 && !path[depth - 1]);
        select.onchange = () => onFilterChange(depth, select.value);
    }
}

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
async function onFilterChange(depth, value) {
    const base = path.slice(0, depth);
    if (value !== '') {
        const options = depth === 0 ? await children(null) : await children(path[depth - 1].id);
        base.push(options.find((region) => String(region.id) === value));
    }
    await applyPath(base, true);
}

// 엑셀 다운로드 창(SCR-C-006)에 현재 지역을 넘긴다. 단위·기간은 통계 그래프의 기본값(1h, 오늘)
function openExport() {
    const target = path[path.length - 1];
    if (!target) {
        return;
    }
    const today = new Date();
    const from = new Date(today.getFullYear(), today.getMonth(), today.getDate());
    const to = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    openExportModal({ region: { id: target.id, name: target.name }, interval: '1h', from, to });
}

// ---- 값 읽기·그리기 --------------------------------------------------------------------------

async function loadRegion() {
    const target = path[path.length - 1];
    renderGraphLink(target.id);
    const resetView = renderedRegionId !== target.id;
    if (resetView) {
        map = createRegionMap(document.getElementById('dash-map'), target, map);
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
        renderedRegionId = target.id;
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
    await refresh();
}

async function selectChildRegion(id) {
    const target = path[path.length - 1];
    const child = (await children(target.id)).find((region) => region.id === id);
    if (child) {
        await applyPath([...path, child], true);
    }
}

// ---- 지도 우클릭 메뉴(관리자 앱, 동 단계) -------------------------------------------------
// 항목은 기능마다 이슈를 나눠 더한다(구역 등록·수정·삭제, 캠 수정·삭제). 항목이 없으면 메뉴를 띄우지 않는다

// 구역을 그리거나 고치는 중이면 true
function isMapBusy() {
    return false;
}

function openZoneMenu(zoneId, event) {
    const zone = zoneShapeList.find((candidate) => candidate.id === zoneId);
    if (zone && path[path.length - 1].level === EMD) {
        // 메뉴가 떠 있는 동안 그 구역이 강조된 채로 남게 한다
        layer.holdZone(zoneId);
        mapMenu.open({ x: event.clientX, y: event.clientY, title: zone.name, items: buildZoneMenu(zone) });
    }
}

function openEmptyMenu(latLng, event) {
    const target = path[path.length - 1];
    if (target.level === EMD) {
        mapMenu.open({ x: event.clientX, y: event.clientY, title: target.name, items: buildEmptyMenu(latLng) });
    }
}

/** 구역 위 우클릭 메뉴 항목 */
function buildZoneMenu(zone) {
    const items = [];
    return items;
}

/** 지금 동 안의 빈 곳 우클릭 메뉴 항목 */
function buildEmptyMenu(latLng) {
    const items = [];
    return items;
}

// 동 단계에서 이웃 동 윤곽선을 누르면 그 동으로 이동한다
async function selectSiblingRegion(id) {
    const parent = path.length >= 2 ? path[path.length - 2] : null;
    const sibling = parent ? (await children(parent.id)).find((region) => region.id === id) : null;
    if (sibling) {
        await applyPath([...path.slice(0, -1), sibling], true);
    }
}

async function refresh() {
    const target = path[path.length - 1];
    const isDong = target.level === EMD;
    const [summary, zones, child, zoneShapes] = await Promise.all([
        get('/api/v1/dashboard/summary', { targetType: 'REGION', targetId: target.id }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'ZONE' }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'CHILD' }),
        isDong ? get('/api/v1/zones', { dongId: target.id }) : Promise.resolve([]),
    ]);
    // 읽기에 성공했을 때만 기록한다. 실패하면 다음 60초 확인이 바로 다시 읽는다
    lastRefreshAt = Date.now();
    if (target !== path[path.length - 1]) {
        return; // 읽는 동안 지역이 바뀌었다
    }
    zoneItems = zones.items;
    zoneShapeList = zoneShapes;
    renderTarget(target.name, summary.cameraCount);
    renderSummary(summary, levels);
    renderZonePanel(zoneItems, zoneTab, levels);
    // 보조 지표(FNC-C-030): 펼쳐 있는 영역만 시계열을 다시 읽는다. 읽기 실패는 영역 안에 문구로 보인다
    auxPanel.refresh(zoneItems);
    // 시간대별 혼잡 단계(FNC-C-018): 현재 시간 막대가 1분 통계로 바뀐다
    hourlyLevelsPanel.refresh();

    const statusById = new Map(child.items.map((item) => [item.id, item]));
    if (isDong) {
        // 동이면 하위 단위가 구역이라 구역 경계를 칠한다
        layer.showBoundaries(
            zoneShapes.map((zone) => ({ id: zone.id, name: zone.name, boundary: zone.boundary, item: statusById.get(zone.id), zoom: true })),
            true,
        );
    } else {
        const kids = await children(target.id);
        layer.showBoundaries(
            kids.map((region) => ({ id: region.id, name: region.name, boundary: region.boundary, item: statusById.get(region.id) })),
            true,
        );
    }
    // 하위 단위로 내려가도 지역 경계가 남도록, 지금 지역(동이면 이웃 동도)의 윤곽선을 함께 그린다
    const parent = path.length >= 2 ? path[path.length - 2] : null;
    const siblings = isDong && parent ? (await children(parent.id)).filter((region) => region.id !== target.id) : [];
    if (target !== path[path.length - 1]) {
        return; // 이웃 동 목록을 읽는 동안 지역이 바뀌었다(새 지도에 옛 윤곽선을 그리지 않는다)
    }
    layer.showOutlines(target, siblings);
    layer.showPins(zoneItems);
    // 이동 흐름(FNC-C-010): 켜져 있으면 구역 중심점으로 화살표를 다시 그린다
    flowTool.refresh(zoneItems);
    if (isAdmin) {
        await loadCameras();
    }
    updateVisibleZones();
}

async function loadCameras() {
    const target = path[path.length - 1];
    const cameras = await get('/api/v1/manage/cameras', { regionId: target.id });
    deck.setCameras(cameras);
    // 열려 있는 실시간 보기 창의 '현재 통계'도 새 분 통계로 바꾼다
    updateLiveView(cameras);
    updateVisibleZones();
}

// 구역 핀: 그 구역의 캠이 가동 중이면 실시간 보기 창을 연다
function openLiveForZone(zoneId) {
    const camera = deck?.cameraByZone(zoneId);
    if (!camera) {
        showToast('이 구역에는 캠이 없습니다.');
    } else if (camera.status !== 'RUNNING') {
        showToast('가동 중인 캠이 아니라 실시간 영상을 볼 수 없습니다.');
    } else {
        openLiveView(camera);
    }
}

function updateVisibleZones() {
    if (deck && layer) {
        deck.setVisibleZones(layer.visibleZoneIds());
    }
}

// ---- 패널 ------------------------------------------------------------------------------------

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
