// 통계 패널 '피크 시간 TOP 3 · 증감 추세' 접기 영역의 증감 추세(SCR-C-003, FNC-C-019).
// 펼칠 때 GET /api/v1/dashboard/trend로 읽어 어제까지 7일을 지난주·지난달·지난해 같은 7일과 비교한 증감률을 보인다.
// 증가는 빨강 ▲ +n%, 감소는 파랑 ▼ −n%, 기준 기간 데이터가 없으면 '-'. 줄을 누르면 통계 그래프(SCR-C-004)를 대상·1일 단위·대상 기간(어제까지 7일)으로 연다(기간 비교 화면은 결정 0016으로 없앰).
// 기간이 어제까지라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않는다.
import { get, url } from '../../common/api.js';
import { formatSigned } from '../../common/format.js';

// 증감 추세 API
const TREND_API = '/api/v1/dashboard/trend';
// 비교하는 일 수(어제까지 최근 7일)
const DAYS = 7;
const LOAD_ERROR = '불러오지 못했습니다.';
// 응답 필드, 이름(세 줄 모두 같은 통계 그래프로 연결)
const RATES = [
    ['weekRate', '지난주 대비'],
    ['monthRate', '지난달 대비'],
    ['yearRate', '지난해 대비'],
];

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 접기 영역 하나를 만든다. 화면은 setRegion만 부른다
export function createTrendPanel() {
    // 피크 시간 TOP 3와 같은 접기 영역(peak-trend)에 속한다
    const box = byId('peak-trend');
    const list = byId('trend-list');
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
            const trend = await get(
                TREND_API,
                { targetType: 'REGION', targetId: String(requestedRegionId) },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            // 이 지역을 읽었음을 기록한다
            loadedRegionId = requestedRegionId;
            // 응답의 세 비율(지난주·지난달·지난해)을 한 줄씩 보인다
            list.replaceChildren(
                ...RATES.map(([field, label]) =>
                    row(label, trend[field], graphLink(requestedRegionId)),
                ),
            );
        } catch {
            // 늦게 온 실패 응답은 무시하고, 최신 요청일 때만 오류 문구를 보인다
            if (requestId === latestRequestId) {
                const message = document.createElement('span');
                message.className = 'muted';
                message.textContent = LOAD_ERROR;
                list.replaceChildren(message);
            }
        }
    }

    return { setRegion };
}

// 통계 그래프 주소: 1일 단위, 대상 기간 = 어제까지 7일(FNC-C-019 후속 동작). 그래프의 to는 끝을 빼므로 오늘 0시
// 통계 그래프 주소
function graphLink(regionId) {
    // 오늘 0시. 그래프의 to는 끝을 포함하지 않으므로 어제까지를 뜻한다
    const today = new Date();
    const params = new URLSearchParams({
        targetType: 'REGION',
        targetId: String(regionId),
        interval: '1d',
        from: toDateText(addDays(today, -DAYS)),
        to: toDateText(today),
    });
    return url(`/stats/graph?${params.toString()}`);
}

// '지난주 대비  ▲ +12.4%' (증가 빨강, 감소 파랑 ▼ −n%, 기준 없음 '-')
function row(label, rate, href) {
    // 한 줄: 이름과 증감률. 줄 전체가 통계 그래프 링크다
    const element = document.createElement('a');
    element.className = 'row trend-row';
    element.href = href;
    const name = document.createElement('span');
    name.className = 'grow';
    name.textContent = label;
    const value = document.createElement('b');
    // 기준 기간에 데이터가 없으면 '-'
    if (rate === null) {
        value.className = 'muted';
        value.textContent = '-';
    } else {
        // 기간 비교 화면과 같은 부호 표기. 반올림해서 0이면 부호가 없으므로 화살표·색도 붙이지 않는다
        // 반올림 후의 표기로 증가·감소를 판단한다(0%이면 둘 다 아님)
        const text = formatSigned(rate, 1);
        const up = text.startsWith('+');
        const down = text.startsWith('−');
        // 증가는 빨강, 감소는 파랑 스타일
        value.className = up ? 'trend-up' : down ? 'trend-down' : '';
        const mark = up ? '▲ ' : down ? '▼ ' : '';
        value.textContent = `${mark}${text}%`;
    }
    element.append(name, value);
    return element;
}

function addDays(date, days) {
    // 시·분·초 없이 날짜만 복사해 일 수를 더한다
    const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    copy.setDate(copy.getDate() + days);
    return copy;
}

function toDateText(date) {
    // 서버가 받는 날짜 형식(yyyy-MM-dd)
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
