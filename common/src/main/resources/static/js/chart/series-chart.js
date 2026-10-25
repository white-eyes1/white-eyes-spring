// 통계 시계열 선 그래프(SCR-C-004, FNC-C-012·013·014). Chart.js(전역 Chart, graph.html이 불러옴) 코드는 이 파일에만 둔다.
// 값이 없는 점(sampleSec=0·통계 없음)은 null이라 선과 띠가 끊긴다(spanGaps 끔).
import { formatDecimal, formatNumber } from '../common/format.js';

const GRID_COLOR = '#1e2a38';
const TICK_COLOR = '#8b98a9';
// 하위 비교 선 색(최대 10개, FNC-C-014). 어두운 배경에서 서로 구분되는 색
const COMPARE_COLORS = ['#4f8cff', '#f59e0b', '#3bc99b', '#ef4d55', '#a78bfa', '#22c3e6', '#f472b6', '#facc15', '#94a3b8', '#fb923c'];

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
 * @param {{onPointClick?: (index: number, position: {x: number, y: number}) => void, clickable?: (index: number) => boolean}} [options]
 *     onPointClick: 그래프 안을 누르면 가장 가까운 점의 순번과 canvas 안 위치를 넘긴다(단계별 들어가기, FNC-C-013).
 *     clickable: 그 점을 누를 수 있는지(마우스 모양을 손가락으로 바꿀지)
 */
export function createSeriesChart(canvas, { onPointClick, clickable = () => true } = {}) {
    let chart = null;

    /**
     * @param {string[]} labels x축 글자(점마다 하나)
     * @param {string[]} titles 툴팁 제목(점마다 하나, 예: '17:00 ~ 18:00')
     * @param {Object<string, {values: (number|null)[], high?: (number|null)[], low?: (number|null)[]}>} seriesByItem
     *     고른 항목만, SERIES_ITEMS의 키 → 점마다 값. 띠를 그리는 항목은 high(최대)·low(최소)도 넘긴다
     */
    function update(labels, titles, seriesByItem) {
        const keys = Object.keys(seriesByItem);
        const datasets = keys.flatMap((key) => {
            const item = SERIES_ITEMS[key];
            const series = seriesByItem[key];
            const line = lineDataset(key, item.label, series.values, item.color, item.width);
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
        const usedAxes = new Set(keys.map((key) => SERIES_ITEMS[key].axis));
        draw(labels, titles, datasets, usedAxes, (context) => {
            const key = context.dataset.itemKey;
            const series = seriesByItem[key];
            const index = context.dataIndex;
            return tooltipLine(key, context.parsed.y, series.high?.[index], series.low?.[index]);
        });
    }

    /**
     * 하위 비교(FNC-C-014): 한 항목을 하위 단위마다 다른 색 선으로 겹쳐 그린다. 범례는 하위 단위 이름이다.
     * @param {string[]} labels x축 글자
     * @param {string[]} titles 툴팁 제목
     * @param {string} key SERIES_ITEMS의 키(단위·축·자릿수에 씀)
     * @param {{name: string, values: (number|null)[]}[]} seriesList 하위 단위별 값(목록 순서대로 색을 준다)
     */
    function compare(labels, titles, key, seriesList) {
        const item = SERIES_ITEMS[key];
        const datasets = seriesList.map((series, index) =>
            lineDataset(key, series.name, series.values, COMPARE_COLORS[index % COMPARE_COLORS.length], 2),
        );
        draw(labels, titles, datasets, new Set([item.axis]), (context) =>
            `${context.dataset.label} ${valueText(key, context.parsed.y)}`,
        );
    }

    function lineDataset(key, label, data, color, width) {
        return {
            itemKey: key,
            label,
            data,
            yAxisID: SERIES_ITEMS[key].axis,
            borderColor: color,
            backgroundColor: color,
            borderWidth: width,
            pointRadius: 0,
            pointHoverRadius: 5,
            spanGaps: false,
            tension: 0.2,
        };
    }

    function draw(labels, titles, datasets, usedAxes, tooltipLabel) {
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
                // 값이 없는 점(null)도 누를 수 있게 x 위치로 점을 찾는다
                onClick: (event) => {
                    const index = pointIndexAt(event);
                    if (onPointClick && index !== null) {
                        onPointClick(index, { x: event.x, y: event.y });
                    }
                },
                onHover: (event) => {
                    const index = onPointClick ? pointIndexAt(event) : null;
                    canvas.style.cursor = index !== null && clickable(index) ? 'pointer' : '';
                },
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
                            label: tooltipLabel,
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

    // 그래프 영역 안이면 x 위치에 가장 가까운 점의 순번, 밖이거나 점이 없으면 null
    function pointIndexAt(event) {
        const area = chart?.chartArea;
        const count = chart?.data.labels.length ?? 0;
        if (!area || count === 0 || event.x < area.left || event.x > area.right || event.y < area.top || event.y > area.bottom) {
            return null;
        }
        const index = Math.round(chart.scales.x.getValueForPixel(event.x));
        return Math.min(Math.max(index, 0), count - 1);
    }

    return { update, compare };
}

// '밀집도 0.92명/㎡', 띠가 있는 항목은 '인원 평균 120명 (최대 150 · 최소 90)'. 값이 없으면 '데이터 없음'
function tooltipLine(key, value, high, low) {
    const item = SERIES_ITEMS[key];
    if (value === null || value === undefined) {
        return `${item.label} 데이터 없음`;
    }
    const format = (number) => formatValue(item, number);
    if (!item.bandColor) {
        return `${item.label} ${format(value)}${item.unit}`;
    }
    const range = high === null || high === undefined || low === null || low === undefined
        ? ''
        : ` (최대 ${format(high)} · 최소 ${format(low)})`;
    return `${item.label} 평균 ${format(value)}${item.unit}${range}`;
}

// 하위 비교 툴팁 값: '120명', 값이 없으면 '데이터 없음'
function valueText(key, value) {
    const item = SERIES_ITEMS[key];
    return value === null || value === undefined ? '데이터 없음' : `${formatValue(item, value)}${item.unit}`;
}

function formatValue(item, number) {
    return item.decimals ? formatDecimal(number, item.decimals) : formatNumber(Math.round(number));
}
