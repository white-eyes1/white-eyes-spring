// 구역 간 이동 흐름 화살표(SCR-C-003 지도 도구, FNC-C-010). Leaflet 코드는 map/ 폴더 안에만 둔다(code-conventions 5-3).
// 이웃 구역으로 간 이동은 두 구역 중심점을 잇는 실선 화살표, 관측 범위 밖으로 간 이동은 그 방향으로 짧은 점선 화살표다.
// 굵기는 가장 큰 이동에 대한 비율이다. A→B와 B→A가 겹치지 않게 진행 방향 오른쪽으로 조금 비켜 그린다.
// 화면 좌표(픽셀)로 계산하므로 확대·축소하면 다시 그린다. 서버 값은 textContent로만 넣는다.
import { formatNumber } from '../common/format.js';

// 화살표 색: 이웃 구역으로 가는 것은 파랑, 관측 범위 밖은 회색
const NEIGHBOR_COLOR = '#76a7ff';
const OUTSIDE_COLOR = '#9aa7b8';
// 선 굵기는 최소 2px에서 가장 큰 이동일 때 +7px까지
const MIN_WEIGHT = 2;
const MAX_EXTRA_WEIGHT = 7;
// 마주 보는 화살표 사이 간격, 핀을 가리지 않게 양끝에서 뺄 길이, 관측 범위 밖 화살표 길이, 화살촉 크기(px)
const SIDE_OFFSET_PX = 6;
const END_GAP_PX = 16;
const OUTSIDE_LENGTH_PX = 56;
const HEAD_PX = 9;
// 방향 코드 -> 화면 좌표 단위 벡터(위가 북이라 y는 위로 갈수록 작다)
const DIRECTION_VECTORS = { N: [0, -1], E: [1, 0], S: [0, 1], W: [-1, 0] };
const DIRECTION_NAMES = { N: '북', E: '동', S: '남', W: '서' };

/**
 * 지도 위에 이동 화살표 층을 만든다.
 * @param map Leaflet 지도
 */
export function createFlowLayer(map) {
    // 화살표를 담는 층. 다시 그릴 때 한 번에 비운다
    const group = L.layerGroup().addTo(map);
    let current = null; // 마지막으로 그린 { flows, zones }
    // 확대·축소하면 픽셀 간격이 바뀌므로 다시 그린다
    map.on('zoomend', redraw);

    /**
     * @param {{fromZoneId: number, toZoneId: number|null, direction: string, count: number}[]} flows GET /api/v1/map/flows
     * @param {{id: number, name: string, centerLat: number, centerLng: number}[]} zones 중심점을 아는 구역(map/status ZONE)
     */
    function show(flows, zones) {
        // 확대·축소 때 다시 그릴 수 있게 마지막 자료를 기억해 둔다
        current = { flows, zones };
        redraw();
    }

    function clear() {
        // 화살표를 모두 지우고 자료도 버린다
        current = null;
        group.clearLayers();
    }

    function redraw() {
        // 기존 화살표를 모두 지우고 현재 자료로 다시 그린다
        group.clearLayers();
        if (!current) {
            return;
        }
        // 구역 id로 중심점을 빨리 찾는 표
        const byId = new Map(current.zones.map((zone) => [zone.id, zone]));
        // 가장 큰 이동 건수. 굵기를 비율로 정하는 기준(0 방지로 최소 1)
        const max = Math.max(1, ...current.flows.map((flow) => flow.count));
        current.flows.forEach((flow) => {
            const from = byId.get(flow.fromZoneId);
            // 출발 구역의 위치를 모르면 그릴 수 없다
            if (!from) {
                return;
            }
            // 도착 구역이 null이면 관측 범위 밖
            const to = flow.toZoneId === null ? null : byId.get(flow.toZoneId);
            // 지역 밖 이웃(중심점을 모름)은 그 방향 짧은 화살표로 그린다
            const points = to ? neighborPoints(from, to) : outsidePoints(from, flow.direction);
            // 굵기 = 최소 + 비율 * 추가분
            const weight = MIN_WEIGHT + MAX_EXTRA_WEIGHT * (flow.count / max);
            const isNeighbor = flow.toZoneId !== null;
            // 이웃은 실선, 범위 밖은 점선 '6 6'
            const style = {
                color: isNeighbor ? NEIGHBOR_COLOR : OUTSIDE_COLOR,
                weight,
                opacity: 0.9,
                dashArray: isNeighbor ? null : '6 6',
            };
            // 선은 지도 좌표(위경도)로 바꿔 그린다. 계산은 화면 픽셀로 했기 때문이다
            const line = L.polyline(points.map(toLatLng), style);
            // 화살촉은 선 끝에 붙는 채운 삼각형(점선·굵기는 따로)
            const head = L.polygon(arrowHead(points[0], points[1], weight).map(toLatLng), {
                ...style,
                dashArray: null,
                fillColor: style.color,
                fillOpacity: 0.9,
                weight: 1,
            });
            // 선·화살촉 모두 마우스를 올리면 같은 설명을 보인다
            const label = tooltip(from, to, flow);
            line.bindTooltip(label, { sticky: true });
            head.bindTooltip(label, { sticky: true });
            group.addLayer(line);
            group.addLayer(head);
        });
    }

    // 구역 중심 위경도 -> 지도 위의 픽셀 위치
    function point(zone) {
        return map.latLngToLayerPoint(L.latLng(Number(zone.centerLat), Number(zone.centerLng)));
    }

    // 픽셀 위치 -> 위경도
    function toLatLng(layerPoint) {
        return map.layerPointToLatLng(layerPoint);
    }

    // 두 중심점을 잇되 양끝을 조금 줄이고, 진행 방향 오른쪽으로 비켜 반대 방향 화살표와 겹치지 않게 한다
    function neighborPoints(from, to) {
        const a = point(from);
        // 두 점 사이 거리가 0이면 나눗셈 오류가 나므로 1로
        const b = point(to);
        const length = a.distanceTo(b) || 1;
        // 진행 방향 단위 벡터
        const unit = L.point((b.x - a.x) / length, (b.y - a.y) / length);
        // 진행 방향의 오른쪽 수직 벡터 x 비킴 거리 -> 반대 방향 화살표와 겹치지 않는다
        const side = L.point(-unit.y * SIDE_OFFSET_PX, unit.x * SIDE_OFFSET_PX);
        // 구역이 가까우면 양끝을 줄이는 길이도 거리의 1/3까지만
        const gap = Math.min(END_GAP_PX, length / 3);
        return [a.add(unit.multiplyBy(gap)).add(side), b.subtract(unit.multiplyBy(gap)).add(side)];
    }

    function outsidePoints(from, direction) {
        // 관측 범위 밖: 구역 중심에서 그 방향으로 짧게 나가는 화살표
        const a = point(from);
        // 알 수 없는 방향이면 북쪽으로
        const [dx, dy] = DIRECTION_VECTORS[direction] ?? [0, -1];
        const unit = L.point(dx, dy);
        return [
            a.add(unit.multiplyBy(END_GAP_PX)),
            a.add(unit.multiplyBy(END_GAP_PX + OUTSIDE_LENGTH_PX)),
        ];
    }

    return { show, clear };
}

// 끝점에 붙는 삼각형 화살촉(굵을수록 조금 크게)
function arrowHead(start, end, weight) {
    // 끝점(end)에서 되돌아간 점 back을 기준으로 좌우 두 점을 잡아 삼각형을 만든다
    const length = start.distanceTo(end) || 1;
    const unit = L.point((end.x - start.x) / length, (end.y - start.y) / length);
    // 화살촉 크기는 선이 굵을수록 조금 커진다
    const size = HEAD_PX + weight;
    const back = end.subtract(unit.multiplyBy(size));
    const side = L.point(-unit.y * size * 0.55, unit.x * size * 0.55);
    return [end, back.add(side), back.subtract(side)];
}

// '사직동 광장 → 사직동 공원 입구 · 340명(추정)', 관측 범위 밖은 '→ 관측 범위 밖(동쪽)'
function tooltip(from, to, flow) {
    // 툴팁 내용을 DOM으로 만든다(구역 이름은 서버 값이므로 textContent로 넣는다)
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
