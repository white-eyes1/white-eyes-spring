// 통계 메인(대시보드) 진입 파일 SCR-C-003: 지역 필터(FNC-C-008), 통계 패널(FNC-C-009·016·025), 지도, 캠 패널(관리자 앱).
// 지역은 주소 쿼리 regionId로 유지한다. 값은 60초마다 다시 읽는다(통계 SSE는 2단계).
import { get } from '../common/api.js';
import { createRegionMap, createStatusLayer } from '../map/status-map.js';
import { createDeck } from './stats-main/deck.js';
import {
    renderGraphLink,
    renderLevelRules,
    renderMapLegend,
    renderSummary,
    renderTarget,
    renderZonePanel,
} from './stats-main/panel.js';

const REFRESH_MS = 60_000;
const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];
const EMD = 'EMD';

const isAdmin = document.getElementById('dash').dataset.admin === 'true';
const selects = ['filter-sido', 'filter-sigungu', 'filter-emd'].map((id) => document.getElementById(id));

let levels = [];
let path = []; // 시·도부터 고른 지역 목록. 마지막이 통계 대상
let map = null;
let layer = null;
let deck = null;
let zoneItems = [];
let zoneTab = 'count';
let renderedRegionId = null;
const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록

start();

async function start() {
    levels = await get('/api/v1/meta/levels');
    renderMapLegend(levels);
    renderLevelRules(levels);
    initPanelToggle();
    initZoneTabs();
    window.addEventListener('popstate', () => selectFromUrl());
    if (isAdmin) {
        deck = createDeck({
            levels,
            onReload: loadCameras,
            onResize: () => {
                layer?.refreshSize();
                updateVisibleZones();
            },
        });
    }
    await selectFromUrl();
    window.setInterval(refresh, REFRESH_MS);
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

// ---- 값 읽기·그리기 --------------------------------------------------------------------------

async function loadRegion() {
    const target = path[path.length - 1];
    renderGraphLink(target.id);
    const resetView = renderedRegionId !== target.id;
    if (resetView) {
        map = createRegionMap(document.getElementById('dash-map'), target, map);
        layer = createStatusLayer(map, {
            onSelectRegion: selectChildRegion,
            onViewChange: updateVisibleZones,
        });
        renderedRegionId = target.id;
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

async function refresh() {
    const target = path[path.length - 1];
    const isDong = target.level === EMD;
    const [summary, zones, child, zoneShapes] = await Promise.all([
        get('/api/v1/dashboard/summary', { targetType: 'REGION', targetId: target.id }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'ZONE' }),
        get('/api/v1/map/status', { regionId: target.id, unit: 'CHILD' }),
        isDong ? get('/api/v1/zones', { dongId: target.id }) : Promise.resolve([]),
    ]);
    if (target !== path[path.length - 1]) {
        return; // 읽는 동안 지역이 바뀌었다
    }
    zoneItems = zones.items;
    renderTarget(target.name, summary.cameraCount);
    renderSummary(summary, levels);
    renderZonePanel(zoneItems, zoneTab, levels);

    const statusById = new Map(child.items.map((item) => [item.id, item]));
    if (isDong) {
        // 동이면 하위 단위가 구역이라 구역 경계를 칠한다
        layer.showBoundaries(
            zoneShapes.map((zone) => ({ id: zone.id, name: zone.name, boundary: zone.boundary, item: statusById.get(zone.id) })),
            false,
        );
    } else {
        const kids = await children(target.id);
        layer.showBoundaries(
            kids.map((region) => ({ id: region.id, name: region.name, boundary: region.boundary, item: statusById.get(region.id) })),
            true,
        );
    }
    layer.showPins(zoneItems);
    if (isAdmin) {
        await loadCameras();
    }
    updateVisibleZones();
}

async function loadCameras() {
    const target = path[path.length - 1];
    deck.setCameras(await get('/api/v1/manage/cameras', { regionId: target.id }));
    updateVisibleZones();
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
