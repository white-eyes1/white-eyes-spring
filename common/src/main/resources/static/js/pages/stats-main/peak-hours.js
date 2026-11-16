// 통계 패널의 피크 시간 TOP 3 접기 영역(SCR-C-003, FNC-C-029).
// 펼칠 때 GET /api/v1/dashboard/peak-hours로 읽어 1~3위 시간대와 평균 인원을 보인다. 기간 탭(일·주·월·연, 기본 주)을 바꾸면 다시 읽는다.
// 기간이 모두 어제까지라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않는다.
import { get } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';

// 피크 시간 API
const PEAK_HOURS_API = '/api/v1/dashboard/peak-hours';
// 기본 기간 탭: 주(WEEK)
const DEFAULT_PERIOD = 'WEEK';
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 접기 영역 하나를 만든다. 화면은 setRegion만 부른다
export function createPeakHoursPanel() {
    // 접기 영역은 증감 추세(trend.js)와 같은 요소(peak-trend)를 쓴다
    const box = byId('peak-trend');
    // 기간 탭(일·주·월·연)
    const tabs = [...document.querySelectorAll('#peak-periods [data-period]')];
    const list = byId('peak-list');
    // regionId, period: 현재 조건. loadedKey: 읽어서 그린 조건(지역+기간). latestRequestId: 늦게 온 응답을 버리는 번호
    let regionId = null;
    let period = DEFAULT_PERIOD;
    let loadedKey = null;
    let latestRequestId = 0;

    // 펼칠 때 읽는다
    box.addEventListener('toggle', () => {
        if (box.open) {
            load();
        }
    });
    // 기간 탭을 누르면 기간을 바꾸고 다시 읽는다
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
        // 펼쳐 있고 이 조건을 읽은 적이 없으면 읽는다
        if (box.open && loadedKey !== key()) {
            load();
        }
    }

    // 같은 지역·기간을 두 번 읽지 않으려는 키
    function key() {
        return `${regionId}:${period}`;
    }

    // 선택된 탭을 강조하고 접근성 상태(aria-selected)를 맞춘다
    function renderTabs() {
        tabs.forEach((tab) => {
            const on = tab.dataset.period === period;
            tab.className = on ? 'chip run' : 'chip off';
            tab.setAttribute('aria-selected', String(on));
        });
    }

    async function load() {
        // 지역이 정해지지 않았으면 읽지 않는다
        if (regionId === null) {
            return;
        }
        // 요청 번호와 조건 키로 늦게 온 응답을 가려낸다
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
            // 읽은 조건을 기록해 같은 조건으로 다시 펼쳐도 중복해서 읽지 않게 한다
            loadedKey = requestedKey;
            render(items);
        } catch {
            if (requestId === latestRequestId) {
                list.replaceChildren(message(LOAD_ERROR));
            }
        }
    }

    function render(items) {
        // 데이터가 없으면 '데이터 없음', 있으면 1~3위 줄
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

// 문구 한 줄
function message(text) {
    const element = document.createElement('span');
    element.className = 'muted';
    element.textContent = text;
    return element;
}
