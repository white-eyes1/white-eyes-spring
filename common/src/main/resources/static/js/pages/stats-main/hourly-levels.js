// 통계 패널의 시간대별 혼잡 단계 접기 영역(SCR-C-003, FNC-C-018).
// 펼칠 때 GET /api/v1/dashboard/hourly-levels(오늘)로 읽어 0~23시 막대 24개를 혼잡 단계 색으로 그린다.
// 막대 높이는 그날 가장 높은 밀집도에 대한 비율이다. 데이터 없는 시간은 회색 낮은 막대다.
// 진행 중인 현재 시간은 1분 통계로 계산되므로 새 1분 통계(통계 SSE)가 오면 펼쳐 있을 때 다시 읽는다.
import { get } from '../../common/api.js';
import { formatDecimal } from '../../common/format.js';
import { levelColor } from '../../map/map-view.js';

// 시간대별 혼잡 단계 API(오늘 0~23시)
const HOURLY_LEVELS_API = '/api/v1/dashboard/hourly-levels';
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';
// 값이 작아도 막대가 보이게 하는 최소 높이(%)
const MIN_BAR_PERCENT = 8;
// 데이터가 없는 시간도 자리를 알 수 있게 아주 낮은 회색 막대로 보인다
const EMPTY_BAR_PERCENT = 4;

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 접기 영역 하나를 만든다. 화면은 setRegion·refresh만 부른다
export function createHourlyLevelsPanel() {
    const box = byId('hourly-levels');
    const bars = byId('hourly-levels-bars');
    const message = byId('hourly-levels-message');
    // regionId: 현재 지역, loadedRegionId: 읽어서 그린 지역, latestRequestId: 늦게 온 응답을 버리는 번호
    let regionId = null;
    let loadedRegionId = null;
    let latestRequestId = 0;

    // 펼칠 때 값을 읽는다
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

    /** 새 1분 통계가 오면 부른다. 현재 시간 막대가 바뀌므로 펼쳐 있을 때만 다시 읽는다 */
    function refresh() {
        if (box.open) {
            load();
        }
    }

    async function load() {
        // 지역이 정해지지 않았으면 읽지 않는다
        if (regionId === null) {
            return;
        }
        // 마지막 요청 번호와 요청한 지역을 기억해 늦게 온 응답을 가려낸다
        const requestId = ++latestRequestId;
        const requestedRegionId = regionId;
        try {
            const items = await get(
                HOURLY_LEVELS_API,
                { targetType: 'REGION', targetId: String(requestedRegionId) },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            // 이 지역을 읽었음을 기록해 같은 지역으로 다시 펼쳐도 중복해서 읽지 않게 한다
            loadedRegionId = requestedRegionId;
            render(items);
        } catch {
            // 실패하면 막대를 비우고 오류 문구를 보인다
            if (requestId === latestRequestId) {
                bars.replaceChildren();
                message.textContent = LOAD_ERROR;
                message.hidden = false;
            }
        }
    }

    function render(items) {
        // 밀집도가 있는 시간만 모아 가장 큰 값을 높이 기준으로 삼는다
        const densities = items
            .filter((item) => item.density !== null)
            .map((item) => Number(item.density));
        const max = densities.length > 0 ? Math.max(...densities) : 0;
        // 24개 막대를 새로 만든다
        bars.replaceChildren(...items.map((item) => bar(item, max)));
        message.textContent = NO_DATA;
        // 밀집도가 있는 시간이 하나라도 있으면 문구를 숨긴다
        message.hidden = densities.length > 0;
    }

    return { setRegion, refresh };
}

// 단계 색 막대. 툴팁: '18시 · 밀집도 1.24 · 3단계', 데이터 없으면 '18시 · 데이터 없음'
// 막대 하나(1시간). 높이는 그날 최대 밀집도에 대한 비율이고, 색은 서버가 판정한 혼잡 단계 색이다
function bar(item, max) {
    const element = document.createElement('span');
    element.className = 'hour-bar';
    // 밀집도가 없는 시간: 회색 낮은 막대
    if (item.density === null) {
        element.classList.add('empty');
        element.style.height = `${EMPTY_BAR_PERCENT}%`;
        element.title = `${item.hour}시 · ${NO_DATA}`;
        return element;
    }
    const density = Number(item.density);
    // 최대가 0이면 비율을 낼 수 없으니 최소 높이로
    const percent = max > 0 ? Math.max(MIN_BAR_PERCENT, (density / max) * 100) : MIN_BAR_PERCENT;
    element.style.height = `${percent}%`;
    element.style.background = levelColor(item.level);
    element.title = `${item.hour}시 · 밀집도 ${formatDecimal(density, 2)} · ${item.level}단계`;
    return element;
}
