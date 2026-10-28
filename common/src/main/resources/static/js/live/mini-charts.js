// 캠 카드(SCR-C-003)와 실시간 보기 창(SCR-A-008)의 작은 그래프(결정 0016). 대시보드는 Chart.js를 쓰지 않으므로 SVG로 그린다.
// 막대 색은 서버가 밀집도로 판정한 level(FNC-C-025)이고, 화면에서 단계를 다시 판정하지 않는다.
// 축 글자는 SVG 밖(HTML)에 두고, SVG는 칸 크기에 맞춰 늘린다(preserveAspectRatio=none).

const SVG_NS = 'http://www.w3.org/2000/svg';
const WIDTH = 400;
const LEVEL_COLORS = { 1: '#34a37f', 2: '#c98a1c', 3: '#d9475a' };
const EMPTY_COLOR = '#253242';
const NOW_COLOR = '#818cf8';
const COUNT_COLOR = '#818cf8';
const COUNT_FILL = 'rgba(129, 140, 248, .16)';
const INFLOW_COLOR = '#a5b4fc';
const OUTFLOW_COLOR = '#4f46e5';
const WARN_COLOR = '#c98a1c';
const CONGESTION_FILL = 'rgba(239, 77, 85, .15)';
// 주의 이상(level 2·3)인 분은 인원·유입·유출 칸을 옅은 빨강으로
const CONGESTED_LEVEL = 2;
// 막대 사이 틈(칸 폭에 대한 비율)
const GAP_RATIO = 0.18;

/**
 * 혼잡 단계 색 막대. 값이 없는 분은 빈칸, 마지막 막대(지금)는 인디고.
 * @param {{value: number|null, level: number|null}[]} points 시각 순서
 * @param {{height: number, max: number, warn?: number|null, label: string}} options
 *   max: y축 최대(같은 줄의 그래프끼리 맞출 때 같은 값을 준다), warn: 주의 기준 인원(있으면 점선)
 */
export function levelBars(points, { height, max, warn = null, label }) {
    const svg = svgBox(height, label);
    const step = WIDTH / Math.max(points.length, 1);
    points.forEach((point, index) => {
        if (point.value === null || point.value === undefined) {
            return;
        }
        const barHeight = Math.max(1, (point.value / max) * (height - 2));
        const isNow = index === points.length - 1;
        svg.append(rect(index * step + (step * GAP_RATIO) / 2, height - barHeight, step * (1 - GAP_RATIO), barHeight,
            isNow ? NOW_COLOR : LEVEL_COLORS[point.level] ?? EMPTY_COLOR));
    });
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
    const svg = svgBox(height, label);
    const step = WIDTH / Math.max(points.length, 1);
    shadeCongested(svg, points, step, height);
    const xy = points.map((point, index) => (point.value === null || point.value === undefined
        ? null
        : [index * step + step / 2, height - 1 - (point.value / max) * (height - 4)]));
    // 값이 없는 분에서 선을 끊는다
    let run = [];
    const flush = () => {
        if (run.length > 1) {
            const area = document.createElementNS(SVG_NS, 'polygon');
            area.setAttribute('points', [...run, [run[run.length - 1][0], height], [run[0][0], height]].map(pair).join(' '));
            area.setAttribute('fill', COUNT_FILL);
            svg.append(area);
            const polyline = document.createElementNS(SVG_NS, 'polyline');
            polyline.setAttribute('points', run.map(pair).join(' '));
            polyline.setAttribute('fill', 'none');
            polyline.setAttribute('stroke', COUNT_COLOR);
            polyline.setAttribute('stroke-width', '2');
            polyline.setAttribute('vector-effect', 'non-scaling-stroke');
            svg.append(polyline);
        }
        run = [];
    };
    xy.forEach((point) => (point ? run.push(point) : flush()));
    flush();
    return svg;
}

/**
 * 유입(가운데 선 위)·유출(아래) 막대.
 * @param {{inflow: number|null, outflow: number|null, level: number|null}[]} points
 * @param {{height: number, label: string}} options
 */
export function flowBars(points, { height, label }) {
    const svg = svgBox(height, label);
    const step = WIDTH / Math.max(points.length, 1);
    shadeCongested(svg, points, step, height);
    const max = Math.max(1, ...points.flatMap((point) => [point.inflow ?? 0, point.outflow ?? 0]));
    const middle = height / 2;
    points.forEach((point, index) => {
        const x = index * step + (step * GAP_RATIO) / 2;
        const width = step * (1 - GAP_RATIO);
        if (point.inflow) {
            const barHeight = (point.inflow / max) * (middle - 1);
            svg.append(rect(x, middle - barHeight, width, barHeight, INFLOW_COLOR));
        }
        if (point.outflow) {
            svg.append(rect(x, middle, width, (point.outflow / max) * (middle - 1), OUTFLOW_COLOR));
        }
    });
    svg.append(line(0, middle, WIDTH, middle, EMPTY_COLOR));
    return svg;
}

/** 막대 높이를 맞출 y축 최대: 값 최대를 보기 좋은 수로 올린다(최소 1) */
export function niceMax(values) {
    const max = Math.max(0, ...values.filter((value) => value !== null && value !== undefined));
    if (max <= 0) {
        return 1;
    }
    const magnitude = 10 ** Math.floor(Math.log10(max));
    const scaled = max / magnitude;
    const nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
    return nice * magnitude;
}

/** 주의 이상인지(인원·유입·유출 칸의 옅은 빨강) */
export function isCongested(level) {
    return level !== null && level !== undefined && level >= CONGESTED_LEVEL;
}

function shadeCongested(svg, points, step, height) {
    points.forEach((point, index) => {
        if (isCongested(point.level)) {
            svg.append(rect(index * step, 0, step, height, CONGESTION_FILL));
        }
    });
}

function svgBox(height, label) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${WIDTH} ${height}`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', label);
    svg.style.height = `${height}px`;
    svg.style.width = '100%';
    svg.style.display = 'block';
    return svg;
}

function rect(x, y, width, height, fill) {
    const element = document.createElementNS(SVG_NS, 'rect');
    element.setAttribute('x', x.toFixed(1));
    element.setAttribute('y', y.toFixed(1));
    element.setAttribute('width', Math.max(0, width).toFixed(1));
    element.setAttribute('height', Math.max(0, height).toFixed(1));
    element.setAttribute('rx', '1.5');
    element.setAttribute('fill', fill);
    return element;
}

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

const pair = ([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`;
