// 구역 관리 지도(SCR-A-004): 동 경계·구역 다각형 표시와 Leaflet-Geoman 다각형 그리기·꼭짓점 편집.
// Leaflet·Geoman은 템플릿의 fragments/layout :: leaflet 이 전역 L로 불러온다. 이 코드는 map/ 밖에 두지 않는다(code-conventions 5-3).
import { createMap } from './map-view.js';

// 처음 보이는 위치(대전). 동을 고르면 그 동 경계로 맞춘다
// 시연 지역(대전) 중심 [위도, 경도]와 확대 단계
const DEFAULT_VIEW = { center: [36.3504, 127.3845], zoom: 13 };

// 동 경계: 점선 외곽선만(칠하지 않고 클릭도 받지 않음)
const DONG_STYLE = {
    color: '#7d8a9b',
    weight: 2,
    dashArray: '8 6',
    fill: false,
    interactive: false,
};
// 구역 다각형 기본 색(초록)과 선택 색(파랑), 그리는 중 초안 색
const ZONE_STYLE = { color: '#3bc99b', weight: 2, fillColor: '#3bc99b', fillOpacity: 0.18 };
const ZONE_SELECTED_STYLE = {
    color: '#76a7ff',
    weight: 3,
    fillColor: '#4f8cff',
    fillOpacity: 0.28,
};
const DRAFT_STYLE = { color: '#76a7ff', weight: 2.5, fillColor: '#4f8cff', fillOpacity: 0.2 };

/**
 * 구역 관리 지도를 만든다.
 * @param {HTMLElement} element 지도 칸
 * @param {{onZoneClick: (zoneId: number) => void, onDrawn: (boundary: object) => void}} handlers
 *   onDrawn은 그리기를 마치면 GeoJSON Polygon(geometry)과 함께 불린다
 */
export function createZoneMap(element, { onZoneClick, onDrawn }) {
    // 공통 지도 만들기 + 레이어
    const map = createMap(element, DEFAULT_VIEW);
    // 동 경계 층(처음엔 비어 있음)
    const dongLayer = L.geoJSON(null, { style: DONG_STYLE }).addTo(map);
    // 구역 id -> 지도 위 레이어
    const zoneLayers = new Map();
    // draft: 새로 그린 초안 다각형, selectedId: 선택된 구역
    let draft = null;
    let selectedId = null;
    // 꼭짓점을 고치는 중인 구역의 다각형 { polygon, handler }
    let editing = null;

    // Geoman 그리기 완료 이벤트: 이전 초안을 지우고 새 초안을 두고 경계를 알린다
    map.on('pm:create', (event) => {
        removeDraft();
        draft = event.layer;
        draft.setStyle(DRAFT_STYLE);
        onDrawn(draft.toGeoJSON().geometry);
    });

    function removeDraft() {
        if (draft) {
            draft.remove();
            draft = null;
        }
    }

    // 선택된 구역만 선택 색, 나머지는 기본 색
    function applyStyle(id, layer) {
        layer.setStyle(id === selectedId ? ZONE_SELECTED_STYLE : ZONE_STYLE);
    }

    return {
        /** 동 경계(GeoJSON)를 점선으로 그리고 지도를 그 크기에 맞춘다. boundary가 없으면 처음 위치로. */
        showDong(boundary) {
            // 이전 동 경계를 지운다
            dongLayer.clearLayers();
            if (!boundary) {
                map.setView(DEFAULT_VIEW.center, DEFAULT_VIEW.zoom);
                return;
            }
            dongLayer.addData(boundary);
            map.fitBounds(dongLayer.getBounds(), { padding: [30, 30] });
        },

        /** 구역 다각형을 모두 새로 그린다. zones는 /api/v1/zones 응답 항목. */
        showZones(zones) {
            // 이전 구역 층을 모두 떼고 비운 뒤 다시 추가한다
            zoneLayers.forEach((layer) => layer.remove());
            zoneLayers.clear();
            zones.forEach((zone) => this.addZone(zone));
        },

        addZone(zone) {
            // zone.boundary는 GeoJSON. 구역 한 개를 다각형 레이어로 만든다
            const layer = L.geoJSON(zone.boundary, { style: ZONE_STYLE });
            layer.on('click', () => {
                // 그리는 중이나 꼭짓점을 고치는 중에 구역을 누르면 선택(지도 맞춤)이 되어 지도가 축소되므로 무시한다
                if (editing || map.pm.globalDrawModeEnabled()) {
                    return;
                }
                onZoneClick(zone.id);
            });
            // 이름은 마우스를 따라다니는 툴팁으로
            layer.bindTooltip(zone.name, { sticky: true });
            layer.addTo(map);
            // id로 찾을 수 있게 보관하고, 선택 상태에 맞는 색을 입힌다
            zoneLayers.set(zone.id, layer);
            applyStyle(zone.id, layer);
        },

        /** 구역 하나를 강조하고 그 구역으로 지도를 맞춘다. null이면 강조를 푼다. */
        select(zoneId) {
            // 선택 상태를 기록하고 모든 구역의 색을 다시 입힌다
            selectedId = zoneId;
            zoneLayers.forEach((layer, id) => applyStyle(id, layer));
            const layer = zoneLayers.get(zoneId);
            if (layer) {
                // 선택 구역을 맨 위로 올리고 지도를 그 구역에 맞춘다(maxZoom으로 너무 확대되지 않게 제한)
                layer.bringToFront();
                map.fitBounds(layer.getBounds(), { padding: [60, 60], maxZoom: 18 });
            }
        },

        /**
         * 구역 하나의 꼭짓점 편집을 시작한다(FNC-A-015). 꼭짓점을 끌거나 더하거나 지울 때마다 onChange가 새 GeoJSON Polygon으로 불린다.
         * 선이 서로 겹치게 옮기는 것은 막는다.
         */
        startEdit(zoneId, onChange) {
            // 이전 편집이 있으면 먼저 끝낸다
            this.stopEdit();
            const layer = zoneLayers.get(zoneId);
            // GeoJSON 레이어 안의 실제 다각형 하나를 꺼낸다
            const polygon = layer ? layer.getLayers()[0] : null;
            if (!polygon) {
                return;
            }
            // 편집 중임을 알리는 색으로 바꾸고 꼭짓점 편집을 켠다. allowSelfIntersection false: 선이 꼬이게 옮기는 것을 막는다
            polygon.setStyle(DRAFT_STYLE);
            polygon.bringToFront();
            polygon.pm.enable({ allowSelfIntersection: false });
            // 꼭짓점이 바뀔 때마다 새 경계를 알린다
            const handler = () => onChange(polygon.toGeoJSON().geometry);
            polygon.on('pm:edit', handler);
            // 나중에 끝낼 때 이벤트를 떼려고 함께 보관한다
            editing = { polygon, handler };
        },

        /** 꼭짓점 편집을 끝낸다. 고친 모양을 버리려면 호출한 쪽이 showZones로 다시 그린다. */
        stopEdit() {
            if (!editing) {
                // 이벤트를 떼고 편집 모드를 끈다
                return;
            }
            editing.polygon.off('pm:edit', editing.handler);
            editing.polygon.pm.disable();
            editing = null;
        },

        /** 다각형 그리기를 시작한다. 이미 그린 초안은 지운다. */
        startDraw() {
            // 새 초안을 위해 이전 초안을 지운다
            removeDraft();
            // finishOn null: 자동 종료 없이 첫 점을 눌러 닫는다
            map.pm.enableDraw('Polygon', { pathOptions: DRAFT_STYLE, finishOn: null });
        },

        /** 그리는 중이거나 그려 둔 초안을 지운다. */
        cancelDraw() {
            map.pm.disableDraw();
            removeDraft();
        },
    };
}
