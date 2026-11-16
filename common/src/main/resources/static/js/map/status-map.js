// 통계 메인 지도(SCR-C-003, FNC-C-008·009): 대상 지역에 맞춘 지도, 하위 지역 경계·구역 핀을 혼잡 단계 색으로 그린다.
// Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3). 서버 값은 textContent로만 넣는다.
import { createMap, levelColor } from './map-view.js';
import { formatDecimal, formatNumber } from '../common/format.js';
import { bindNowCount } from '../common/now-count.js';

// 지역 경계가 없을 때 쓰는 기본 확대 단계
const FALLBACK_ZOOM = 12;
// 히트맵 값이 없는 칸(데이터 없음)을 칠하는 회색
const OFF_PAINT = '#4a5768';
// 하위 단위로 내려가도 남기는 윤곽선: 지금 보는 지역은 실선, 같은 단계의 이웃 지역은 옅은 점선
// 지금 보는 지역은 굵은 실선과 옅은 채움으로 강조한다(지역 밖을 어둡게 덮지는 않는다)
// 꼭짓점을 고치는 구역의 모양(구역 수정, FNC-A-015)
const EDIT_STYLE = { color: '#76a7ff', weight: 3, fillColor: '#4f8cff', fillOpacity: 0.28 };
// 지금 보는 지역의 윤곽선 모양(굵은 실선 + 옅은 채움). 아래 OUTLINE_SIBLING은 이웃 지역의 옅은 점선
const OUTLINE_TARGET = {
    color: '#1f3a5f',
    weight: 3.5,
    fill: true,
    fillColor: '#4f8cff',
    fillOpacity: 0.07,
};
const OUTLINE_SIBLING = { color: '#4a5d78', weight: 2, dashArray: '6 4', fill: false };
// 마우스를 올렸을 때: 칠 진하기를 이만큼 올리되 이 값을 넘기지 않는다(지도가 가려지지 않게)
// 마우스를 올린 영역: 채움 진하기를 HOVER_FILL_BOOST만큼 올리되 HOVER_MAX_FILL을 넘기지 않는다. 윤곽선 호버는 OUTLINE_HOVER_FILL
const HOVER_FILL_BOOST = 0.2;
const HOVER_MAX_FILL = 0.6;
const OUTLINE_HOVER_FILL = 0.18;
// 핀 아이콘(지름 30px) 위쪽에서 팝업 꼬리가 시작하도록 올린다
// 팝업 꼬리의 위치 보정(x, y px)
const POPUP_OFFSET = [0, -18];
// 고정 문자열이라 innerHTML에 넣어도 안전하다(서버 값이 아님)
// 핀 안에 넣는 카메라 아이콘 SVG
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
    // 이전 지도가 있으면 없앤다(같은 칸에 지도를 두 번 만들 수 없다)
    previous?.remove();
    // 서버가 준 중심점(문자열일 수 있어 숫자로 바꾼다)
    const center = [Number(region.centerLat), Number(region.centerLng)];
    // 확대 단계를 구하려고 임시 지도를 먼저 만든다. 지도 크기에 맞는 확대 단계는 지도가 있어야 계산된다
    const probe = createMap(element, { center, zoom: FALLBACK_ZOOM });
    // 경계가 있으면 그 경계의 사각형 범위
    const bounds = region.boundary ? L.geoJSON(region.boundary).getBounds() : null;
    // 경계가 없거나 비어 있으면 fitted가 false
    const fitted = bounds?.isValid() === true;
    // 경계 전체가 보이는 확대 단계. 경계가 없으면 기본 단계
    const zoom = fitted ? probe.getBoundsZoom(bounds) : FALLBACK_ZOOM;
    const viewCenter = fitted ? bounds.getCenter() : L.latLng(center);
    // 임시 지도는 버리고
    probe.remove();
    // 계산한 위치·확대 단계로 진짜 지도를 만든다(초기화 버튼이 이 값으로 돌아가게 하려는 것이다)
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
export function createStatusLayer(
    map,
    {
        onSelectRegion,
        onSelectSibling,
        onZoneMenu,
        onEmptyMenu,
        isBusy,
        onViewStart,
        onViewChange,
        onPinClick,
        levels = [],
    },
) {
    // 윤곽선은 단계 색 경계 아래에 깐다. 지금 지역은 눌러도 반응하지 않고, 이웃 지역은 누르면 이동한다
    // 지도 위 층: 윤곽선(맨 아래) < 경계(단계 색) < 핀(맨 위) 순서로 쌓는다
    const outlines = L.layerGroup().addTo(map);
    const boundaries = L.layerGroup().addTo(map);
    const pins = L.layerGroup().addTo(map);
    // 핀을 가진 구역 목록(visibleZoneIds에서 지도 안에 있는지 검사)
    let zonePoints = [];
    // 열려 있는 팝업의 구역. 새 1분 통계로 핀을 다시 그려도 같은 팝업을 다시 연다
    let openPopupZoneId = null;
    // 경계 층과 그 단계 색 모양. 히트맵(FNC-C-011)이 켜져 있으면 paint로 덮어 칠한다
    let boundaryLayers = [];
    let paint = null;
    // 지금 보는 지역의 바깥 고리들(showOutlines가 채운다). 빈 곳 우클릭이 이 안인지 가린다
    let regionRings = [];
    // 꼭짓점을 고치는 중인 구역 { entry, polygon, handler }
    let zoneEditing = null;
    // 지도를 움직이거나 확대·축소를 끝내면 화면이 보이는 구역을 다시 계산하게 알린다
    map.on('moveend', onViewChange);
    // 움직이기 시작하면 열려 있던 우클릭 메뉴를 닫게 알린다
    ['movestart', 'zoomstart'].forEach((name) => map.on(name, () => onViewStart?.()));
    // 우클릭 메뉴(관리자 앱): 구역 위는 onZoneMenu, 지금 지역 안의 빈 곳은 onEmptyMenu. 메뉴가 없는 앱에서는 브라우저 기본 메뉴를 그대로 둔다
    // 우클릭 메뉴가 있는 앱(관리자)에서만 지도 우클릭을 가로챈다
    if (onZoneMenu || onEmptyMenu) {
        map.on('contextmenu', (event) => {
            // 브라우저 기본 메뉴를 막는다
            L.DomEvent.preventDefault(event.originalEvent);
            // 그리거나 고치는 중이 아니고, 지금 지역 안(바깥 고리 안)의 빈 곳일 때만 빈 곳 메뉴를 연다
            if (
                !isBusy?.() &&
                onEmptyMenu &&
                regionRings.some((ring) => insideRing(event.latlng, ring))
            ) {
                onEmptyMenu(event.latlng, event.originalEvent);
            }
        });
    }

    // 윤곽선 하나를 윤곽선 층에 더한다. onClick이 있으면 눌러서 이동할 수 있는 이웃 지역
    function addOutline(boundary, style, onClick, name) {
        // 경계 자료가 없는 지역은 그리지 않는다
        if (!boundary) {
            return;
        }
        // 누를 수 있는 윤곽선은 안쪽도 눌리도록 투명한 채움을 둔다(채움이 없으면 선만 눌린다)
        const layer = L.geoJSON(
            boundary,
            onClick
                ? { style: { ...style, fill: true, fillOpacity: 0 } }
                : { style, interactive: false },
        );
        if (onClick) {
            layer.on('click', () => {
                // 구역을 그리거나 고치는 중에는 누름을 무시한다
                if (!isBusy?.()) {
                    onClick();
                }
            });
            // 올리면 영역이 살짝 칠해지고 이름이 뜬다(누를 수 있다는 표시)
            layer.bindTooltip(textTooltip(name, '눌러서 이동'), { sticky: true });
            // 올리면 선이 굵어지고 안쪽이 살짝 칠해지며, 벗어나면 되돌린다
            layer.on('mouseover', () =>
                layer.setStyle({ weight: style.weight + 1.5, fillOpacity: OUTLINE_HOVER_FILL }),
            );
            layer.on('mouseout', () => layer.setStyle({ weight: style.weight, fillOpacity: 0 }));
        }
        outlines.addLayer(layer);
    }

    // 지도를 그 영역 크기에 맞춘다(구역 줌인)
    // 영역에 맞춰 확대. maxZoom으로 너무 가까이 가지 않게 한다
    function zoomTo(layer) {
        map.fitBounds(layer.getBounds(), { padding: [60, 60], maxZoom: 18 });
    }

    // 지금 칠할 모양: 히트맵이 켜져 있으면 히트맵 색, 아니면 단계 색. 마우스를 올린 영역은 테두리를 굵게, 채움을 진하게 한다
    function styleOf(entry) {
        // 꼭짓점을 고치는 중인 구역은 항상 수정 색
        if (entry.editing) {
            return EDIT_STYLE;
        }
        // 히트맵(FNC-C-011) 값. 켜져 있으면 히트맵 색으로 덮어 칠한다
        const painted = paint?.get(entry.id);
        let style;
        if (paint && painted) {
            style = {
                color: painted.color,
                weight: 1.5,
                fillColor: painted.color,
                fillOpacity: painted.fillOpacity,
            };
        } else if (paint) {
            // 히트맵 값이 없는 칸은 회색(FNC-C-011 예외 상황)
            style = { color: OFF_PAINT, weight: 1, fillColor: OFF_PAINT, fillOpacity: 0.12 };
        } else {
            style = entry.style;
        }
        // 마우스를 올린 상태면 선을 굵게, 채움을 진하게(상한 있음)
        return entry.hover
            ? {
                  ...style,
                  weight: style.weight + 1.5,
                  fillOpacity: Math.min(HOVER_MAX_FILL, style.fillOpacity + HOVER_FILL_BOOST),
              }
            : style;
    }

    // 모든 경계의 색과 툴팁 내용을 지금 상태(히트맵 on/off)에 맞게 다시 적용한다
    function applyPaint() {
        boundaryLayers.forEach((entry) => {
            // entry: showBoundaries가 만든 경계 정보
            const { id, name, layer, item } = entry;
            const painted = paint?.get(id);
            layer.setStyle(styleOf(entry));
            if (paint && painted) {
                layer.setTooltipContent(textTooltip(name, painted.label));
            } else if (paint) {
                layer.setTooltipContent(textTooltip(name, '데이터 없음'));
            } else {
                // 툴팁은 함수로 넘겨 올릴 때마다 최신 현재 인원(5초 갱신)을 담는다
                layer.setTooltipContent(() => tooltip(name, item));
            }
        });
    }

    return {
        /**
         * 경계를 단계 색으로 칠한다. 통계가 없으면 회색. clickable이면 눌러서 줌인한다:
         * 지역은 그 지역으로 이동(onSelectRegion), unit.zoom이 true인 구역은 지도를 그 구역에 맞춘다.
         */
        showBoundaries(units, clickable) {
            // 편집 중이던 구역이 있으면 끝내고, 이전 경계를 모두 지운다
            this.stopZoneEdit();
            boundaries.clearLayers();
            boundaryLayers = [];
            // units: 지역 또는 구역 목록. item은 통계(없으면 회색), zoom이 true면 구역이다
            units.forEach(({ id, name, boundary, item, zoom }) => {
                // 경계 자료가 없으면 그릴 수 없다
                if (!boundary) {
                    return;
                }
                // 단계 색. 통계가 없으면 levelColor가 회색을 준다
                const color = levelColor(item?.level);
                const layer = L.geoJSON(boundary, {
                    style: {
                        color,
                        // 통계가 있으면 조금 굵고 진하게, 없으면 옅게
                        weight: item?.level ? 2 : 1.5,
                        fillColor: color,
                        fillOpacity: item?.level ? 0.22 : 0.12,
                    },
                });
                // 툴팁은 올릴 때마다 만든다(5초 현재 인원을 반영하려고)
                layer.bindTooltip(() => tooltip(name, item), { sticky: true });
                // 눌러서 이동할 수 있는 지도(clickable)에서만 클릭을 받는다
                if (clickable) {
                    layer.on('click', () => {
                        if (!isBusy?.()) {
                            // 구역(zoom)이면 지도를 그 구역에 맞추고, 지역이면 그 지역으로 이동한다
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
                // 경계 하나의 상태 표(호버·고정·편집 여부)를 보관해 색을 다시 칠할 때 쓴다
                const entry = {
                    id,
                    name,
                    layer,
                    style: layer.options.style,
                    item,
                    hover: false,
                    held: false,
                    editing: false,
                    isZone: Boolean(zoom),
                };
                // 마우스를 올리면 강조
                layer.on('mouseover', () => {
                    entry.hover = true;
                    layer.setStyle(styleOf(entry));
                });
                layer.on('mouseout', () => {
                    // 우클릭 메뉴가 떠 있는 동안에는 그 구역의 강조를 유지한다(holdZone)
                    if (entry.held) {
                        return;
                    }
                    entry.hover = false;
                    layer.setStyle(styleOf(entry));
                });
                boundaryLayers.push(entry);
            });
            applyPaint();
        },

        /** 우클릭 메뉴가 뜬 구역(zoneId)을 강조한 채로 붙들어 둔다. 마우스가 메뉴로 옮겨가도 풀리지 않는다(releaseZone으로 푼다). */
        holdZone(zoneId) {
            // 이전에 붙들어 둔 강조는 푼다
            this.releaseZone();
            // 구역 경계만 찾는다(지역 경계와 id가 겹칠 수 있어 isZone도 확인)
            const entry = boundaryLayers.find(
                (candidate) => candidate.isZone && candidate.id === zoneId,
            );
            if (entry) {
                entry.held = true;
                entry.hover = true;
                entry.layer.setStyle(styleOf(entry));
            }
        },

        /** 붙들어 둔 강조를 푼다(메뉴가 닫힐 때). */
        releaseZone() {
            // 붙들려 있던 강조만 푼다
            boundaryLayers.forEach((entry) => {
                if (entry.held) {
                    entry.held = false;
                    entry.hover = false;
                    entry.layer.setStyle(styleOf(entry));
                }
            });
        },

        /**
         * 구역 하나의 꼭짓점 편집을 시작한다(FNC-A-015). 꼭짓점을 끌거나 더하거나 지울 때마다 onChange가 새 GeoJSON Polygon으로 불린다.
         * 선이 서로 겹치게 옮기는 것은 막는다. 고치는 동안 그 구역은 수정 색으로 칠한다.
         */
        startZoneEdit(zoneId, onChange) {
            // 이전 편집이 있으면 먼저 끝낸다
            this.stopZoneEdit();
            const entry = boundaryLayers.find(
                (candidate) => candidate.isZone && candidate.id === zoneId,
            );
            // GeoJSON 레이어 안의 실제 다각형
            const polygon = entry ? entry.layer.getLayers()[0] : null;
            if (!polygon) {
                return;
            }
            // 편집 중이면 색이 수정 색으로 고정된다(styleOf 참고)
            entry.editing = true;
            entry.layer.setStyle(EDIT_STYLE);
            entry.layer.bringToFront();
            // 꼭짓점을 지워 3점 아래로 내려가면 구역 모양이 사라지므로, 3점 아래로는 지울 수 없게 한다
            polygon.pm.enable({
                allowSelfIntersection: false,
                removeLayerBelowMinVertexCount: false,
            });
            // 꼭짓점이 바뀔 때마다 새 경계를 알린다
            const handler = () => onChange(polygon.toGeoJSON().geometry);
            polygon.on('pm:edit', handler);
            // 끝낼 때 이벤트를 떼려고 함께 보관
            zoneEditing = { entry, polygon, handler };
        },

        /** 꼭짓점 편집을 끝낸다. 고친 모양을 버리려면 부른 쪽이 경계를 다시 그린다(showBoundaries). */
        stopZoneEdit() {
            if (!zoneEditing) {
                return;
            }
            // 편집 이벤트를 떼고 편집 모드를 끈다
            const { entry, polygon, handler } = zoneEditing;
            polygon.off('pm:edit', handler);
            polygon.pm.disable();
            entry.editing = false;
            zoneEditing = null;
        },

        /**
         * 지금 보는 지역(target)과 이웃 지역(siblings)의 윤곽선을 그린다. 하위 단위 경계(showBoundaries)와 별개로 남아,
         * 동까지 내려가도 지역 경계가 지도에서 사라지지 않는다.
         * @param {{boundary: object|null}} target 지금 보는 지역(regions API 항목)
         * @param {{id: number, name: string, boundary: object|null}[]} siblings 같은 단계의 이웃 지역(누르면 onSelectSibling)
         */
        showOutlines(target, siblings) {
            // 이전 윤곽선을 지우고 지금 지역의 바깥 고리를 다시 구한다(빈 곳 우클릭 판정에 쓴다)
            outlines.clearLayers();
            regionRings = target.boundary ? outerRings(target.boundary) : [];
            // 이웃 지역 윤곽선: 이동 처리기가 있을 때만 눌러서 이동할 수 있다
            siblings.forEach(({ id, name, boundary }) =>
                addOutline(
                    boundary,
                    OUTLINE_SIBLING,
                    onSelectSibling ? () => onSelectSibling(id) : undefined,
                    name,
                ),
            );
            // 지금 지역 윤곽선은 맨 위에 그린다(눌러도 반응하지 않음)
            addOutline(target.boundary, OUTLINE_TARGET);
        },

        /**
         * 경계를 혼잡 단계 색 대신 주어진 색으로 칠한다(히트맵, FNC-C-011). null이면 단계 색으로 되돌린다.
         * 경계를 다시 그려도(새 1분 통계) 끌 때까지 이 색을 유지한다.
         * @param {Map<number, {color: string, fillOpacity: number, label: string}>|null} next 단위 id → 칠할 모양
         */
        paintBoundaries(next) {
            // 새 색 표를 저장하고 바로 적용한다. null이면 단계 색으로 되돌린다
            paint = next;
            applyPaint();
        },

        /** 구역 핀: 중심점에 단계 색, 진행 중 이벤트가 있으면 오늘 이벤트 수 배지. */
        showPins(items) {
            // 핀을 다시 그려도 열려 있던 팝업은 다시 열려고 그 구역 id를 기억한다
            const reopenZoneId = openPopupZoneId;
            pins.clearLayers();
            // 중심점이 있는 구역만 핀을 둔다
            zonePoints = items
                .filter((item) => item.centerLat !== null && item.centerLat !== undefined)
                .map((item) => ({
                    item,
                    latLng: L.latLng(Number(item.centerLat), Number(item.centerLng)),
                }));
            zonePoints.forEach(({ item, latLng }) => {
                // 핀 아이콘은 단계 색, 진행 중 이벤트가 있으면 배지를 단다
                const marker = L.marker(latLng, { icon: pinIcon(item) });
                // 올릴 때마다 만들어 그때까지 받은 5초 현재 인원을 담는다
                marker.bindTooltip(() => tooltip(item.name, item));
                // 핀 위를 우클릭해도 그 구역의 우클릭 메뉴가 뜬다(관리자 앱). 지도의 빈 곳 우클릭으로 번지지 않게 끊는다
                // 관리자 앱: 핀 우클릭도 그 구역의 메뉴를 연다
                if (onZoneMenu) {
                    marker.on('contextmenu', (event) => {
                        L.DomEvent.stop(event);
                        if (!isBusy?.()) {
                            onZoneMenu(item.id, event.originalEvent);
                        }
                    });
                }
                // 핀 클릭 처리기가 있으면(관리자 앱) 그것을, 없으면(사용자 앱, FNC-C-009) 구역 정보 팝업을 연다
                if (onPinClick) {
                    marker.on('click', () => onPinClick(item.id));
                } else {
                    marker.bindPopup(() => zonePopup(item, levels), { offset: POPUP_OFFSET });
                    marker.on('popupopen', () => {
                        openPopupZoneId = item.id;
                    });
                    marker.on('popupclose', () => {
                        openPopupZoneId = null;
                    });
                }
                pins.addLayer(marker);
                // 다시 그리기 전에 열려 있던 팝업이면 새 핀에서 다시 연다
                if (!onPinClick && item.id === reopenZoneId) {
                    marker.openPopup();
                }
            });
        },

        /** 지금 지도 화면 안에 핀이 있는 구역 id (캠 패널이 '지도 화면 안 캠'을 거를 때 쓴다) */
        visibleZoneIds() {
            // 지도에 보이는 영역
            const bounds = map.getBounds();
            // 영역 안에 점이 있는 구역의 id 집합
            return new Set(
                zonePoints
                    .filter(({ latLng }) => bounds.contains(latLng))
                    .map(({ item }) => item.id),
            );
        },

        /** 통계 패널을 접고 펼치거나 캠 패널 높이가 바뀌어 지도 칸 크기가 달라진 뒤에 부른다 */
        refreshSize() {
            // 지도 칸 크기가 바뀐 뒤 Leaflet이 새 크기를 다시 계산하게 한다
            map.invalidateSize();
        },
    };
}

// GeoJSON Polygon·MultiPolygon의 바깥 고리들(구멍은 뺀다)
// 지도에서 지금 지역의 바깥 고리를 꺼내 '이 점이 지역 안인가'를 판정할 때 쓴다
function outerRings(boundary) {
    const rings = [];
    // Leaflet 레이어에서 좌표 목록을 꺼낸다
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
// 점-다각형 판정(ray casting): 점에서 오른쪽으로 반직선을 쏘아 변과 만나는 횟수가 홀수면 안이다
function insideRing(point, ring) {
    let inside = false;
    // j는 i의 직전 꼭짓점. 변 (j,i)를 차례로 검사한다
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const a = ring[i];
        const b = ring[j];
        // 변이 점의 위도를 가로지르고, 가로지르는 지점이 점의 오른쪽에 있으면 한 번 교차한 것
        if (
            a.lat > point.lat !== b.lat > point.lat &&
            point.lng < ((b.lng - a.lng) * (point.lat - a.lat)) / (b.lat - a.lat) + a.lng
        ) {
            // 교차할 때마다 안/밖이 뒤집힌다
            inside = !inside;
        }
    }
    return inside;
}

// 핀 아이콘 HTML 요소를 Leaflet divIcon으로 만든다
function pinIcon(item) {
    const pin = document.createElement('span');
    // 단계가 없으면 꺼짐(off) 모양
    pin.className = 'pin ' + (item.level ? 'l' + item.level : 'off');
    pin.style.background = levelColor(item.level);
    // 고정 문자열(서버 값 아님)이라 innerHTML을 써도 안전하다
    pin.innerHTML = PIN_ICON;
    // 진행 중인 이벤트가 있으면 오른쪽 위 배지
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
    // 단계가 없는 구역
    if (!item.level) {
        // 1분 통계가 아직 없어도 5초 현재 인원이 있으면 그것만 보인다(캠을 막 가동한 구역)
        if (hasNowCount(item)) {
            box.append(popupRow('현재 인원', nowCountSpan(item)));
            return box;
        }
        const empty = document.createElement('div');
        empty.textContent = '데이터 없음';
        box.append(empty);
        return box;
    }
    // 서버가 준 단계 이름을 찾는다. 없으면 '단계 N'
    const found = levels.find((candidate) => candidate.level === item.level);
    const chip = document.createElement('span');
    chip.className = 'chip l' + item.level;
    chip.textContent = found ? found.name : '단계 ' + item.level;
    box.append(
        popupRow('현재 인원', nowCountSpan(item)),
        popupRow('밀집도', document.createTextNode(`${formatDecimal(item.density, 2)}명/㎡`)),
        popupRow('혼잡 단계', chip),
    );
    return box;
}

// 팝업 한 줄: 이름과 값
function popupRow(label, value) {
    const row = document.createElement('div');
    const name = document.createElement('span');
    name.textContent = label;
    row.append(name, value);
    return row;
}

// 제목(굵게) + 본문 한 줄 툴팁. 히트맵 값이나 '눌러서 이동' 안내에 쓴다
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
    // 구역이면 구·동 경로를 한 줄 더 보인다
    if (item?.regionPath) {
        const path = document.createElement('div');
        path.textContent = item.regionPath;
        box.append(path);
    }
    const body = document.createElement('div');
    // 통계가 있으면 '현재 인원 · 밀집도', 현재 인원만 있으면 그것만, 둘 다 없으면 '데이터 없음'
    if (item?.level) {
        body.append(nowCountSpan(item), ` · ${formatDecimal(item.density, 2)}명/㎡`);
    } else if (item && hasNowCount(item)) {
        body.append(nowCountSpan(item));
    } else {
        body.textContent = '데이터 없음';
    }
    box.append(body);
    return box;
}

// 구역 단위 항목만 5초 현재 인원을 받는다(지역 합은 서버가 준 값 그대로)
// 구역 항목만 중심점을 갖는다(지역은 중심점이 있어도 5초 현재 인원 대상이 아니라 서버 값 그대로)
function isZoneItem(item) {
    return item.centerLat !== null && item.centerLat !== undefined;
}

// 서버가 5초 현재 인원을 준 항목인지
function hasNowCount(item) {
    return item.currentCount !== null && item.currentCount !== undefined;
}

// 서버가 준 currentCount(없으면 1분 평균)를 처음 보이고, 구역이면 통계 SSE event: now 가 올 때 이 숫자만 바꾼다
function nowCountSpan(item) {
    // 숫자 칸. 처음에는 서버 값(없으면 1분 평균)을 보이고, 구역이면 now-count가 이 칸을 5초마다 갱신한다
    const span = document.createElement('span');
    const fallback = `${formatNumber(hasNowCount(item) ? item.currentCount : Math.round(Number(item.countAvg)))}명`;
    // 구역 항목만 5초 갱신에 묶는다
    if (isZoneItem(item)) {
        bindNowCount(span, item.id, fallback, '명');
    } else {
        span.textContent = fallback;
    }
    return span;
}
