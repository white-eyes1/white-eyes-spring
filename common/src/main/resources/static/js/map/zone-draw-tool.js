// 대시보드 지도(SCR-C-003)에서 구역을 그리는 도구: Leaflet-Geoman 다각형 그리기.
// 구역 관리 화면(SCR-A-004)의 지도·그리기는 map/zone-draw.js. Leaflet·Geoman은 템플릿의 fragments/layout :: leaflet 이 전역 L로 불러온다.

const DRAFT_STYLE = { color: '#76a7ff', weight: 2.5, fillColor: '#4f8cff', fillOpacity: 0.2 };

/**
 * 이미 만든 지도에 구역 그리기 도구를 붙인다.
 * @param map createRegionMap의 지도
 * @param {{onDrawn: (boundary: object) => void}} handlers onDrawn: 그리기를 마치면 GeoJSON Polygon(geometry)과 함께
 */
export function createZoneDrawTool(map, { onDrawn }) {
    let draft = null;

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

    return {
        /** 다각형 그리기를 시작한다. 이미 그린 초안은 지운다. */
        startDraw() {
            removeDraft();
            map.pm.enableDraw('Polygon', { pathOptions: DRAFT_STYLE, finishOn: null });
        },

        /** 그리는 중이거나 그려 둔 초안을 지운다. */
        cancel() {
            map.pm.disableDraw();
            removeDraft();
        },
    };
}
