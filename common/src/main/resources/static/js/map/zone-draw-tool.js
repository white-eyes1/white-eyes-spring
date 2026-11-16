// 대시보드 지도(SCR-C-003)에서 구역을 그리는 도구: Leaflet-Geoman 다각형 그리기.
// 구역 관리 화면(SCR-A-004)의 지도·그리기는 map/zone-draw.js. Leaflet·Geoman은 템플릿의 fragments/layout :: leaflet 이 전역 L로 불러온다.

// 그리는 중인 초안 다각형의 모양
const DRAFT_STYLE = { color: '#76a7ff', weight: 2.5, fillColor: '#4f8cff', fillOpacity: 0.2 };

/**
 * 이미 만든 지도에 구역 그리기 도구를 붙인다.
 * @param map createRegionMap의 지도
 * @param {{onDrawn: (boundary: object) => void}} handlers onDrawn: 그리기를 마치면 GeoJSON Polygon(geometry)과 함께
 */
export function createZoneDrawTool(map, { onDrawn }) {
    // 초안 다각형(한 번에 하나만 둔다)
    let draft = null;

    // Geoman이 다각형 그리기를 마치면 pm:create가 발생한다
    map.on('pm:create', (event) => {
        // 이전 초안이 남아 있으면 지운다
        removeDraft();
        draft = event.layer;
        draft.setStyle(DRAFT_STYLE);
        // 경계를 GeoJSON Polygon으로 바꿔 호출한 쪽(구역 등록 창)에 넘긴다
        onDrawn(draft.toGeoJSON().geometry);
    });

    // 초안이 있으면 지도에서 떼고 비운다
    function removeDraft() {
        if (draft) {
            draft.remove();
            draft = null;
        }
    }

    return {
        /** 다각형 그리기를 시작한다. 이미 그린 초안은 지운다. */
        startDraw() {
            // 새 초안을 위해 이전 초안을 지운다
            removeDraft();
            // finishOn null: 더블클릭 등으로 자동 종료하지 않고 첫 점을 다시 눌러 닫게 한다
            map.pm.enableDraw('Polygon', { pathOptions: DRAFT_STYLE, finishOn: null });
        },

        /** 그리는 중이거나 그려 둔 초안을 지운다. */
        cancel() {
            // 그리기 모드를 끄고 초안을 지운다
            map.pm.disableDraw();
            removeDraft();
        },
    };
}
