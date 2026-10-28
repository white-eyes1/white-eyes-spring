// 통계 그래프 아래 세 칸(SCR-C-004, 결정 0016): 시간별 인원·혼잡 단계 막대, 인원·유입·유출, 방향별 흐름.
// 통합 그래프와 같은 응답(GET /api/v1/stats/series)·같은 구간을 받아 그린다. Chart.js 코드는 js/chart/ 안에만 둔다.
import { formatNumber } from '../common/format.js';
import {
    CONGESTION_FILL,
    COUNT_COLOR,
    COUNT_FILL,
    GRID_COLOR,
    INFLOW_COLOR,
    LEVEL_COLORS,
    OUTFLOW_COLOR,
    TICK_COLOR,
    shadePlugin,
} from './chart-theme.js';

const LEVEL_NAMES = { 1: '정상', 2: '주의', 3: '위험' };
// 주의 이상(level 2·3)인 점은 인원·유입·유출 칸을 옅은 빨강으로
const CONGESTED_LEVEL = 2;
const DIRECTIONS = [
    ['N', '북'],
    ['E', '동'],
    ['S', '남'],
    ['W', '서'],
];

/**
 * @param {{level: HTMLCanvasElement, count: HTMLCanvasElement, flow: HTMLCanvasElement, direction: HTMLCanvasElement}} canvases
 */
export function createPanelCharts(canvases) {
    const charts = {};

    /**
     * @param {string[]} labels x축 글자(통합 그래프와 같음)
     * @param {string[]} titles 툴팁 제목
     * @param {object[]} points GET /api/v1/stats/series의 items
     */
    function update(labels, titles, points) {
        const counts = points.map((point) => numberOrNull(point.countAvg));
        const levels = points.map((point) => point.level ?? null);
        const inflow = points.map((point) => flowSum(point.inflow));
        const outflow = points.map((point) => flowSum(point.outflow));
        const congested = levels.map((level) => level !== null && level >= CONGESTED_LEVEL);

        draw('level', {
            type: 'bar',
            data: {
                labels,
                datasets: [{
                    data: counts,
                    backgroundColor: levels.map((level) => LEVEL_COLORS[level] ?? GRID_COLOR),
                    borderRadius: 3,
                    maxBarThickness: 22,
                }],
            },
            options: options(titles, (context) => {
                const level = levels[context.dataIndex];
                return `인원 ${countText(context.parsed.y)} · ${LEVEL_NAMES[level] ?? '단계 없음'}`;
            }, { yTitle: '명' }),
        });

        draw('count', {
            type: 'line',
            data: {
                labels,
                datasets: [{
                    data: counts,
                    borderColor: COUNT_COLOR,
                    backgroundColor: COUNT_FILL,
                    fill: true,
                    borderWidth: 3,
                    pointRadius: 0,
                    pointHoverRadius: 4,
                    spanGaps: false,
                    tension: 0.2,
                }],
            },
            options: options(titles, (context) => `인원 평균 ${countText(context.parsed.y)}`, { yTitle: '명', hideX: true }),
            plugins: [shadePlugin(congested, CONGESTION_FILL)],
        });

        draw('flow', {
            type: 'bar',
            data: {
                labels,
                datasets: [
                    { label: '유입', data: inflow, backgroundColor: INFLOW_COLOR, stack: 'flow', borderRadius: 2, maxBarThickness: 22 },
                    {
                        label: '유출',
                        data: outflow.map((value) => (value === null ? null : -value)),
                        backgroundColor: OUTFLOW_COLOR,
                        stack: 'flow',
                        borderRadius: 2,
                        maxBarThickness: 22,
                    },
                ],
            },
            options: options(titles, (context) => `${context.dataset.label} ${countText(Math.abs(context.parsed.y))}`, {
                yTitle: '명',
                stacked: true,
                absoluteTicks: true,
            }),
            plugins: [shadePlugin(congested, CONGESTION_FILL)],
        });

        const totals = DIRECTIONS.map(([key]) => [directionSum(points, 'inflow', key), directionSum(points, 'outflow', key)]);
        draw('direction', {
            type: 'bar',
            data: {
                labels: DIRECTIONS.map(([, name]) => name),
                datasets: [
                    { label: '유입', data: totals.map(([sum]) => sum), backgroundColor: INFLOW_COLOR, borderRadius: 3, maxBarThickness: 12 },
                    { label: '유출', data: totals.map(([, sum]) => sum), backgroundColor: OUTFLOW_COLOR, borderRadius: 3, maxBarThickness: 12 },
                ],
            },
            options: {
                ...options(null, (context) => `${context.dataset.label} ${countText(context.parsed.x)}`, {}),
                indexAxis: 'y',
                scales: {
                    x: { beginAtZero: true, grid: { color: GRID_COLOR }, ticks: { color: TICK_COLOR } },
                    y: { grid: { display: false }, ticks: { color: TICK_COLOR } },
                },
            },
        });
        return totals.some(([inSum, outSum]) => inSum > 0 || outSum > 0);
    }

    function draw(key, config) {
        charts[key]?.destroy();
        charts[key] = new window.Chart(canvases[key], config);
    }

    return { update };
}

// 공통 옵션: 범례 없음(칸 머리에 범례), 같은 x 위치 툴팁, 애니메이션 끔
function options(titles, label, { yTitle, hideX = false, stacked = false, absoluteTicks = false }) {
    return {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
            legend: { display: false },
            tooltip: {
                callbacks: {
                    ...(titles ? { title: (contexts) => titles[contexts[0].dataIndex] } : {}),
                    label,
                },
            },
        },
        scales: {
            x: { display: !hideX, stacked, grid: { display: false }, ticks: { color: TICK_COLOR, maxTicksLimit: 12 } },
            y: {
                stacked,
                beginAtZero: true,
                grid: { color: GRID_COLOR },
                ticks: { color: TICK_COLOR, ...(absoluteTicks ? { callback: (value) => formatNumber(Math.abs(value)) } : {}) },
                title: { display: Boolean(yTitle), text: yTitle, color: TICK_COLOR },
            },
        },
    };
}

function numberOrNull(value) {
    return value === null || value === undefined ? null : Number(value);
}

// 방향별(N·E·S·W) 합. 값이 없는 점은 null
function flowSum(flow) {
    return flow ? Object.values(flow).reduce((sum, value) => sum + value, 0) : null;
}

function directionSum(points, field, direction) {
    return points.reduce((sum, point) => sum + (point[field]?.[direction] ?? 0), 0);
}

function countText(value) {
    return value === null || value === undefined ? '데이터 없음' : `${formatNumber(Math.round(value))}명`;
}
