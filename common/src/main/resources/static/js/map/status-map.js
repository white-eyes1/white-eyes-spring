// 통계 메인 지도(SCR-C-003, FNC-C-008·009): 대상 지역에 맞춘 지도, 하위 지역 경계·구역 핀을 혼잡 단계 색으로 그린다.
// Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3). 서버 값은 textContent로만 넣는다.
import { createMap, levelColor } from './map-view.js';
import { formatDecimal, formatNumber } from '../common/format.js';

const FALLBACK_ZOOM = 12;
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
 * @param {{onSelectRegion: (id: number) => void, onViewChange: () => void, onPinClick?: (zoneId: number) => void}} handlers
 *   onPinClick: 구역 핀을 눌렀을 때(관리자 앱은 실시간 보기 창). 없으면 눌러도 아무 일도 하지 않는다
 */
export function createStatusLayer(map, { onSelectRegion, onViewChange, onPinClick }) {
    const boundaries = L.layerGroup().addTo(map);
    const pins = L.layerGroup().addTo(map);
    let zonePoints = [];
    map.on('moveend', onViewChange);

    return {
        /** 경계를 단계 색으로 칠한다. 통계가 없으면 회색. clickable이면 눌러서 그 지역으로 이동. */
        showBoundaries(units, clickable) {
            boundaries.clearLayers();
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
            });
        },

        /** 구역 핀: 중심점에 단계 색, 진행 중 이벤트가 있으면 배지. */
        showPins(items) {
            pins.clearLayers();
            zonePoints = items
                .filter((item) => item.centerLat !== null && item.centerLat !== undefined)
                .map((item) => ({ item, latLng: L.latLng(Number(item.centerLat), Number(item.centerLng)) }));
            zonePoints.forEach(({ item, latLng }) => {
                const marker = L.marker(latLng, { icon: pinIcon(item) });
                marker.bindTooltip(tooltip(item.name, item));
                if (onPinClick) {
                    marker.on('click', () => onPinClick(item.id));
                }
                pins.addLayer(marker);
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
        badge.textContent = '1';
        pin.append(badge);
    }
    return L.divIcon({ html: pin, className: 'zone-pin', iconSize: [0, 0] });
}

// 이름 + 인원·밀집도 (통계가 없으면 '데이터 없음')
function tooltip(name, item) {
    const box = document.createElement('div');
    const title = document.createElement('b');
    title.textContent = name;
    const body = document.createElement('div');
    body.textContent = item?.level
        ? `${formatNumber(Math.round(Number(item.countAvg)))}명 · ${formatDecimal(item.density, 2)}명/㎡`
        : '데이터 없음';
    box.append(title, body);
    return box;
}
