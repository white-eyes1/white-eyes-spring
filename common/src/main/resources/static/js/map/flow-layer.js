// 구역 간 이동 흐름 화살표(SCR-C-003 지도 도구, FNC-C-010). Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3).
// 이웃 구역으로 간 이동은 두 구역 중심점을 잇는 실선 화살표, 관측 범위 밖으로 간 이동은 그 방향으로 짧은 점선 화살표다.
// 굵기는 가장 큰 이동에 대한 비율이다. A→B와 B→A가 겹치지 않게 진행 방향 오른쪽으로 조금 비켜 그린다.
// 화면 좌표(픽셀)로 계산하므로 확대·축소하면 다시 그린다. 서버 값은 textContent로만 넣는다.
import { formatNumber } from '../common/format.js';

const NEIGHBOR_COLOR = '#76a7ff';
const OUTSIDE_COLOR = '#9aa7b8';
const MIN_WEIGHT = 2;
const MAX_EXTRA_WEIGHT = 7;
// 마주 보는 화살표 사이 간격, 핀을 가리지 않게 양끝에서 뺄 길이, 관측 범위 밖 화살표 길이, 화살촉 크기(px)
const SIDE_OFFSET_PX = 6;
const END_GAP_PX = 16;
const OUTSIDE_LENGTH_PX = 56;
const HEAD_PX = 9;
const DIRECTION_VECTORS = { N: [0, -1], E: [1, 0], S: [0, 1], W: [-1, 0] };
const DIRECTION_NAMES = { N: '북', E: '동', S: '남', W: '서' };

/**
 * 지도 위에 이동 화살표 층을 만든다.
 * @param map Leaflet 지도
 */
export function createFlowLayer(map) {
    const group = L.layerGroup().addTo(map);
    let current = null; // 마지막으로 그린 { flows, zones }
    map.on('zoomend', redraw);

    /**
     * @param {{fromZoneId: number, toZoneId: number|null, direction: string, count: number}[]} flows GET /api/v1/map/flows
     * @param {{id: number, name: string, centerLat: number, centerLng: number}[]} zones 중심점을 아는 구역(map/status ZONE)
     */
    function show(flows, zones) {
        current = { flows, zones };
        redraw();
    }

    function clear() {
        current = null;
        group.clearLayers();
    }

    function redraw() {
        group.clearLayers();
        if (!current) {
            return;
        }
        const byId = new Map(current.zones.map((zone) => [zone.id, zone]));
        const max = Math.max(1, ...current.flows.map((flow) => flow.count));
        current.flows.forEach((flow) => {
            const from = byId.get(flow.fromZoneId);
            if (!from) {
                return;
            }
            const to = flow.toZoneId === null ? null : byId.get(flow.toZoneId);
            // 지역 밖 이웃(중심점을 모름)은 그 방향 짧은 화살표로 그린다
            const points = to ? neighborPoints(from, to) : outsidePoints(from, flow.direction);
            const weight = MIN_WEIGHT + MAX_EXTRA_WEIGHT * (flow.count / max);
            const isNeighbor = flow.toZoneId !== null;
            const style = {
                color: isNeighbor ? NEIGHBOR_COLOR : OUTSIDE_COLOR,
                weight,
                opacity: 0.9,
                dashArray: isNeighbor ? null : '6 6',
            };
            const line = L.polyline(points.map(toLatLng), style);
            const head = L.polygon(arrowHead(points[0], points[1], weight).map(toLatLng), {
                ...style,
                dashArray: null,
                fillColor: style.color,
                fillOpacity: 0.9,
                weight: 1,
            });
            const label = tooltip(from, to, flow);
            line.bindTooltip(label, { sticky: true });
            head.bindTooltip(label, { sticky: true });
            group.addLayer(line);
            group.addLayer(head);
        });
    }

    function point(zone) {
        return map.latLngToLayerPoint(L.latLng(Number(zone.centerLat), Number(zone.centerLng)));
    }

    function toLatLng(layerPoint) {
        return map.layerPointToLatLng(layerPoint);
    }

    // 두 중심점을 잇되 양끝을 조금 줄이고, 진행 방향 오른쪽으로 비켜 반대 방향 화살표와 겹치지 않게 한다
    function neighborPoints(from, to) {
        const a = point(from);
        const b = point(to);
        const length = a.distanceTo(b) || 1;
        const unit = L.point((b.x - a.x) / length, (b.y - a.y) / length);
        const side = L.point(-unit.y * SIDE_OFFSET_PX, unit.x * SIDE_OFFSET_PX);
        const gap = Math.min(END_GAP_PX, length / 3);
        return [
            a.add(unit.multiplyBy(gap)).add(side),
            b.subtract(unit.multiplyBy(gap)).add(side),
        ];
    }

    function outsidePoints(from, direction) {
        const a = point(from);
        const [dx, dy] = DIRECTION_VECTORS[direction] ?? [0, -1];
        const unit = L.point(dx, dy);
        return [a.add(unit.multiplyBy(END_GAP_PX)), a.add(unit.multiplyBy(END_GAP_PX + OUTSIDE_LENGTH_PX))];
    }

    return { show, clear };
}

// 끝점에 붙는 삼각형 화살촉(굵을수록 조금 크게)
function arrowHead(start, end, weight) {
    const length = start.distanceTo(end) || 1;
    const unit = L.point((end.x - start.x) / length, (end.y - start.y) / length);
    const size = HEAD_PX + weight;
    const back = end.subtract(unit.multiplyBy(size));
    const side = L.point(-unit.y * size * 0.55, unit.x * size * 0.55);
    return [end, back.add(side), back.subtract(side)];
}

// '사직동 광장 → 사직동 공원 입구 · 340명(추정)', 관측 범위 밖은 '→ 관측 범위 밖(동쪽)'
function tooltip(from, to, flow) {
    const box = document.createElement('div');
    const title = document.createElement('b');
    title.textContent = to
        ? `${from.name} → ${to.name}`
        : `${from.name} → ${flow.toZoneId === null ? '관측 범위 밖' : '지역 밖 이웃'}(${DIRECTION_NAMES[flow.direction]}쪽)`;
    const body = document.createElement('div');
    body.textContent = `${formatNumber(flow.count)}명(추정)`;
    box.append(title, body);
    return box;
}
