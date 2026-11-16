// 통계 패널의 방문 패턴 접기 영역(SCR-C-003, FNC-C-028).
// 펼칠 때 GET /api/v1/dashboard/visit-pattern(기간을 비워 어제까지 28일)으로 읽어 요일 × 시간 표와 평일·주말 행을 그린다.
// 칸 색은 값 크기에 따른 파랑 한 가지 색의 진하기(혼잡 단계 색과 구분, 업무 규칙)이고, 데이터 없는 칸은 빈칸이다.
// 시간별 통계라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않고, 지역이 바뀌거나 다시 펼칠 때 읽는다.
import { get } from '../../common/api.js';
import { formatDecimal } from '../../common/format.js';

// 방문 패턴 API(기간을 비워 어제까지 28일)
const VISIT_PATTERN_API = '/api/v1/dashboard/visit-pattern';
// 요일 이름(월~일). 표의 행 순서와 같다
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
// 하루 시간 수. 요일당 칸 24개
const HOURS = 24;
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';
// 칸 색 rgba(79,140,255, a)의 진하기 범위(시안 0.08~0.88)
const MIN_ALPHA = 0.08;
const MAX_ALPHA = 0.88;

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 접기 영역 하나를 만든다. 화면은 setRegion만 부른다
export function createVisitPatternPanel() {
    const box = byId('visit-pattern');
    const grid = byId('visit-pattern-grid');
    const message = byId('visit-pattern-message');
    // regionId: 현재 지역, loadedRegionId: 읽어서 그린 지역, latestRequestId: 늦게 온 응답을 버리는 번호
    let regionId = null;
    let loadedRegionId = null;
    let latestRequestId = 0;

    // 펼칠 때 읽는다
    box.addEventListener('toggle', () => {
        if (box.open) {
            load();
        }
    });

    /** 통계 대상 지역이 바뀌면 부른다. 펼쳐 있으면 바로 다시 읽는다 */
    function setRegion(id) {
        regionId = id;
        // 펼쳐 있고 아직 이 지역을 읽은 적이 없으면 읽는다
        if (box.open && loadedRegionId !== id) {
            load();
        }
    }

    async function load() {
        // 지역이 정해지지 않았으면 읽지 않는다
        if (regionId === null) {
            return;
        }
        // 요청 번호와 요청한 지역을 기억해 늦게 온 응답을 가려낸다
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
            // 이 지역을 읽었음을 기록한다
            loadedRegionId = requestedRegionId;
            render(pattern);
        } catch {
            // 늦게 온 실패 응답은 무시하고, 최신 요청일 때만 오류 문구를 보인다
            if (requestId === latestRequestId) {
                grid.replaceChildren();
                message.textContent = LOAD_ERROR;
                message.hidden = false;
            }
        }
    }

    function render(pattern) {
        // 응답의 평탄한 cells(요일 7 x 시간 24)를 요일 행으로 나눈다
        const rows = WEEKDAYS.map((label, index) => ({
            label,
            values: pattern.cells
                .slice(index * HOURS, (index + 1) * HOURS)
                .map((cell) => cell.avgCount),
        }));
        // 평일(월~금)·주말(토·일) 행은 요일 행 아래에 따로 둔다(FNC-C-028 처리 순서 3)
        // 평일·주말 평균 행은 요일 행과 구분되게 따로 추가한다
        rows.push({ label: '평일', values: pattern.weekdayAvg, separated: true });
        rows.push({ label: '주말', values: pattern.weekendAvg });

        // 값이 있는 칸만 모아 최소·최대를 구한다(색의 기준)
        const numbers = rows
            .flatMap((row) => row.values)
            .filter((value) => value !== null)
            .map(Number);
        const min = numbers.length > 0 ? Math.min(...numbers) : 0;
        const max = numbers.length > 0 ? Math.max(...numbers) : 0;
        grid.replaceChildren(...rows.flatMap((row) => rowElements(row, min, max)));
        // 하루 7행 + 평일·주말 행의 요소를 이어 붙여 격자에 그린다
        message.textContent = NO_DATA;
        message.hidden = numbers.length > 0;
    }

    return { setRegion };
}

// 한 행: 라벨 칸 + 24개 값 칸
function rowElements(row, min, max) {
    const label = document.createElement('span');
    label.className = row.separated ? 'muted pattern-label separated' : 'muted pattern-label';
    label.textContent = row.label;
    const cells = row.values.map((value, hour) => {
        const cell = document.createElement('span');
        cell.className = row.separated ? 'pattern-cell separated' : 'pattern-cell';
        // 값이 없으면 빈칸
        if (value === null) {
            cell.classList.add('empty');
            cell.title = `${row.label} ${hour}시 · ${NO_DATA}`;
            return cell;
        }
        const number = Number(value);
        // min~max 중 위치(0~1)를 파랑의 진하기로 바꾼다. 모두 같으면 가장 진하게
        const ratio = max > min ? (number - min) / (max - min) : 1;
        cell.style.background = `rgba(79, 140, 255, ${(MIN_ALPHA + ratio * (MAX_ALPHA - MIN_ALPHA)).toFixed(2)})`;
        cell.title = `${row.label} ${hour}시 · 평균 ${formatDecimal(number, 1)}명`;
        return cell;
    });
    return [label, ...cells];
}
