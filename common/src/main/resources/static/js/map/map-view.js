// 기본 지도(Leaflet + OSM). Leaflet 코드는 이 폴더(map/) 밖에 두지 않는다(code-conventions 5-3).
// Leaflet은 템플릿의 fragments/layout :: leaflet 이 전역 L로 불러온다.

const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const TILE_ATTRIBUTION = '© OpenStreetMap contributors';
const MAX_ZOOM = 19;

// 혼잡 단계(1 정상, 2 주의, 3 위험)와 꺼짐의 색. 값은 /api/v1/meta/levels와 같다(we.css --lv1~3)
const LEVEL_COLORS = { 1: '#3bc99b', 2: '#f59e0b', 3: '#ef4d55' };
const OFF_COLOR = '#4a5768';

/** 혼잡 단계의 표시 색. 모르는 단계(꺼짐·자료 없음)는 회색. */
export function levelColor(level) {
    return LEVEL_COLORS[level] ?? OFF_COLOR;
}

/**
 * element 안에 지도를 만든다. 확대·축소·초기화 버튼은 시안(we.css .map-ctrl) 모양이다.
 * 초기화는 처음 center·zoom으로 되돌린다.
 * @param {HTMLElement} element 지도 칸
 * @param {{center: [number, number], zoom: number}} options center는 [위도, 경도]
 * @returns Leaflet 지도
 */
export function createMap(element, { center, zoom }) {
    const map = L.map(element, { center, zoom, zoomControl: false, attributionControl: true });
    L.tileLayer(TILE_URL, { maxZoom: MAX_ZOOM, attribution: TILE_ATTRIBUTION }).addTo(map);
    new ResetControl({ center, zoom }).addTo(map);
    return map;
}

const ResetControl = L.Control.extend({
    options: { position: 'bottomright' },

    initialize(home) {
        this.home = home;
    },

    onAdd(map) {
        const box = L.DomUtil.create('div', 'map-ctrl');
        box.append(
            button('+', '확대', () => map.zoomIn()),
            button('−', '축소', () => map.zoomOut()),
            button('초기화', '처음 위치로', () => map.setView(this.home.center, this.home.zoom)),
        );
        L.DomEvent.disableClickPropagation(box);
        return box;
    },
});

function button(text, label, onClick) {
    const element = document.createElement('button');
    element.type = 'button';
    element.textContent = text;
    element.title = label;
    element.setAttribute('aria-label', label);
    element.addEventListener('click', onClick);
    return element;
}
