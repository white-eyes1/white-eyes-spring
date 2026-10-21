// 구역 관리 지도(SCR-A-004): 동 경계·구역 다각형 표시와 Leaflet-Geoman 다각형 그리기.
// Leaflet·Geoman은 템플릿의 fragments/layout :: leaflet 이 전역 L로 불러온다. 이 코드는 map/ 밖에 두지 않는다(code-conventions 5-3).
import { createMap } from './map-view.js';

// 처음 보이는 위치(대전). 동을 고르면 그 동 경계로 맞춘다
const DEFAULT_VIEW = { center: [36.3504, 127.3845], zoom: 13 };

const DONG_STYLE = { color: '#7d8a9b', weight: 2, dashArray: '8 6', fill: false, interactive: false };
const ZONE_STYLE = { color: '#3bc99b', weight: 2, fillColor: '#3bc99b', fillOpacity: 0.18 };
const ZONE_SELECTED_STYLE = { color: '#76a7ff', weight: 3, fillColor: '#4f8cff', fillOpacity: 0.28 };
const DRAFT_STYLE = { color: '#76a7ff', weight: 2.5, fillColor: '#4f8cff', fillOpacity: 0.2 };

/**
 * 구역 관리 지도를 만든다.
 * @param {HTMLElement} element 지도 칸
 * @param {{onZoneClick: (zoneId: number) => void, onDrawn: (boundary: object) => void}} handlers
 *   onDrawn은 그리기를 마치면 GeoJSON Polygon(geometry)과 함께 불린다
 */
export function createZoneMap(element, { onZoneClick, onDrawn }) {
    const map = createMap(element, DEFAULT_VIEW);
    const dongLayer = L.geoJSON(null, { style: DONG_STYLE }).addTo(map);
    const zoneLayers = new Map();
    let draft = null;
    let selectedId = null;

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

    function applyStyle(id, layer) {
        layer.setStyle(id === selectedId ? ZONE_SELECTED_STYLE : ZONE_STYLE);
    }

    return {
        /** 동 경계(GeoJSON)를 점선으로 그리고 지도를 그 크기에 맞춘다. boundary가 없으면 처음 위치로. */
        showDong(boundary) {
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
            zoneLayers.forEach((layer) => layer.remove());
            zoneLayers.clear();
            zones.forEach((zone) => this.addZone(zone));
        },

        addZone(zone) {
            const layer = L.geoJSON(zone.boundary, { style: ZONE_STYLE });
            layer.on('click', () => onZoneClick(zone.id));
            layer.bindTooltip(zone.name, { sticky: true });
            layer.addTo(map);
            zoneLayers.set(zone.id, layer);
            applyStyle(zone.id, layer);
        },

        /** 구역 하나를 강조하고 그 구역으로 지도를 맞춘다. null이면 강조를 푼다. */
        select(zoneId) {
            selectedId = zoneId;
            zoneLayers.forEach((layer, id) => applyStyle(id, layer));
            const layer = zoneLayers.get(zoneId);
            if (layer) {
                layer.bringToFront();
                map.fitBounds(layer.getBounds(), { padding: [60, 60], maxZoom: 18 });
            }
        },

        /** 다각형 그리기를 시작한다. 이미 그린 초안은 지운다. */
        startDraw() {
            removeDraft();
            map.pm.enableDraw('Polygon', { pathOptions: DRAFT_STYLE, finishOn: null });
        },

        /** 그리는 중이거나 그려 둔 초안을 지운다. */
        cancelDraw() {
            map.pm.disableDraw();
            removeDraft();
        },
    };
}
