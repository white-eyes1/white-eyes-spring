// 통계 시계열 선 그래프(SCR-C-004 통합 그래프, FNC-C-012·013·014·024). Chart.js(전역 Chart, graph.html이 불러옴) 코드는 js/chart/ 안에만 둔다.
// 값이 없는 점(sampleSec=0·통계 없음)은 null이라 선과 띠가 끊긴다(spanGaps 끔).
// 시연용 점(isDemo)은 그 점의 칸을 옅은 배경으로 칠한다(FNC-C-024 업무 규칙). 점선은 밀집도 선에만 쓴다(결정 0016).
import { formatDecimal, formatNumber } from '../common/format.js';
import { DEMO_FILL, GRID_COLOR, TICK_COLOR, shadePlugin } from './chart-theme.js';

// 점선 모양: [선 길이 5px, 빈 간격 4px]
// 밀집도 선 점선(인원 선과 구분, 결정 0016)
const DENSITY_DASH = [5, 4];
// 하위 비교 선 색(최대 10개, FNC-C-014). 어두운 배경에서 서로 구분되는 색
const COMPARE_COLORS = [
    '#4f8cff',
    '#f59e0b',
    '#3bc99b',
    '#ef4d55',
    '#a78bfa',
    '#22c3e6',
    '#f472b6',
    '#facc15',
    '#94a3b8',
    '#fb923c',
];

/**
 * 항목 정의. axis: count(명) | density(명/㎡) | dwell(초). 색은 결정 0016(인원 인디고, 유입 라벤더, 유출 짙은 인디고, 밀집도 연한 점선).
 * bandColor가 있는 항목은 최대~최소를 그 색 띠로 함께 그릴 수 있다(인원, '최대·최소 띠'를 켰을 때).
 */
export const SERIES_ITEMS = {
    // 항목 키 -> 표시 정보(label 이름, axis 어느 축, color/width 선 모양, unit 단위, decimals 소수 자릿수, dash 점선, bandColor 띠 색)
    count: {
        label: '인원',
        axis: 'count',
        color: '#818cf8',
        width: 3,
        unit: '명',
        bandColor: 'rgba(129, 140, 248, .16)',
    },
    density: {
        label: '밀집도',
        axis: 'density',
        color: '#e0e7ff',
        width: 2,
        unit: '명/㎡',
        decimals: 2,
        dash: DENSITY_DASH,
    },
    inflow: { label: '유입', axis: 'count', color: '#a5b4fc', width: 2, unit: '명' },
    outflow: { label: '유출', axis: 'count', color: '#4f46e5', width: 2, unit: '명' },
    dwellAvgSec: {
        label: '평균 체류',
        axis: 'dwell',
        color: '#a78bfa',
        width: 2,
        unit: '초',
        decimals: 1,
    },
};

// 축 정의: 인원 계열은 왼쪽, 밀집도·체류는 오른쪽 축을 쓴다
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
    // 현재 그려진 Chart 인스턴스. 다시 그릴 때 destroy 한다
    let chart = null;

    /**
     * @param {string[]} labels x축 글자(점마다 하나)
     * @param {string[]} titles 툴팁 제목(점마다 하나, 예: '17:00 ~ 18:00')
     * @param {Object<string, {values: (number|null)[], high?: (number|null)[], low?: (number|null)[]}>} seriesByItem
     *     고른 항목만, SERIES_ITEMS의 키 → 점마다 값. 띠를 그리는 항목은 high(최대)·low(최소)도 넘긴다
     * @param {boolean[]} [demo] 점마다 시연용 여부(FNC-C-024). 시연용 점의 칸은 옅은 배경
     */
    function update(labels, titles, seriesByItem, demo = []) {
        // 사용자가 고른 항목 키들
        const keys = Object.keys(seriesByItem);
        // 항목마다 데이터셋을 만든다(띠가 있으면 3개: 평균 선 + 최대 + 최소)
        const datasets = keys.flatMap((key) => {
            const item = SERIES_ITEMS[key];
            const series = seriesByItem[key];
            const line = lineDataset(key, item.label, series.values, item.color, item.width);
            // 띠를 쓰지 않는 항목(또는 최대·최소 값이 없을 때)은 선 하나만
            if (!item.bandColor || !series.high || !series.low) {
                return [line];
            }
            // 띠: 최대 선에서 바로 다음 데이터셋(최소 선)까지 칠한다. 두 선은 보이지 않게 하고 범례·툴팁에서 뺀다
            // 최대·최소 선의 공통 모양: 선·점 없음. bandOf는 범례·툴팁 필터가 걸러 낼 표시
            const edge = {
                bandOf: key,
                yAxisID: item.axis,
                borderWidth: 0,
                pointRadius: 0,
                pointHoverRadius: 0,
                spanGaps: false,
                tension: 0.2,
            };
            return [
                line,
                {
                    ...edge,
                    label: `${item.label} 최대`,
                    data: series.high,
                    backgroundColor: item.bandColor,
                    // fill '+1': 다음 데이터셋(최소 선)과의 사이를 칠한다
                    fill: '+1',
                },
                {
                    ...edge,
                    label: `${item.label} 최소`,
                    data: series.low,
                    backgroundColor: item.bandColor,
                    fill: false,
                },
            ];
        });
        // 쓰는 축만 보이게 한다
        const usedAxes = new Set(keys.map((key) => SERIES_ITEMS[key].axis));
        // 시연용(FNC-C-024) 점은 툴팁 제목에 표시를 붙인다
        const demoTitles = titles.map((title, index) =>
            demo[index] ? `${title} · 시연용` : title,
        );
        // 툴팁 한 줄은 데이터셋의 항목 키로 평균·최대·최소를 함께 꾸민다
        draw(labels, demoTitles, datasets, usedAxes, demo, (context) => {
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
        // 하위 단위 이름은 위 SERIES_ITEMS와 관계없이 범례에 그대로 보인다
        const item = SERIES_ITEMS[key];
        // 하위 단위마다 선 하나. 색은 순서대로 돌려 쓴다
        const datasets = seriesList.map((series, index) =>
            lineDataset(
                key,
                series.name,
                series.values,
                // 10개를 넘어도 처음 색부터 다시 쓴다
                COMPARE_COLORS[index % COMPARE_COLORS.length],
                2,
            ),
        );
        // 비교는 한 항목만 쓰므로 축은 그 항목 것 하나, 시연용 칸 칠하기는 없다
        draw(
            labels,
            titles,
            datasets,
            new Set([item.axis]),
            [],
            (context) => `${context.dataset.label} ${valueText(key, context.parsed.y)}`,
        );
    }

    // 선 데이터셋 하나를 만든다. 점은 평소 숨기고 마우스를 올릴 때만 보인다(pointRadius 0 / pointHoverRadius)
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
            // 값이 없는 점(null)에서는 선을 이어 그리지 않는다
            spanGaps: false,
            // 선을 약간 부드럽게
            tension: 0.2,
            borderDash: SERIES_ITEMS[key].dash ?? [],
        };
    }

    // 실제 그리기: 축 설정, 옵션을 만들어 Chart.js 인스턴스를 새로 만든다
    function draw(labels, titles, datasets, usedAxes, demo, tooltipLabel) {
        const scales = {
            x: { grid: { color: GRID_COLOR }, ticks: { color: TICK_COLOR, maxTicksLimit: 12 } },
        };
        // 축마다 보임 여부를 정한다. 안 쓰는 축은 숨긴다
        Object.entries(AXES).forEach(([id, axis]) => {
            scales[id] = {
                display: usedAxes.has(id),
                position: axis.position,
                beginAtZero: true,
                grid: {
                    color: GRID_COLOR,
                    // 격자선은 인원 축(없으면 쓰는 축)에만 그려 오른쪽 축의 격자가 겹쳐 지저분해지지 않게 한다
                    drawOnChartArea: id === 'count' || !usedAxes.has('count'),
                },
                ticks: { color: TICK_COLOR },
                title: { display: true, text: axis.title, color: TICK_COLOR },
            };
        });

        // 시연용 칸 칠하기 플러그인을 함께 등록한다
        const config = {
            type: 'line',
            data: { labels, datasets },
            plugins: [shadePlugin(demo, DEMO_FILL)],
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                // 같은 x 위치의 모든 선을 한 번에 툴팁으로 보인다
                interaction: { mode: 'index', intersect: false },
                // 값이 없는 점(null)도 누를 수 있게 x 위치로 점을 찾는다
                onClick: (event) => {
                    const index = pointIndexAt(event);
                    if (onPointClick && index !== null) {
                        // event.x/y는 canvas 안 좌표. 화면이 이 위치에 '단계별 들어가기' 메뉴를 띄운다
                        onPointClick(index, { x: event.x, y: event.y });
                    }
                },
                onHover: (event) => {
                    // 누를 수 있는 점 위에서만 손가락 모양 커서
                    const index = onPointClick ? pointIndexAt(event) : null;
                    canvas.style.cursor = index !== null && clickable(index) ? 'pointer' : '';
                },
                scales,
                plugins: {
                    // 범례는 아래 왼쪽. 띠 보조 선은 범례에서 뺀다
                    legend: {
                        position: 'bottom',
                        align: 'start',
                        labels: {
                            color: TICK_COLOR,
                            boxWidth: 10,
                            boxHeight: 10,
                            filter: (legendItem, data) =>
                                !data.datasets[legendItem.datasetIndex].bandOf,
                        },
                    },
                    tooltip: {
                        // 띠 보조 선은 툴팁에서도 뺀다
                        filter: (context) => !context.dataset.bandOf,
                        callbacks: {
                            title: (contexts) => titles[contexts[0].dataIndex],
                            label: tooltipLabel,
                        },
                    },
                },
            },
        };
        // 다시 그리기 전에 이전 그래프를 없앤다(캔버스 중복 사용 방지)
        if (chart) {
            chart.destroy();
        }
        // window.Chart: graph.html이 불러온 전역 Chart.js
        chart = new window.Chart(canvas, config);
    }

    // 그래프 영역 안이면 x 위치에 가장 가까운 점의 순번, 밖이거나 점이 없으면 null
    function pointIndexAt(event) {
        // 그려진 그래프가 없으면 area가 undefined
        const area = chart?.chartArea;
        const count = chart?.data.labels.length ?? 0;
        if (
            !area ||
            count === 0 ||
            event.x < area.left ||
            event.x > area.right ||
            event.y < area.top ||
            event.y > area.bottom
        ) {
            return null;
        }
        // 가로 픽셀을 점 순번으로 바꾸고 0~마지막 순번 범위로 자른다
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
    // 값 모양: decimals가 있는 항목은 소수 고정, 아니면 반올림한 정수
    const format = (number) => formatValue(item, number);
    // 띠가 없는 항목은 '이름 값단위' 한 줄
    if (!item.bandColor) {
        return `${item.label} ${format(value)}${item.unit}`;
    }
    // 최대·최소가 모두 있을 때만 괄호로 범위를 덧붙인다
    const range =
        high === null || high === undefined || low === null || low === undefined
            ? ''
            : ` (최대 ${format(high)} · 최소 ${format(low)})`;
    return `${item.label} 평균 ${format(value)}${item.unit}${range}`;
}

// 하위 비교 툴팁 값: '120명', 값이 없으면 '데이터 없음'
function valueText(key, value) {
    const item = SERIES_ITEMS[key];
    return value === null || value === undefined
        ? '데이터 없음'
        : `${formatValue(item, value)}${item.unit}`;
}

// 항목 정의의 소수 자릿수에 맞춰 숫자를 글자로 바꾼다
function formatValue(item, number) {
    return item.decimals ? formatDecimal(number, item.decimals) : formatNumber(Math.round(number));
}
