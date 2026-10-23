// 통계 시계열 선 그래프(SCR-C-004, FNC-C-012). Chart.js(전역 Chart, graph.html이 불러옴) 코드는 이 파일에만 둔다.
// 값이 없는 점(sampleSec=0·통계 없음)은 null이라 선과 띠가 끊긴다(spanGaps 끔).
import { formatDecimal, formatNumber } from '../common/format.js';

const GRID_COLOR = '#1e2a38';
const TICK_COLOR = '#8b98a9';

/**
 * 항목 정의. axis: count(명) | density(명/㎡) | dwell(초). 색은 시안(인원 파랑, 밀집도 주황)을 따른다.
 * 점선은 시연용 구간 표시(FNC-C-024)에 쓰므로 항목 선은 모두 실선이다.
 * bandColor가 있는 항목은 최대~최소를 그 색 띠로 함께 그린다(인원, 다시보기 시간축과 같은 표현).
 */
export const SERIES_ITEMS = {
    count: { label: '인원', axis: 'count', color: '#4f8cff', width: 3, unit: '명', bandColor: 'rgba(79, 140, 255, .16)' },
    density: { label: '밀집도', axis: 'density', color: '#f59e0b', width: 2, unit: '명/㎡', decimals: 2 },
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
     * @param {Object<string, {values: (number|null)[], high?: (number|null)[], low?: (number|null)[]}>} seriesByItem
     *     고른 항목만, SERIES_ITEMS의 키 → 점마다 값. 띠를 그리는 항목은 high(최대)·low(최소)도 넘긴다
     */
    function update(labels, titles, seriesByItem) {
        const keys = Object.keys(seriesByItem);
        const usedAxes = new Set(keys.map((key) => SERIES_ITEMS[key].axis));
        const datasets = keys.flatMap((key) => {
            const item = SERIES_ITEMS[key];
            const series = seriesByItem[key];
            const line = {
                itemKey: key,
                label: item.label,
                data: series.values,
                yAxisID: item.axis,
                borderColor: item.color,
                backgroundColor: item.color,
                borderWidth: item.width,
                pointRadius: 0,
                pointHoverRadius: 5,
                spanGaps: false,
                tension: 0.2,
            };
            if (!item.bandColor || !series.high || !series.low) {
                return [line];
            }
            // 띠: 최대 선에서 바로 다음 데이터셋(최소 선)까지 칠한다. 두 선은 보이지 않게 하고 범례·툴팁에서 뺀다
            const edge = { bandOf: key, yAxisID: item.axis, borderWidth: 0, pointRadius: 0, pointHoverRadius: 0, spanGaps: false, tension: 0.2 };
            return [
                line,
                { ...edge, label: `${item.label} 최대`, data: series.high, backgroundColor: item.bandColor, fill: '+1' },
                { ...edge, label: `${item.label} 최소`, data: series.low, backgroundColor: item.bandColor, fill: false },
            ];
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
                    legend: {
                        position: 'bottom',
                        align: 'start',
                        labels: {
                            color: TICK_COLOR,
                            boxWidth: 10,
                            boxHeight: 10,
                            filter: (legendItem, data) => !data.datasets[legendItem.datasetIndex].bandOf,
                        },
                    },
                    tooltip: {
                        filter: (context) => !context.dataset.bandOf,
                        callbacks: {
                            title: (contexts) => titles[contexts[0].dataIndex],
                            label: (context) => {
                                const key = context.dataset.itemKey;
                                const series = seriesByItem[key];
                                const index = context.dataIndex;
                                return tooltipLine(key, context.parsed.y, series.high?.[index], series.low?.[index]);
                            },
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

// '밀집도 0.92명/㎡', 띠가 있는 항목은 '인원 평균 120명 (최대 150 · 최소 90)'. 값이 없으면 '데이터 없음'
function tooltipLine(key, value, high, low) {
    const item = SERIES_ITEMS[key];
    if (value === null || value === undefined) {
        return `${item.label} 데이터 없음`;
    }
    const format = (number) => (item.decimals ? formatDecimal(number, item.decimals) : formatNumber(Math.round(number)));
    if (!item.bandColor) {
        return `${item.label} ${format(value)}${item.unit}`;
    }
    const range = high === null || high === undefined || low === null || low === undefined
        ? ''
        : ` (최대 ${format(high)} · 최소 ${format(low)})`;
    return `${item.label} 평균 ${format(value)}${item.unit}${range}`;
}
