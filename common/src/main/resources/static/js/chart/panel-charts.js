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

// 혼잡 단계 번호 -> 이름. 툴팁에 쓴다
const LEVEL_NAMES = { 1: '정상', 2: '주의', 3: '위험' };
// 주의 이상(level 2·3)인 점은 인원·유입·유출 칸을 옅은 빨강으로
const CONGESTED_LEVEL = 2;
// 방향 코드(서버의 N·E·S·W)와 화면 이름
const DIRECTIONS = [
    ['N', '북'],
    ['E', '동'],
    ['S', '남'],
    ['W', '서'],
];

/**
 * @param {{level: HTMLCanvasElement, count: HTMLCanvasElement, flow: HTMLCanvasElement, direction: HTMLCanvasElement}} canvases
 */
// canvases의 네 칸 각각에 Chart.js 그래프를 하나씩 그린다. 다시 그릴 때는 update만 부른다
export function createPanelCharts(canvases) {
    // 칸 이름 -> Chart 인스턴스. 다시 그리기 전에 지우려고 보관한다
    const charts = {};

    /**
     * @param {string[]} labels x축 글자(통합 그래프와 같음)
     * @param {string[]} titles 툴팁 제목
     * @param {object[]} points GET /api/v1/stats/series의 items
     */
    // 반환값: 방향별 유입·유출 합이 하나라도 있으면 true(방향 칸에 보여 줄 데이터가 있는지)
    function update(labels, titles, points) {
        // 시점마다의 값을 뽑는다. 값이 없는 점은 null이라 선이 끊기고 막대는 비어 보인다
        const counts = points.map((point) => numberOrNull(point.countAvg));
        const levels = points.map((point) => point.level ?? null);
        // 서버가 판정한 혼잡 단계(FNC-C-025). 없으면 null
        const inflow = points.map((point) => flowSum(point.inflow));
        const outflow = points.map((point) => flowSum(point.outflow));
        // 주의(2) 이상인 점은 뒤 칸을 붉게 칠할 때 쓴다
        const congested = levels.map((level) => level !== null && level >= CONGESTED_LEVEL);

        // 1) 시간별 인원 막대: 막대 색은 혼잡 단계 색
        draw('level', {
            type: 'bar',
            data: {
                labels,
                datasets: [
                    {
                        data: counts,
                        // 단계를 모르는 점은 격자 색(눈에 띄지 않는 회색)으로 칠한다
                        backgroundColor: levels.map((level) => LEVEL_COLORS[level] ?? GRID_COLOR),
                        borderRadius: 3,
                        maxBarThickness: 22,
                    },
                ],
            },
            options: options(
                titles,
                (context) => {
                    const level = levels[context.dataIndex];
                    return `인원 ${countText(context.parsed.y)} · ${LEVEL_NAMES[level] ?? '단계 없음'}`;
                },
                { yTitle: '명' },
            ),
        });

        // 2) 인원 선 그래프(가로축 글자는 아래 유입·유출 칸과 겹치므로 숨김)
        draw('count', {
            type: 'line',
            data: {
                labels,
                datasets: [
                    {
                        data: counts,
                        borderColor: COUNT_COLOR,
                        backgroundColor: COUNT_FILL,
                        fill: true,
                        borderWidth: 3,
                        pointRadius: 0,
                        pointHoverRadius: 4,
                        // 값이 없는 점에서 선을 잇지 않는다
                        spanGaps: false,
                        tension: 0.2,
                    },
                ],
            },
            options: options(titles, (context) => `인원 평균 ${countText(context.parsed.y)}`, {
                yTitle: '명',
                hideX: true,
            }),
            plugins: [shadePlugin(congested, CONGESTION_FILL)],
        });

        // 3) 유입(위쪽)·유출(아래쪽) 막대: 같은 stack에 유출을 음수로 넣어 0 기준선 아래로 내린다
        draw('flow', {
            type: 'bar',
            data: {
                labels,
                datasets: [
                    {
                        label: '유입',
                        data: inflow,
                        backgroundColor: INFLOW_COLOR,
                        stack: 'flow',
                        borderRadius: 2,
                        maxBarThickness: 22,
                    },
                    {
                        label: '유출',
                        // 유출은 음수로 바꿔 그린다. 눈금과 툴팁은 absoluteTicks·Math.abs로 다시 양수로 보여 준다
                        data: outflow.map((value) => (value === null ? null : -value)),
                        backgroundColor: OUTFLOW_COLOR,
                        stack: 'flow',
                        borderRadius: 2,
                        maxBarThickness: 22,
                    },
                ],
            },
            options: options(
                titles,
                (context) => `${context.dataset.label} ${countText(Math.abs(context.parsed.y))}`,
                {
                    yTitle: '명',
                    stacked: true,
                    absoluteTicks: true,
                },
            ),
            plugins: [shadePlugin(congested, CONGESTION_FILL)],
        });

        // 4) 방향별 합계 가로 막대. 구간 전체의 방향별 유입·유출을 더한다. totals[i] = [유입합, 유출합]
        const totals = DIRECTIONS.map(([key]) => [
            directionSum(points, 'inflow', key),
            directionSum(points, 'outflow', key),
        ]);
        draw('direction', {
            type: 'bar',
            data: {
                labels: DIRECTIONS.map(([, name]) => name),
                datasets: [
                    {
                        label: '유입',
                        data: totals.map(([sum]) => sum),
                        backgroundColor: INFLOW_COLOR,
                        borderRadius: 3,
                        maxBarThickness: 12,
                    },
                    {
                        label: '유출',
                        data: totals.map(([, sum]) => sum),
                        backgroundColor: OUTFLOW_COLOR,
                        borderRadius: 3,
                        maxBarThickness: 12,
                    },
                ],
            },
            // 공통 옵션을 쓰되 가로 막대(indexAxis y)라서 축 설정은 새로 덮어쓴다
            options: {
                ...options(
                    null,
                    (context) => `${context.dataset.label} ${countText(context.parsed.x)}`,
                    {},
                ),
                indexAxis: 'y',
                scales: {
                    x: {
                        beginAtZero: true,
                        grid: { color: GRID_COLOR },
                        ticks: { color: TICK_COLOR },
                    },
                    y: { grid: { display: false }, ticks: { color: TICK_COLOR } },
                },
            },
        });
        // 네 방향 모두 0이면 false: 호출한 쪽이 '방향 데이터 없음'을 보여 줄 수 있다
        return totals.some(([inSum, outSum]) => inSum > 0 || outSum > 0);
    }

    // 같은 캔버스에 다시 그리기 전에 이전 그래프를 없애야 Chart.js가 캔버스 중복 사용 오류를 내지 않는다
    function draw(key, config) {
        charts[key]?.destroy();
        // window.Chart: graph.html이 불러온 전역 Chart.js
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
            x: {
                display: !hideX,
                stacked,
                grid: { display: false },
                ticks: { color: TICK_COLOR, maxTicksLimit: 12 },
            },
            y: {
                stacked,
                beginAtZero: true,
                grid: { color: GRID_COLOR },
                ticks: {
                    color: TICK_COLOR,
                    ...(absoluteTicks
                        ? { callback: (value) => formatNumber(Math.abs(value)) }
                        : {}),
                },
                title: { display: Boolean(yTitle), text: yTitle, color: TICK_COLOR },
            },
        },
    };
}

function numberOrNull(value) {
    // 서버 값이 null/undefined면 null(선을 끊음), 아니면 숫자
    return value === null || value === undefined ? null : Number(value);
}

// 방향별(N·E·S·W) 합. 값이 없는 점은 null(통합 그래프의 유입·유출 값도 이 함수로 낸다)
export function flowSum(flow) {
    // 유입·유출 객체({N:3,E:1,...})의 값을 모두 더한다
    return flow ? Object.values(flow).reduce((sum, value) => sum + value, 0) : null;
}

// 구간 전체의 한 방향 합계. 값이 없는 점은 0으로 본다
function directionSum(points, field, direction) {
    return points.reduce((sum, point) => sum + (point[field]?.[direction] ?? 0), 0);
}

// 툴팁용 '1,234명' 글자. 소수 평균은 반올림한다
function countText(value) {
    return value === null || value === undefined
        ? '데이터 없음'
        : `${formatNumber(Math.round(value))}명`;
}
