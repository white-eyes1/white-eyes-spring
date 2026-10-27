// 통계 메인 지도(SCR-C-003, FNC-C-008·009): 대상 지역에 맞춘 지도, 하위 지역 경계·구역 핀을 혼잡 단계 색으로 그린다.
// Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3). 서버 값은 textContent로만 넣는다.
import { createMap, levelColor } from './map-view.js';
import { formatDecimal, formatNumber } from '../common/format.js';

const FALLBACK_ZOOM = 12;
const OFF_PAINT = '#4a5768';
// 핀 아이콘(지름 30px) 위쪽에서 팝업 꼬리가 시작하도록 올린다
const POPUP_OFFSET = [0, -18];
// 고정 문자열이라 innerHTML에 넣어도 안전하다(서버 값이 아님)
const PIN_ICON =
    '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">' +
    '<path d="M3 7h13v10H3zM16 10l5-3v10l-5-3"></path></svg>';

/**
 * 대상 지역 전체가 보이도록 지도를 만든다. map-view의 '초기화' 버튼이 처음 시야로 돌아가므로,
 * 지역이 바뀔 때마다 그 지역에 맞춰 새로 만든다.
 * @param {HTMLElement} element 지도 칸
 * @param {{centerLat, centerLng, boundary}} region regions API 항목
 * @param {object|null} previous 이전 지도(있으면 없앤다)
 */
export function createRegionMap(element, region, previous) {
    previous?.remove();
    const center = [Number(region.centerLat), Number(region.centerLng)];
    const probe = createMap(element, { center, zoom: FALLBACK_ZOOM });
    const bounds = region.boundary ? L.geoJSON(region.boundary).getBounds() : null;
    const fitted = bounds?.isValid() === true;
    const zoom = fitted ? probe.getBoundsZoom(bounds) : FALLBACK_ZOOM;
    const viewCenter = fitted ? bounds.getCenter() : L.latLng(center);
    probe.remove();
    return createMap(element, { center: [viewCenter.lat, viewCenter.lng], zoom });
}

/**
 * 지도 위에 올리는 경계·핀 층.
 * @param map createRegionMap의 지도
 * @param {{onSelectRegion: (id: number) => void, onViewChange: () => void, onPinClick?: (zoneId: number) => void, levels?: object[]}} handlers
 *   onPinClick: 구역 핀을 눌렀을 때(관리자 앱은 실시간 보기 창). 없으면 구역 정보 팝업을 연다(사용자 앱, FNC-C-009)
 *   levels: GET /meta/levels. 팝업의 혼잡 단계 이름에 쓴다
 */
export function createStatusLayer(map, { onSelectRegion, onViewChange, onPinClick, levels = [] }) {
    const boundaries = L.layerGroup().addTo(map);
    const pins = L.layerGroup().addTo(map);
    let zonePoints = [];
    // 열려 있는 팝업의 구역. 새 1분 통계로 핀을 다시 그려도 같은 팝업을 다시 연다
    let openPopupZoneId = null;
    // 경계 층과 그 단계 색 모양. 히트맵(FNC-C-011)이 켜져 있으면 paint로 덮어 칠한다
    let boundaryLayers = [];
    let paint = null;
    map.on('moveend', onViewChange);

    function applyPaint() {
        boundaryLayers.forEach(({ id, name, layer, style, item }) => {
            const painted = paint?.get(id);
            if (paint && painted) {
                layer.setStyle({ color: painted.color, weight: 1.5, fillColor: painted.color, fillOpacity: painted.fillOpacity });
                layer.setTooltipContent(textTooltip(name, painted.label));
            } else if (paint) {
                // 히트맵 값이 없는 칸은 회색(FNC-C-011 예외 상황)
                layer.setStyle({ color: OFF_PAINT, weight: 1, fillColor: OFF_PAINT, fillOpacity: 0.12 });
                layer.setTooltipContent(textTooltip(name, '데이터 없음'));
            } else {
                layer.setStyle(style);
                layer.setTooltipContent(tooltip(name, item));
            }
        });
    }

    return {
        /** 경계를 단계 색으로 칠한다. 통계가 없으면 회색. clickable이면 눌러서 그 지역으로 이동. */
        showBoundaries(units, clickable) {
            boundaries.clearLayers();
            boundaryLayers = [];
            units.forEach(({ id, name, boundary, item }) => {
                if (!boundary) {
                    return;
                }
                const color = levelColor(item?.level);
                const layer = L.geoJSON(boundary, {
                    style: {
                        color,
                        weight: item?.level ? 2 : 1.5,
                        fillColor: color,
                        fillOpacity: item?.level ? 0.22 : 0.12,
                    },
                });
                layer.bindTooltip(tooltip(name, item), { sticky: true });
                if (clickable) {
                    layer.on('click', () => onSelectRegion(id));
                }
                boundaries.addLayer(layer);
                boundaryLayers.push({ id, name, layer, style: layer.options.style, item });
            });
            applyPaint();
        },

        /**
         * 경계를 혼잡 단계 색 대신 주어진 색으로 칠한다(히트맵, FNC-C-011). null이면 단계 색으로 되돌린다.
         * 경계를 다시 그려도(새 1분 통계) 끌 때까지 이 색을 유지한다.
         * @param {Map<number, {color: string, fillOpacity: number, label: string}>|null} next 단위 id → 칠할 모양
         */
        paintBoundaries(next) {
            paint = next;
            applyPaint();
        },

        /** 구역 핀: 중심점에 단계 색, 진행 중 이벤트가 있으면 오늘 이벤트 수 배지. */
        showPins(items) {
            const reopenZoneId = openPopupZoneId;
            pins.clearLayers();
            zonePoints = items
                .filter((item) => item.centerLat !== null && item.centerLat !== undefined)
                .map((item) => ({ item, latLng: L.latLng(Number(item.centerLat), Number(item.centerLng)) }));
            zonePoints.forEach(({ item, latLng }) => {
                const marker = L.marker(latLng, { icon: pinIcon(item) });
                marker.bindTooltip(tooltip(item.name, item));
                if (onPinClick) {
                    marker.on('click', () => onPinClick(item.id));
                } else {
                    marker.bindPopup(zonePopup(item, levels), { offset: POPUP_OFFSET });
                    marker.on('popupopen', () => {
                        openPopupZoneId = item.id;
                    });
                    marker.on('popupclose', () => {
                        openPopupZoneId = null;
                    });
                }
                pins.addLayer(marker);
                if (!onPinClick && item.id === reopenZoneId) {
                    marker.openPopup();
                }
            });
        },

        /** 지금 지도 화면 안에 핀이 있는 구역 id (캠 패널이 '지도 화면 안 캠'을 거를 때 쓴다) */
        visibleZoneIds() {
            const bounds = map.getBounds();
            return new Set(zonePoints.filter(({ latLng }) => bounds.contains(latLng)).map(({ item }) => item.id));
        },

        /** 통계 패널을 접고 펼치거나 캠 패널 높이가 바뀌어 지도 칸 크기가 달라진 뒤에 부른다 */
        refreshSize() {
            map.invalidateSize();
        },
    };
}

function pinIcon(item) {
    const pin = document.createElement('span');
    pin.className = 'pin ' + (item.level ? 'l' + item.level : 'off');
    pin.style.background = levelColor(item.level);
    pin.innerHTML = PIN_ICON;
    if (item.ongoingEvent) {
        const badge = document.createElement('b');
        // 어제 시작해 계속 열린 이벤트만 있으면 오늘 이벤트 수가 0이라, 숫자 없이 배지만 보인다
        if (item.todayEventCount > 0) {
            badge.textContent = formatNumber(item.todayEventCount);
        }
        pin.append(badge);
    }
    return L.divIcon({ html: pin, className: 'zone-pin', iconSize: [0, 0] });
}

// 구역 정보 팝업: 이름, 현재 인원, 밀집도, 혼잡 단계 (통계가 없으면 '데이터 없음')
function zonePopup(item, levels) {
    const box = document.createElement('div');
    box.className = 'zone-popup';
    const title = document.createElement('b');
    title.textContent = item.name;
    box.append(title);
    if (!item.level) {
        const empty = document.createElement('div');
        empty.textContent = '데이터 없음';
        box.append(empty);
        return box;
    }
    const found = levels.find((candidate) => candidate.level === item.level);
    const chip = document.createElement('span');
    chip.className = 'chip l' + item.level;
    chip.textContent = found ? found.name : '단계 ' + item.level;
    box.append(
        popupRow('현재 인원', document.createTextNode(`${formatNumber(Math.round(Number(item.countAvg)))}명`)),
        popupRow('밀집도', document.createTextNode(`${formatDecimal(item.density, 2)}명/㎡`)),
        popupRow('혼잡 단계', chip),
    );
    return box;
}

function popupRow(label, value) {
    const row = document.createElement('div');
    const name = document.createElement('span');
    name.textContent = label;
    row.append(name, value);
    return row;
}

function textTooltip(name, text) {
    const box = document.createElement('div');
    const title = document.createElement('b');
    title.textContent = name;
    const body = document.createElement('div');
    body.textContent = text;
    box.append(title, body);
    return box;
}

// 이름 + (구역이면) 구·동 + 인원·밀집도 (통계가 없으면 '데이터 없음')
function tooltip(name, item) {
    const box = document.createElement('div');
    const title = document.createElement('b');
    title.textContent = name;
    box.append(title);
    if (item?.regionPath) {
        const path = document.createElement('div');
        path.textContent = item.regionPath;
        box.append(path);
    }
    const body = document.createElement('div');
    body.textContent = item?.level
        ? `${formatNumber(Math.round(Number(item.countAvg)))}명 · ${formatDecimal(item.density, 2)}명/㎡`
        : '데이터 없음';
    box.append(body);
    return box;
}
