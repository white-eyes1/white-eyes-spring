// 캠 카드(SCR-C-003)와 실시간 보기 창(SCR-A-008)의 작은 그래프(결정 0016). 대시보드는 Chart.js를 쓰지 않으므로 SVG로 그린다.
// 막대 색은 서버가 밀집도로 판정한 level(FNC-C-025)이고, 화면에서 단계를 다시 판정하지 않는다.
// 축 글자는 SVG 밖(HTML)에 두고, SVG는 칸 크기에 맞춰 늘린다(preserveAspectRatio=none).

// SVG 요소는 일반 createElement가 아니라 이 네임스페이스로 만들어야 한다
const SVG_NS = 'http://www.w3.org/2000/svg';
// SVG 안의 가상 가로 폭. 실제 화면 폭에는 preserveAspectRatio=none으로 늘려 맞춘다
const WIDTH = 400;
// 혼잡 단계 색(1 정상, 2 주의, 3 위험). 단계 판정은 서버가 하고 화면은 색만 입힌다
const LEVEL_COLORS = { 1: '#34a37f', 2: '#c98a1c', 3: '#d9475a' };
// 단계가 없는 점·보조선 색
const EMPTY_COLOR = '#253242';
// 지금(마지막으로 값이 있는 분)을 가리키는 막대 색
const NOW_COLOR = '#818cf8';
const COUNT_COLOR = '#818cf8';
const COUNT_FILL = 'rgba(129, 140, 248, .16)';
const INFLOW_COLOR = '#a5b4fc';
const OUTFLOW_COLOR = '#4f46e5';
// 주의 기준선 색
const WARN_COLOR = '#c98a1c';
const CONGESTION_FILL = 'rgba(239, 77, 85, .15)';
// 주의 이상(level 2·3)인 분은 인원·유입·유출 칸을 옅은 빨강으로
const CONGESTED_LEVEL = 2;
// 막대 사이 틈(칸 폭에 대한 비율)
const GAP_RATIO = 0.18;

/**
 * 혼잡 단계 색 막대. 값이 없는 분은 빈칸, 값이 있는 마지막 분(지금)은 인디고(아직 집계 전인 지금 분은 값이 없어 건너뛴다).
 * @param {{value: number|null, level: number|null}[]} points 시각 순서
 * @param {{height: number, max: number, warn?: number|null, label: string}} options
 *   max: y축 최대(같은 줄의 그래프끼리 맞출 때 같은 값을 준다), warn: 주의 기준 인원(있으면 점선)
 */
export function levelBars(points, { height, max, warn = null, label }) {
    // 그림 틀(svg)을 먼저 만든다
    const svg = svgBox(height, label);
    // 점 하나가 차지하는 가로 폭. 점이 없어도 0으로 나누지 않도록 최소 1
    const step = WIDTH / Math.max(points.length, 1);
    // 값이 있는 마지막 점의 순번. 현재 분은 아직 집계 전이라 값이 없어 건너뛴다
    const nowIndex = points.findLastIndex(
        (point) => point.value !== null && point.value !== undefined,
    );
    points.forEach((point, index) => {
        // 값이 없는 분은 막대를 그리지 않는다(빈칸)
        if (point.value === null || point.value === undefined) {
            return;
        }
        // 막대 높이는 max 대비 비율. 값이 아주 작아도 보이게 최소 1px
        const barHeight = Math.max(1, (point.value / max) * (height - 2));
        const isNow = index === nowIndex;
        // 마지막 점은 지금이라는 뜻의 인디고, 나머지는 서버가 준 단계 색
        svg.append(
            rect(
                index * step + (step * GAP_RATIO) / 2,
                height - barHeight,
                step * (1 - GAP_RATIO),
                barHeight,
                isNow ? NOW_COLOR : (LEVEL_COLORS[point.level] ?? EMPTY_COLOR),
            ),
        );
    });
    // 주의 기준 인원이 그래프 범위 안에 있을 때만 점선을 긋는다
    if (warn !== null && warn > 0 && warn <= max) {
        const y = height - (warn / max) * (height - 2);
        svg.append(line(0, y, WIDTH, y, WARN_COLOR, '4 3'));
    }
    return svg;
}

/**
 * 인원 선(아래 옅게 채움). 주의 이상인 분은 배경을 옅은 빨강으로.
 * @param {{value: number|null, level: number|null}[]} points
 * @param {{height: number, max: number, label: string}} options
 */
export function countLine(points, { height, max, label }) {
    // 틀을 만들고 주의 이상인 분의 배경을 옅게 칠한 뒤 선을 그린다(배경이 선보다 뒤에 와야 한다)
    const svg = svgBox(height, label);
    const step = WIDTH / Math.max(points.length, 1);
    shadeCongested(svg, points, step, height);
    // 각 점의 SVG 좌표 [x, y]. 값이 없으면 null(선을 끊는 표시). y는 위아래가 뒤집혀 있어 height에서 뺀다
    const xy = points.map((point, index) =>
        point.value === null || point.value === undefined
            ? null
            : [index * step + step / 2, height - 1 - (point.value / max) * (height - 4)],
    );
    // 값이 없는 분에서 선을 끊는다
    let run = [];
    // flush: 지금까지 모은 연속 구간(run)을 영역+선으로 그리고 비운다
    const flush = () => {
        // 점이 하나뿐이면 선이 안 되므로 그리지 않는다
        if (run.length > 1) {
            // 선 아래 옅은 영역: 선의 점들 + 바닥 오른쪽 + 바닥 왼쪽을 이어 닫힌 다각형으로
            const area = document.createElementNS(SVG_NS, 'polygon');
            area.setAttribute(
                'points',
                [...run, [run[run.length - 1][0], height], [run[0][0], height]].map(pair).join(' '),
            );
            area.setAttribute('fill', COUNT_FILL);
            svg.append(area);
            const polyline = document.createElementNS(SVG_NS, 'polyline');
            polyline.setAttribute('points', run.map(pair).join(' '));
            polyline.setAttribute('fill', 'none');
            polyline.setAttribute('stroke', COUNT_COLOR);
            polyline.setAttribute('stroke-width', '2');
            // 화면 비율에 따라 SVG가 늘어나도 선 굵기는 그대로 둔다
            polyline.setAttribute('vector-effect', 'non-scaling-stroke');
            svg.append(polyline);
        }
        run = [];
    };
    // 값이 있으면 현재 구간에 모으고, 없으면 지금까지의 구간을 그린다
    xy.forEach((point) => (point ? run.push(point) : flush()));
    // 마지막 구간도 그린다
    flush();
    return svg;
}

/**
 * 유입(가운데 선 위)·유출(아래) 막대.
 * @param {{inflow: number|null, outflow: number|null, level: number|null}[]} points
 * @param {{height: number, label: string}} options
 */
export function flowBars(points, { height, label }) {
    // 유입은 가운데 선 위로, 유출은 아래로 그린다. 같은 줄에서 비교하기 쉽게 하나의 최대값에 맞춘다
    const svg = svgBox(height, label);
    const step = WIDTH / Math.max(points.length, 1);
    shadeCongested(svg, points, step, height);
    // 유입·유출 중 가장 큰 값을 높이 기준으로. 모두 0이거나 없으면 1(0으로 나누기 방지)
    const max = Math.max(1, ...points.flatMap((point) => [point.inflow ?? 0, point.outflow ?? 0]));
    // 가운데 기준선의 y 좌표
    const middle = height / 2;
    points.forEach((point, index) => {
        const x = index * step + (step * GAP_RATIO) / 2;
        const width = step * (1 - GAP_RATIO);
        // 유입 막대: 가운데에서 위로 올라간다
        if (point.inflow) {
            const barHeight = (point.inflow / max) * (middle - 1);
            svg.append(rect(x, middle - barHeight, width, barHeight, INFLOW_COLOR));
        }
        // 유출 막대: 가운데에서 아래로 내려간다
        if (point.outflow) {
            svg.append(rect(x, middle, width, (point.outflow / max) * (middle - 1), OUTFLOW_COLOR));
        }
    });
    // 가운데 기준선
    svg.append(line(0, middle, WIDTH, middle, EMPTY_COLOR));
    return svg;
}

/** 막대 높이를 맞출 y축 최대: 값 최대를 보기 좋은 수로 올린다(최소 1) */
export function niceMax(values) {
    // 값 중 가장 큰 수(없으면 0)를 1·2·5·10의 배수로 올린다
    const max = Math.max(0, ...values.filter((value) => value !== null && value !== undefined));
    // 값이 전혀 없으면 눈금 범위를 1로
    if (max <= 0) {
        return 1;
    }
    // 자릿수(10의 거듭제곱): 예 340 -> 100
    const magnitude = 10 ** Math.floor(Math.log10(max));
    // 자릿수로 나눈 값: 예 3.4
    const scaled = max / magnitude;
    // 1, 2, 5, 10 중 scaled 이상인 가장 작은 수 -> 340은 5*100=500
    const nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
    return nice * magnitude;
}

/** 주의 이상인지(인원·유입·유출 칸의 옅은 빨강) */
// level이 주의(2) 이상이면 true. level이 없는 점은 false
export function isCongested(level) {
    return level !== null && level !== undefined && level >= CONGESTED_LEVEL;
}

// 주의 이상인 분의 칸 전체를 옅은 빨강으로 칠한다(점 하나의 폭 = step)
function shadeCongested(svg, points, step, height) {
    points.forEach((point, index) => {
        if (isCongested(point.level)) {
            svg.append(rect(index * step, 0, step, height, CONGESTION_FILL));
        }
    });
}

// SVG 틀. viewBox 가로는 고정값이고 높이는 호출한 쪽이 정한다
function svgBox(height, label) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${WIDTH} ${height}`);
    // 가로·세로 비율을 유지하지 않고 칸 크기에 맞춰 늘린다
    svg.setAttribute('preserveAspectRatio', 'none');
    // 접근성: 그림 설명을 aria-label로 준다
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', label);
    svg.style.height = `${height}px`;
    svg.style.width = '100%';
    svg.style.display = 'block';
    return svg;
}

// 사각형 하나. 좌표는 소수 1자리로 줄여 SVG 문서 크기를 줄인다
function rect(x, y, width, height, fill) {
    const element = document.createElementNS(SVG_NS, 'rect');
    element.setAttribute('x', x.toFixed(1));
    element.setAttribute('y', y.toFixed(1));
    // 폭·높이는 음수가 되면 SVG 오류이므로 0으로 막는다
    element.setAttribute('width', Math.max(0, width).toFixed(1));
    element.setAttribute('height', Math.max(0, height).toFixed(1));
    element.setAttribute('rx', '1.5');
    element.setAttribute('fill', fill);
    return element;
}

// 선 하나. dash가 있으면 점선
function line(x1, y1, x2, y2, stroke, dash) {
    const element = document.createElementNS(SVG_NS, 'line');
    element.setAttribute('x1', String(x1));
    element.setAttribute('y1', y1.toFixed(1));
    element.setAttribute('x2', String(x2));
    element.setAttribute('y2', y2.toFixed(1));
    element.setAttribute('stroke', stroke);
    element.setAttribute('stroke-width', '1');
    element.setAttribute('vector-effect', 'non-scaling-stroke');
    if (dash) {
        element.setAttribute('stroke-dasharray', dash);
    }
    return element;
}

// [x, y] -> 'x,y' 글자(SVG points 속성용)
const pair = ([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`;
