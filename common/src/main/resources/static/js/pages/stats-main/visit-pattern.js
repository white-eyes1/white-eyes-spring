// 통계 패널의 방문 패턴 접기 영역(SCR-C-003, FNC-C-028).
// 펼칠 때 GET /api/v1/dashboard/visit-pattern(기간을 비워 어제까지 28일)으로 읽어 요일 × 시간 표와 평일·주말 행을 그린다.
// 칸 색은 값 크기에 따른 파랑 한 가지 색의 진하기(혼잡 단계 색과 구분, 업무 규칙)이고, 데이터 없는 칸은 빈칸이다.
// 시간별 통계라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않고, 지역이 바뀌거나 다시 펼칠 때 읽는다.
import { get } from '../../common/api.js';
import { formatDecimal } from '../../common/format.js';

const VISIT_PATTERN_API = '/api/v1/dashboard/visit-pattern';
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
const HOURS = 24;
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';
// 칸 색 rgba(79,140,255, a)의 진하기 범위(시안 0.08~0.88)
const MIN_ALPHA = 0.08;
const MAX_ALPHA = 0.88;

const byId = (id) => document.getElementById(id);

export function createVisitPatternPanel() {
    const box = byId('visit-pattern');
    const grid = byId('visit-pattern-grid');
    const message = byId('visit-pattern-message');
    let regionId = null;
    let loadedRegionId = null;
    let latestRequestId = 0;

    box.addEventListener('toggle', () => {
        if (box.open) {
            load();
        }
    });

    /** 통계 대상 지역이 바뀌면 부른다. 펼쳐 있으면 바로 다시 읽는다 */
    function setRegion(id) {
        regionId = id;
        if (box.open && loadedRegionId !== id) {
            load();
        }
    }

    async function load() {
        if (regionId === null) {
            return;
        }
        const requestId = ++latestRequestId;
        const requestedRegionId = regionId;
        try {
            const pattern = await get(
                VISIT_PATTERN_API,
                { targetType: 'REGION', targetId: String(requestedRegionId) },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            loadedRegionId = requestedRegionId;
            render(pattern);
        } catch {
            if (requestId === latestRequestId) {
                grid.replaceChildren();
                message.textContent = LOAD_ERROR;
                message.hidden = false;
            }
        }
    }

    function render(pattern) {
        const rows = WEEKDAYS.map((label, index) => ({
            label,
            values: pattern.cells.slice(index * HOURS, (index + 1) * HOURS).map((cell) => cell.avgCount),
        }));
        // 평일(월~금)·주말(토·일) 행은 요일 행 아래에 따로 둔다(FNC-C-028 처리 순서 3)
        rows.push({ label: '평일', values: pattern.weekdayAvg, separated: true });
        rows.push({ label: '주말', values: pattern.weekendAvg });

        const numbers = rows.flatMap((row) => row.values).filter((value) => value !== null).map(Number);
        const min = numbers.length > 0 ? Math.min(...numbers) : 0;
        const max = numbers.length > 0 ? Math.max(...numbers) : 0;
        grid.replaceChildren(...rows.flatMap((row) => rowElements(row, min, max)));
        message.textContent = NO_DATA;
        message.hidden = numbers.length > 0;
    }

    return { setRegion };
}

function rowElements(row, min, max) {
    const label = document.createElement('span');
    label.className = row.separated ? 'muted pattern-label separated' : 'muted pattern-label';
    label.textContent = row.label;
    const cells = row.values.map((value, hour) => {
        const cell = document.createElement('span');
        cell.className = row.separated ? 'pattern-cell separated' : 'pattern-cell';
        if (value === null) {
            cell.classList.add('empty');
            cell.title = `${row.label} ${hour}시 · ${NO_DATA}`;
            return cell;
        }
        const number = Number(value);
        const ratio = max > min ? (number - min) / (max - min) : 1;
        cell.style.background = `rgba(79, 140, 255, ${(MIN_ALPHA + ratio * (MAX_ALPHA - MIN_ALPHA)).toFixed(2)})`;
        cell.title = `${row.label} ${hour}시 · 평균 ${formatDecimal(number, 1)}명`;
        return cell;
    });
    return [label, ...cells];
}
