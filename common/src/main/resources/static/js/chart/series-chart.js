// 통계 시계열 선 그래프(SCR-C-004, FNC-C-012). Chart.js(전역 Chart, graph.html이 불러옴) 코드는 이 파일에만 둔다.
// 값이 없는 점(sampleSec=0·통계 없음)은 null이라 선이 끊긴다(spanGaps 끔).
import { formatDecimal, formatNumber } from '../common/format.js';

const GRID_COLOR = '#1e2a38';
const TICK_COLOR = '#8b98a9';

/** 항목 정의. axis: count(명) | density(명/㎡) | dwell(초). 색·선 모양은 시안(인원 파랑 실선, 밀집도 주황 점선)을 따른다 */
export const SERIES_ITEMS = {
    countAvg: { label: '인원 평균', axis: 'count', color: '#4f8cff', width: 3, unit: '명' },
    countMax: { label: '인원 최대', axis: 'count', color: '#9cc0ff', width: 1.5, unit: '명' },
    countMin: { label: '인원 최소', axis: 'count', color: '#5d6f88', width: 1.5, unit: '명' },
    density: { label: '밀집도', axis: 'density', color: '#f59e0b', width: 2, dash: [6, 5], unit: '명/㎡', decimals: 2 },
    inflow: { label: '유입', axis: 'count', color: '#3bc99b', width: 2, unit: '명' },
    outflow: { label: '유출', axis: 'count', color: '#ef4d55', width: 2, unit: '명' },
    dwellAvgSec: { label: '평균 체류', axis: 'dwell', color: '#a78bfa', width: 2, unit: '초', decimals: 1 },
};

const AXES = {
    count: { position: 'left', title: '명' },
    density: { position: 'right', title: '명/㎡' },
    dwell: { position: 'right', title: '초' },
};

/**
 * 그래프를 만든다. 다시 그릴 때는 돌려받은 update를 부른다.
 * @param {HTMLCanvasElement} canvas
 */
export function createSeriesChart(canvas) {
    let chart = null;

    /**
     * @param {string[]} labels x축 글자(점마다 하나)
     * @param {string[]} titles 툴팁 제목(점마다 하나, 예: '17:00 ~ 18:00')
     * @param {Object<string, (number|null)[]>} valuesByItem 고른 항목만, SERIES_ITEMS의 키 → 점마다 값
     */
    function update(labels, titles, valuesByItem) {
        const keys = Object.keys(valuesByItem);
        const usedAxes = new Set(keys.map((key) => SERIES_ITEMS[key].axis));
        const datasets = keys.map((key) => {
            const item = SERIES_ITEMS[key];
            return {
                label: item.label,
                data: valuesByItem[key],
                yAxisID: item.axis,
                borderColor: item.color,
                backgroundColor: item.color,
                borderWidth: item.width,
                borderDash: item.dash ?? [],
                pointRadius: 0,
                pointHoverRadius: 5,
                spanGaps: false,
                tension: 0.2,
            };
        });
        const scales = { x: { grid: { color: GRID_COLOR }, ticks: { color: TICK_COLOR, maxTicksLimit: 12 } } };
        Object.entries(AXES).forEach(([id, axis]) => {
            scales[id] = {
                display: usedAxes.has(id),
                position: axis.position,
                beginAtZero: true,
                grid: { color: GRID_COLOR, drawOnChartArea: id === 'count' || !usedAxes.has('count') },
                ticks: { color: TICK_COLOR },
                title: { display: true, text: axis.title, color: TICK_COLOR },
            };
        });

        const config = {
            type: 'line',
            data: { labels, datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                interaction: { mode: 'index', intersect: false },
                scales,
                plugins: {
                    legend: { position: 'bottom', align: 'start', labels: { color: TICK_COLOR, boxWidth: 10, boxHeight: 10 } },
                    tooltip: {
                        callbacks: {
                            title: (contexts) => titles[contexts[0].dataIndex],
                            label: (context) => tooltipLine(keys[context.datasetIndex], context.parsed.y),
                        },
                    },
                },
            },
        };
        if (chart) {
            chart.destroy();
        }
        chart = new window.Chart(canvas, config);
    }

    return { update };
}

// '인원 평균 1,204명', 값이 없으면 '데이터 없음'
function tooltipLine(key, value) {
    const item = SERIES_ITEMS[key];
    if (value === null || value === undefined) {
        return `${item.label} 데이터 없음`;
    }
    const text = item.decimals ? formatDecimal(value, item.decimals) : formatNumber(Math.round(value));
    return `${item.label} ${text}${item.unit}`;
}
