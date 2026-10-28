// 통계 패널 '피크 시간 TOP 3 · 증감 추세' 접기 영역의 증감 추세(SCR-C-003, FNC-C-019).
// 펼칠 때 GET /api/v1/dashboard/trend로 읽어 어제까지 7일을 지난주·지난달·지난해 같은 7일과 비교한 증감률을 보인다.
// 증가는 빨강 ▲ +n%, 감소는 파랑 ▼ −n%, 기준 기간 데이터가 없으면 '-'. 줄을 누르면 통계 그래프(SCR-C-004)를 대상·1일 단위·대상 기간(어제까지 7일)으로 연다(기간 비교 화면은 결정 0016으로 없앰).
// 기간이 어제까지라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않는다.
import { get, url } from '../../common/api.js';
import { formatSigned } from '../../common/format.js';

const TREND_API = '/api/v1/dashboard/trend';
const DAYS = 7;
const LOAD_ERROR = '불러오지 못했습니다.';
// 응답 필드, 이름(세 줄 모두 같은 통계 그래프로 연결)
const RATES = [
    ['weekRate', '지난주 대비'],
    ['monthRate', '지난달 대비'],
    ['yearRate', '지난해 대비'],
];

const byId = (id) => document.getElementById(id);

export function createTrendPanel() {
    const box = byId('peak-trend');
    const list = byId('trend-list');
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
            const trend = await get(
                TREND_API,
                { targetType: 'REGION', targetId: String(requestedRegionId) },
                { toast: false },
            );
            if (requestId !== latestRequestId) {
                return; // 더 늦게 보낸 요청이 있다(지역 변경 등)
            }
            loadedRegionId = requestedRegionId;
            list.replaceChildren(...RATES.map(([field, label]) =>
                row(label, trend[field], graphLink(requestedRegionId))));
        } catch {
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
function graphLink(regionId) {
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
    const element = document.createElement('a');
    element.className = 'row trend-row';
    element.href = href;
    const name = document.createElement('span');
    name.className = 'grow';
    name.textContent = label;
    const value = document.createElement('b');
    if (rate === null) {
        value.className = 'muted';
        value.textContent = '-';
    } else {
        // 기간 비교 화면과 같은 부호 표기. 반올림해서 0이면 부호가 없으므로 화살표·색도 붙이지 않는다
        const text = formatSigned(rate, 1);
        const up = text.startsWith('+');
        const down = text.startsWith('−');
        value.className = up ? 'trend-up' : down ? 'trend-down' : '';
        const mark = up ? '▲ ' : down ? '▼ ' : '';
        value.textContent = `${mark}${text}%`;
    }
    element.append(name, value);
    return element;
}

function addDays(date, days) {
    const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    copy.setDate(copy.getDate() + days);
    return copy;
}

function toDateText(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
