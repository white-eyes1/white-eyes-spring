// 통계 패널의 피크 시간 TOP 3 접기 영역(SCR-C-003, FNC-C-029).
// 펼칠 때 GET /api/v1/dashboard/peak-hours로 읽어 1~3위 시간대와 평균 인원을 보인다. 기간 탭(일·주·월·연, 기본 주)을 바꾸면 다시 읽는다.
// 기간이 모두 어제까지라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않는다.
import { get } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';

const PEAK_HOURS_API = '/api/v1/dashboard/peak-hours';
const DEFAULT_PERIOD = 'WEEK';
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';

const byId = (id) => document.getElementById(id);

export function createPeakHoursPanel() {
    const box = byId('peak-trend');
    const tabs = [...document.querySelectorAll('#peak-periods [data-period]')];
    const list = byId('peak-list');
    let regionId = null;
    let period = DEFAULT_PERIOD;
    let loadedKey = null;
    let latestRequestId = 0;

    box.addEventListener('toggle', () => {
        if (box.open) {
            load();
        }
    });
    tabs.forEach((tab) => {
        tab.addEventListener('click', () => {
            period = tab.dataset.period;
            renderTabs();
            load();
        });
    });
    renderTabs();

    /** 통계 대상 지역이 바뀌면 부른다. 펼쳐 있으면 바로 다시 읽는다 */
    function setRegion(id) {
        regionId = id;
        if (box.open && loadedKey !== key()) {
            load();
        }
    }

    function key() {
        return `${regionId}:${period}`;
    }

    function renderTabs() {
        tabs.forEach((tab) => {
            const on = tab.dataset.period === period;
            tab.className = on ? 'chip run' : 'chip off';
            tab.setAttribute('aria-selected', String(on));
        });
    }

    async function load() {
        if (regionId === null) {
            return;
        }
        const requestId = ++latestRequestId;
        const requestedKey = key();
        try {
            const items = await get(
                PEAK_HOURS_API,
                { targetType: 'REGION', targetId: String(regionId), period },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역·기간 변경 등)
            }
            loadedKey = requestedKey;
            render(items);
        } catch {
            if (requestId === latestRequestId) {
                list.replaceChildren(message(LOAD_ERROR));
            }
        }
    }

    function render(items) {
        list.replaceChildren(...(items.length === 0 ? [message(NO_DATA)] : items.map(row)));
    }

    return { setRegion };
}

// '1  18~19시  1,204명'
function row(item) {
    const element = document.createElement('div');
    element.className = 'row';
    const rank = document.createElement('b');
    rank.className = 'rank';
    rank.textContent = String(item.rank);
    const hour = document.createElement('span');
    hour.className = 'grow';
    hour.textContent = `${item.hour}~${item.hour + 1}시`;
    const count = document.createElement('b');
    count.textContent = `${formatNumber(Math.round(Number(item.avgCount)))}명`;
    element.append(rank, hour, count);
    return element;
}

function message(text) {
    const element = document.createElement('span');
    element.className = 'muted';
    element.textContent = text;
    return element;
}
