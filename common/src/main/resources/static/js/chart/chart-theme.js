// 통계 그래프 색·공통 Chart.js 플러그인(SCR-C-004, 결정 0016). 그래프 모듈들(series-chart·panel-charts)이 함께 쓴다.

// 격자선·눈금 글자·인원/유입/유출 색. 어두운 배경 기준이며 결정 0016의 색 약속을 따른다
export const GRID_COLOR = '#1e2a38';
export const TICK_COLOR = '#8b98a9';
export const COUNT_COLOR = '#818cf8';
export const COUNT_FILL = 'rgba(129, 140, 248, .16)';
export const INFLOW_COLOR = '#a5b4fc';
export const OUTFLOW_COLOR = '#4f46e5';
// 혼잡 단계 막대 색(1 정상, 2 주의, 3 위험). 단계는 서버가 밀집도로 판정한 point.level(FNC-C-025)
export const LEVEL_COLORS = { 1: '#34a37f', 2: '#c98a1c', 3: '#d9475a' };
// 시연용 점(FNC-C-024) 칸의 옅은 배경
export const DEMO_FILL = 'rgba(129, 140, 248, .07)';
// 주의 이상 구간의 옅은 빨강(인원·유입·유출 그래프)
export const CONGESTION_FILL = 'rgba(239, 77, 85, .15)';

/**
 * flags[i]가 참인 점의 칸(앞뒤 점의 가운데까지)을 fill 색으로 칠하는 플러그인. 데이터보다 먼저 그린다.
 * @param {boolean[]} flags 점마다 칠할지
 * @param {string} fill
 */
export function shadePlugin(flags, fill) {
    return {
        id: 'shade',
        // 데이터를 그리기 전에(beforeDatasetsDraw) 칠해야 선·막대가 칠한 칸 위에 보인다
        beforeDatasetsDraw(chart) {
            // 칠할 점이 하나도 없으면 아무것도 하지 않는다
            if (!flags.some(Boolean)) {
                return;
            }
            // ctx: 캔버스 그리기 도구, chartArea: 축 눈금을 뺀 그래프 영역, x: 가로축 척도
            const {
                ctx,
                chartArea: area,
                scales: { x },
            } = chart;
            // 칸 너비: 점이 둘 이상이면 이웃한 두 점의 픽셀 간격, 하나뿐이면 영역 전체
            const step =
                flags.length > 1
                    ? Math.abs(x.getPixelForValue(1) - x.getPixelForValue(0))
                    : area.right - area.left;
            // save/restore로 채우기 색을 이 플러그인 안에서만 바꾼다
            ctx.save();
            ctx.fillStyle = fill;
            flags.forEach((flag, index) => {
                if (!flag) {
                    return;
                }
                // 점 위치를 가운데로 두고 좌우 절반씩 칠한다
                const center = x.getPixelForValue(index);
                // 그래프 영역 밖으로 번지지 않게 가장자리를 잘라 낸다
                const left = Math.max(area.left, center - step / 2);
                const right = Math.min(area.right, center + step / 2);
                ctx.fillRect(left, area.top, right - left, area.bottom - area.top);
            });
            ctx.restore();
        },
    };
}
