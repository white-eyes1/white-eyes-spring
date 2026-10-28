// 통계 메인 지도(SCR-C-003, FNC-C-008·009): 대상 지역에 맞춘 지도, 하위 지역 경계·구역 핀을 혼잡 단계 색으로 그린다.
// Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3). 서버 값은 textContent로만 넣는다.
import { createMap, levelColor } from './map-view.js';
import { formatDecimal, formatNumber } from '../common/format.js';

const FALLBACK_ZOOM = 12;
const OFF_PAINT = '#4a5768';
// 하위 단위로 내려가도 남기는 윤곽선: 지금 보는 지역은 실선, 같은 단계의 이웃 지역은 옅은 점선
// 지금 보는 지역은 굵은 실선과 옅은 채움으로 강조한다(지역 밖을 어둡게 덮지는 않는다)
const OUTLINE_TARGET = { color: '#1f3a5f', weight: 3.5, fill: true, fillColor: '#4f8cff', fillOpacity: 0.07 };
const OUTLINE_SIBLING = { color: '#4a5d78', weight: 2, dashArray: '6 4', fill: false };
// 마우스를 올렸을 때: 칠 진하기를 이만큼 올리되 이 값을 넘기지 않는다(지도가 가려지지 않게)
const HOVER_FILL_BOOST = 0.2;
const HOVER_MAX_FILL = 0.6;
const OUTLINE_HOVER_FILL = 0.18;
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
 * @param {{onSelectRegion: (id: number) => void, onSelectSibling?: (id: number) => void, onZoneMenu?: (zoneId: number, event: MouseEvent) => void, onEmptyMenu?: (latLng: object, event: MouseEvent) => void, isBusy?: () => boolean, onViewStart?: () => void, onViewChange: () => void, onPinClick?: (zoneId: number) => void, levels?: object[]}} handlers
 *   onSelectSibling: 동 단계에서 이웃 동 윤곽선을 눌렀을 때(그 동으로 이동)
 *   onZoneMenu·onEmptyMenu: 구역 위·지금 지역 안의 빈 곳을 우클릭했을 때(관리자 앱의 우클릭 메뉴). 없으면 우클릭을 가로채지 않는다
 *   isBusy: true인 동안(구역을 그리거나 고치는 중)에는 지도의 클릭·우클릭을 무시한다
 *   onViewStart: 지도를 끌거나 확대하기 시작할 때(열려 있는 메뉴를 닫는다)
 *   onPinClick: 구역 핀을 눌렀을 때(관리자 앱은 실시간 보기 창). 없으면 구역 정보 팝업을 연다(사용자 앱, FNC-C-009)
 *   levels: GET /meta/levels. 팝업의 혼잡 단계 이름에 쓴다
 */
export function createStatusLayer(map, { onSelectRegion, onSelectSibling, onZoneMenu, onEmptyMenu, isBusy, onViewStart, onViewChange, onPinClick, levels = [] }) {
    // 윤곽선은 단계 색 경계 아래에 깐다. 지금 지역은 눌러도 반응하지 않고, 이웃 지역은 누르면 이동한다
    const outlines = L.layerGroup().addTo(map);
    const boundaries = L.layerGroup().addTo(map);
    const pins = L.layerGroup().addTo(map);
    let zonePoints = [];
    // 열려 있는 팝업의 구역. 새 1분 통계로 핀을 다시 그려도 같은 팝업을 다시 연다
    let openPopupZoneId = null;
    // 경계 층과 그 단계 색 모양. 히트맵(FNC-C-011)이 켜져 있으면 paint로 덮어 칠한다
    let boundaryLayers = [];
    let paint = null;
    // 지금 보는 지역의 바깥 고리들(showOutlines가 채운다). 빈 곳 우클릭이 이 안인지 가린다
    let regionRings = [];
    map.on('moveend', onViewChange);
    ['movestart', 'zoomstart'].forEach((name) => map.on(name, () => onViewStart?.()));
    // 우클릭 메뉴(관리자 앱): 구역 위는 onZoneMenu, 지금 지역 안의 빈 곳은 onEmptyMenu. 메뉴가 없는 앱에서는 브라우저 기본 메뉴를 그대로 둔다
    if (onZoneMenu || onEmptyMenu) {
        map.on('contextmenu', (event) => {
            L.DomEvent.preventDefault(event.originalEvent);
            if (!isBusy?.() && onEmptyMenu && regionRings.some((ring) => insideRing(event.latlng, ring))) {
                onEmptyMenu(event.latlng, event.originalEvent);
            }
        });
    }

    function addOutline(boundary, style, onClick, name) {
        if (!boundary) {
            return;
        }
        // 누를 수 있는 윤곽선은 안쪽도 눌리도록 투명한 채움을 둔다(채움이 없으면 선만 눌린다)
        const layer = L.geoJSON(boundary, onClick ? { style: { ...style, fill: true, fillOpacity: 0 } } : { style, interactive: false });
        if (onClick) {
            layer.on('click', () => {
                if (!isBusy?.()) {
                    onClick();
                }
            });
            // 올리면 영역이 살짝 칠해지고 이름이 뜬다(누를 수 있다는 표시)
            layer.bindTooltip(textTooltip(name, '눌러서 이동'), { sticky: true });
            layer.on('mouseover', () => layer.setStyle({ weight: style.weight + 1.5, fillOpacity: OUTLINE_HOVER_FILL }));
            layer.on('mouseout', () => layer.setStyle({ weight: style.weight, fillOpacity: 0 }));
        }
        outlines.addLayer(layer);
    }

    // 지도를 그 영역 크기에 맞춘다(구역 줌인)
    function zoomTo(layer) {
        map.fitBounds(layer.getBounds(), { padding: [60, 60], maxZoom: 18 });
    }

    // 지금 칠할 모양: 히트맵이 켜져 있으면 히트맵 색, 아니면 단계 색. 마우스를 올린 영역은 테두리를 굵게, 채움을 진하게 한다
    function styleOf(entry) {
        const painted = paint?.get(entry.id);
        let style;
        if (paint && painted) {
            style = { color: painted.color, weight: 1.5, fillColor: painted.color, fillOpacity: painted.fillOpacity };
        } else if (paint) {
            // 히트맵 값이 없는 칸은 회색(FNC-C-011 예외 상황)
            style = { color: OFF_PAINT, weight: 1, fillColor: OFF_PAINT, fillOpacity: 0.12 };
        } else {
            style = entry.style;
        }
        return entry.hover
            ? { ...style, weight: style.weight + 1.5, fillOpacity: Math.min(HOVER_MAX_FILL, style.fillOpacity + HOVER_FILL_BOOST) }
            : style;
    }

    function applyPaint() {
        boundaryLayers.forEach((entry) => {
            const { id, name, layer, item } = entry;
            const painted = paint?.get(id);
            layer.setStyle(styleOf(entry));
            if (paint && painted) {
                layer.setTooltipContent(textTooltip(name, painted.label));
            } else if (paint) {
                layer.setTooltipContent(textTooltip(name, '데이터 없음'));
            } else {
                layer.setTooltipContent(tooltip(name, item));
            }
        });
    }

    return {
        /**
         * 경계를 단계 색으로 칠한다. 통계가 없으면 회색. clickable이면 눌러서 줌인한다:
         * 지역은 그 지역으로 이동(onSelectRegion), unit.zoom이 true인 구역은 지도를 그 구역에 맞춘다.
         */
        showBoundaries(units, clickable) {
            boundaries.clearLayers();
            boundaryLayers = [];
            units.forEach(({ id, name, boundary, item, zoom }) => {
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
                    layer.on('click', () => {
                        if (!isBusy?.()) {
                            return zoom ? zoomTo(layer) : onSelectRegion(id);
                        }
                    });
                }
                // 구역(zoom 표시가 있는 단위)을 우클릭하면 구역 메뉴. 지도의 우클릭(빈 곳 메뉴)으로 번지지 않게 끊는다
                if (zoom && onZoneMenu) {
                    layer.on('contextmenu', (event) => {
                        L.DomEvent.stop(event);
                        if (!isBusy?.()) {
                            onZoneMenu(id, event.originalEvent);
                        }
                    });
                }
                boundaries.addLayer(layer);
                const entry = { id, name, layer, style: layer.options.style, item, hover: false };
                layer.on('mouseover', () => {
                    entry.hover = true;
                    layer.setStyle(styleOf(entry));
                });
                layer.on('mouseout', () => {
                    entry.hover = false;
                    layer.setStyle(styleOf(entry));
                });
                boundaryLayers.push(entry);
            });
            applyPaint();
        },

        /**
         * 지금 보는 지역(target)과 이웃 지역(siblings)의 윤곽선을 그린다. 하위 단위 경계(showBoundaries)와 별개로 남아,
         * 동까지 내려가도 지역 경계가 지도에서 사라지지 않는다.
         * @param {{boundary: object|null}} target 지금 보는 지역(regions API 항목)
         * @param {{id: number, name: string, boundary: object|null}[]} siblings 같은 단계의 이웃 지역(누르면 onSelectSibling)
         */
        showOutlines(target, siblings) {
            outlines.clearLayers();
            regionRings = target.boundary ? outerRings(target.boundary) : [];
            siblings.forEach(({ id, name, boundary }) =>
                addOutline(boundary, OUTLINE_SIBLING, onSelectSibling ? () => onSelectSibling(id) : undefined, name),
            );
            addOutline(target.boundary, OUTLINE_TARGET);
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

// GeoJSON Polygon·MultiPolygon의 바깥 고리들(구멍은 뺀다)
function outerRings(boundary) {
    const rings = [];
    L.geoJSON(boundary).eachLayer((layer) => {
        const latLngs = layer.getLatLngs();
        // Polygon은 [바깥 고리, 구멍…], MultiPolygon은 그 목록의 목록이다
        if (Array.isArray(latLngs[0]) && Array.isArray(latLngs[0][0])) {
            latLngs.forEach((polygon) => rings.push(polygon[0]));
        } else if (Array.isArray(latLngs[0])) {
            rings.push(latLngs[0]);
        }
    });
    return rings;
}

// 점이 고리 안인지(반직선이 변과 만나는 횟수가 홀수면 안)
function insideRing(point, ring) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const a = ring[i];
        const b = ring[j];
        if (a.lat > point.lat !== b.lat > point.lat && point.lng < ((b.lng - a.lng) * (point.lat - a.lat)) / (b.lat - a.lat) + a.lng) {
            inside = !inside;
        }
    }
    return inside;
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
