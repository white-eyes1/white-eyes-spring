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
// levelBarsRich: 위쪽에 지금 ▼ 자리, 눈금선·추세선·호버 색
const RICH_TOP = 13;
const RICH_BOTTOM = 2;
const GRID_COLOR = '#253242';
const HOVER_FILL = 'rgba(255, 255, 255, .07)';
const TREND_COLOR = '#76a7ff';
const DEFAULT_LEVEL_NAMES = { 1: '정상', 2: '주의', 3: '위험' };

/**
 * 혼잡 단계 색 막대(둥근 막대 + 점선 추세선 + 지금 ▼ + 눈금 + 호버 말풍선). 캠 카드와 실시간 보기 창이 쓴다.
 * 값이 없는 분은 빈칸이고 추세선이 끊긴다. 값이 있는 마지막 분(지금)은 인디고(아직 집계 전인 지금 분은 값이 없어 건너뛴다).
 * 막대 색은 서버가 판정한 level이다. 서버 값은 textContent로만 넣는다.
 * @param {{value: number|null, level: number|null, at?: string|null}[]} points 시각 순서
 * @param {{height: number, max: number, warn?: number|null, label: string, scale?: boolean,
 *   levelNames?: Record<number, string>}} options
 *   max: y축 최대(같은 줄의 그래프끼리 맞출 때 같은 값을 준다), warn: 주의 기준 인원(있으면 점선),
 *   scale: 눈금선·눈금 숫자(0·중간·최대)를 그릴지(기본 true. 작은 캠 카드는 끈다)
 * @returns {HTMLDivElement} 막대 SVG, 지금 표시, 말풍선을 담은 상자
 */
export function levelBarsRich(
    points,
    { height, max, warn = null, label, scale = true, levelNames = DEFAULT_LEVEL_NAMES },
) {
    const wrap = document.createElement('div');
    wrap.className = 'rbars';
    const plotHeight = height - RICH_TOP - RICH_BOTTOM;
    const svg = svgBox(height, label);
    svg.style.overflow = 'visible';
    const step = WIDTH / Math.max(points.length, 1);
    // 막대가 많으면(1시간 60개) 둥근 정도와 틈을 줄인다
    const dense = points.length > 20;
    const gap = dense ? 0.16 : 0.14;
    const radiusX = dense ? step * 0.3 : step * 0.16;
    const radiusY = dense ? 2 : 3;
    const baseY = RICH_TOP + plotHeight;

    if (scale) {
        [0, 0.5, 1].forEach((ratio) => {
            const y = RICH_TOP + plotHeight * (1 - ratio);
            svg.append(line(0, y, WIDTH, y, GRID_COLOR));
        });
    }
    const highlight = rect(0, RICH_TOP - 2, step, plotHeight + 2, HOVER_FILL);
    highlight.setAttribute('opacity', '0');
    svg.append(highlight);

    const nowIndex = points.findLastIndex(
        (point) => point.value !== null && point.value !== undefined,
    );
    const tops = [];
    points.forEach((point, index) => {
        if (point.value === null || point.value === undefined) {
            tops.push(null);
            return;
        }
        const barHeight = Math.max(2, (point.value / max) * plotHeight);
        const x = index * step + step * gap;
        const width = step * (1 - gap * 2);
        const bar = document.createElementNS(SVG_NS, 'path');
        bar.setAttribute('d', roundTop(x, baseY - barHeight, width, barHeight, radiusX, radiusY));
        bar.setAttribute(
            'fill',
            index === nowIndex ? NOW_COLOR : (LEVEL_COLORS[point.level] ?? EMPTY_COLOR),
        );
        svg.append(bar);
        tops.push([index * step + step / 2, baseY - barHeight]);
    });
    if (warn !== null && warn > 0 && warn <= max) {
        const y = baseY - (warn / max) * plotHeight;
        svg.append(line(0, y, WIDTH, y, WARN_COLOR, '4 3'));
    }
    // 값이 없는 분에서 추세선을 끊는다
    let run = [];
    const flushTrend = () => {
        if (run.length > 1) {
            const trend = document.createElementNS(SVG_NS, 'path');
            trend.setAttribute('d', smoothPath(run));
            trend.setAttribute('fill', 'none');
            trend.setAttribute('stroke', TREND_COLOR);
            trend.setAttribute('stroke-width', '1.6');
            trend.setAttribute('stroke-dasharray', '4 3');
            trend.setAttribute('stroke-linecap', 'round');
            trend.setAttribute('vector-effect', 'non-scaling-stroke');
            svg.append(trend);
        }
        run = [];
    };
    tops.forEach((top) => (top ? run.push(top) : flushTrend()));
    flushTrend();
    wrap.append(svg);

    // 눈금 숫자(0·중간·최대)는 막대 위에 겹쳐 둔다
    if (scale) {
        [0, 0.5, 1].forEach((ratio) => {
            const tick = document.createElement('span');
            tick.className = 'rbars-tick';
            tick.style.top = `${((RICH_TOP + plotHeight * (1 - ratio)) / height) * 100}%`;
            tick.textContent = formatTick(max * ratio);
            wrap.append(tick);
        });
    }
    if (nowIndex >= 0) {
        const mark = document.createElement('span');
        mark.className = 'rbars-now';
        mark.style.left = `${((nowIndex + 0.5) / points.length) * 100}%`;
        wrap.append(mark);
    }

    const tip = document.createElement('div');
    tip.className = 'rbars-tip';
    tip.setAttribute('role', 'tooltip');
    wrap.append(tip);
    const hide = () => {
        highlight.setAttribute('opacity', '0');
        tip.classList.remove('on');
    };
    wrap.addEventListener('pointermove', (event) => {
        const box = wrap.getBoundingClientRect();
        const index = Math.floor(((event.clientX - box.left) / box.width) * points.length);
        const point = points[Math.max(0, Math.min(points.length - 1, index))];
        if (!point || point.value === null || point.value === undefined) {
            hide();
            return;
        }
        const safe = Math.max(0, Math.min(points.length - 1, index));
        highlight.setAttribute('x', (safe * step).toFixed(1));
        highlight.setAttribute('width', step.toFixed(1));
        highlight.setAttribute('opacity', '1');
        fillTip(tip, point, safe === nowIndex, levelNames);
        const center = ((safe + 0.5) / points.length) * box.width;
        tip.style.left = `${Math.max(0, Math.min(box.width - tip.offsetWidth, center - tip.offsetWidth / 2))}px`;
        tip.classList.add('on');
    });
    wrap.addEventListener('pointerleave', hide);
    return wrap;
}

// 말풍선: 시각, 혼잡도(단계 색 점 + 이름), 인원수. 서버 값은 textContent로만 넣는다.
// '지금' 막대는 막대가 인디고라서 단계를 말풍선에서만 알 수 있다. 점은 항상 단계 색이고, 지금은 시각 옆 글자로 알린다.
function fillTip(tip, point, isNow, levelNames) {
    const time = document.createElement('b');
    const clock = point.at ? String(point.at).slice(11, 16) : '-';
    time.textContent = isNow ? `${clock} (지금)` : clock;
    const dot = document.createElement('i');
    dot.style.background = LEVEL_COLORS[point.level] ?? EMPTY_COLOR;
    const levelLine = document.createElement('div');
    levelLine.append('혼잡도 ', dot, levelNames[point.level] ?? '-');
    const countLine = document.createElement('div');
    countLine.textContent = `인원수 ${Math.round(point.value).toLocaleString('ko-KR')}명`;
    tip.replaceChildren(time, levelLine, countLine);
}

function formatTick(value) {
    return Math.round(value).toLocaleString('ko-KR');
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
    const xy = points.map((point, index) =>
        point.value === null || point.value === undefined
            ? null
            : [index * step + step / 2, height - 1 - (point.value / max) * (height - 4)],
    );
    // 값이 없는 분에서 선을 끊는다
    let run = [];
    const flush = () => {
        if (run.length > 1) {
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

// 위쪽만 둥근 막대 길
function roundTop(x, y, width, height, radiusX, radiusY) {
    const rx = Math.min(radiusX, width / 2);
    const ry = Math.min(radiusY, height);
    return `M${x},${y + height}V${y + ry}Q${x},${y} ${x + rx},${y}H${x + width - rx}Q${x + width},${y} ${x + width},${y + ry}V${y + height}Z`;
}

// 점들을 부드럽게 잇는 곡선(Catmull-Rom을 베지어로)
function smoothPath(points) {
    let path = `M${pair(points[0])}`;
    for (let index = 0; index < points.length - 1; index++) {
        const before = points[index - 1] ?? points[index];
        const from = points[index];
        const to = points[index + 1];
        const after = points[index + 2] ?? to;
        const c1 = [from[0] + (to[0] - before[0]) / 6, from[1] + (to[1] - before[1]) / 6];
        const c2 = [to[0] - (after[0] - from[0]) / 6, to[1] - (after[1] - from[1]) / 6];
        path += `C${pair(c1)} ${pair(c2)} ${pair(to)}`;
    }
    return path;
}
