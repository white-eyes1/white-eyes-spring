// 통계 패널의 시간대별 혼잡 단계 접기 영역(SCR-C-003, FNC-C-018).
// 펼칠 때 GET /api/v1/dashboard/hourly-levels(오늘)로 읽어 0~23시 막대 24개를 혼잡 단계 색으로 그린다.
// 막대 높이는 그날 가장 높은 밀집도에 대한 비율이다. 데이터 없는 시간은 회색 낮은 막대다.
// 진행 중인 현재 시간은 1분 통계로 계산되므로 새 1분 통계(통계 SSE)가 오면 펼쳐 있을 때 다시 읽는다.
import { get } from '../../common/api.js';
import { formatDecimal } from '../../common/format.js';
import { levelColor } from '../../map/map-view.js';

const HOURLY_LEVELS_API = '/api/v1/dashboard/hourly-levels';
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';
// 값이 작아도 막대가 보이게 하는 최소 높이(%)
const MIN_BAR_PERCENT = 8;
const EMPTY_BAR_PERCENT = 4;

const byId = (id) => document.getElementById(id);

export function createHourlyLevelsPanel() {
    const box = byId('hourly-levels');
    const bars = byId('hourly-levels-bars');
    const message = byId('hourly-levels-message');
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

    /** 새 1분 통계가 오면 부른다. 현재 시간 막대가 바뀌므로 펼쳐 있을 때만 다시 읽는다 */
    function refresh() {
        if (box.open) {
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
            const items = await get(
                HOURLY_LEVELS_API,
                { targetType: 'REGION', targetId: String(requestedRegionId) },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            loadedRegionId = requestedRegionId;
            render(items);
        } catch {
            if (requestId === latestRequestId) {
                bars.replaceChildren();
                message.textContent = LOAD_ERROR;
                message.hidden = false;
            }
        }
    }

    function render(items) {
        const densities = items.filter((item) => item.density !== null).map((item) => Number(item.density));
        const max = densities.length > 0 ? Math.max(...densities) : 0;
        bars.replaceChildren(...items.map((item) => bar(item, max)));
        message.textContent = NO_DATA;
        message.hidden = densities.length > 0;
    }

    return { setRegion, refresh };
}

// 단계 색 막대. 툴팁: '18시 · 밀집도 1.24 · 3단계', 데이터 없으면 '18시 · 데이터 없음'
function bar(item, max) {
    const element = document.createElement('span');
    element.className = 'hour-bar';
    if (item.density === null) {
        element.classList.add('empty');
        element.style.height = `${EMPTY_BAR_PERCENT}%`;
        element.title = `${item.hour}시 · ${NO_DATA}`;
        return element;
    }
    const density = Number(item.density);
    const percent = max > 0 ? Math.max(MIN_BAR_PERCENT, (density / max) * 100) : MIN_BAR_PERCENT;
    element.style.height = `${percent}%`;
    element.style.background = levelColor(item.level);
    element.title = `${item.hour}시 · 밀집도 ${formatDecimal(density, 2)} · ${item.level}단계`;
    return element;
}
