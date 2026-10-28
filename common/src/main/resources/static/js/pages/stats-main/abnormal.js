// 통계 패널의 비정상 인원 변동 접기 영역(SCR-C-003, FNC-C-020).
// 펼칠 때 GET /api/v1/compare(어제까지 최근 7일, basePreset=LAST_MONTH)로 읽고, 비정상 칸 수와 |증감률|이 큰 칸 3개를 보인다.
// '자세히'는 같은 지역·같은 7일의 통계 그래프(1시간 단위, SCR-C-004)를 연다(기간 비교 화면은 결정 0016으로 없앰).
// 시간별 통계라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않고, 지역이 바뀌거나 다시 펼칠 때 읽는다.
import { get, url } from '../../common/api.js';
import { formatNumber, formatSigned } from '../../common/format.js';

const COMPARE_API = '/api/v1/compare';
const BASE_PRESET = 'LAST_MONTH';
const DAYS = 7;
const TOP_CELLS = 3;
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';

const byId = (id) => document.getElementById(id);

export function createAbnormalPanel() {
    const box = byId('abnormal-box');
    const count = byId('abnormal-count');
    const list = byId('abnormal-list');
    const detail = byId('abnormal-detail');
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
        detail.href = url(`/stats/graph?${new URLSearchParams(graphParams()).toString()}`);
        if (box.open && loadedRegionId !== id) {
            load();
        }
    }

    // 그래프의 to는 끝을 빼므로 오늘 0시(비교의 to는 어제 날짜를 포함)
    function graphParams() {
        const today = new Date();
        return {
            targetType: 'REGION',
            targetId: String(regionId),
            interval: '1h',
            from: toDateText(addDays(today, -DAYS)),
            to: toDateText(today),
        };
    }

    function params() {
        const yesterday = addDays(new Date(), -1);
        return {
            targetType: 'REGION',
            targetId: String(regionId),
            from: toDateText(addDays(yesterday, -(DAYS - 1))),
            to: toDateText(yesterday),
            basePreset: BASE_PRESET,
        };
    }

    async function load() {
        if (regionId === null) {
            return;
        }
        const requestId = ++latestRequestId;
        const requested = params();
        try {
            const data = await get(COMPARE_API, requested, { toast: false });
            if (requestId !== latestRequestId) {
                return;
            }
            loadedRegionId = Number(requested.targetId);
            render(data);
        } catch {
            if (requestId === latestRequestId) {
                count.textContent = LOAD_ERROR;
                list.replaceChildren();
            }
        }
    }

    function render(data) {
        // 비교할 기준이 하나도 없으면(totalRate null) 비정상 여부를 말할 수 없다
        if (data.totalRate === null) {
            count.textContent = NO_DATA;
            list.replaceChildren();
            return;
        }
        const abnormal = data.cells.filter((cell) => cell.abnormal);
        count.textContent =
            abnormal.length > 0 ? `비정상 칸 ${formatNumber(abnormal.length)}개` : '비정상 변동 없음';
        const top = [...abnormal]
            .sort((a, b) => Math.abs(Number(b.rate)) - Math.abs(Number(a.rate)))
            .slice(0, TOP_CELLS);
        list.replaceChildren(...top.map(row));
    }

    return { setRegion };
}

// '+42%  금 18시 · 지난달 같은 요일보다 많음'
function row(cell) {
    const rate = Number(cell.rate);
    const item = document.createElement('div');
    item.className = 'row';
    const chip = document.createElement('span');
    chip.className = rate > 0 ? 'chip l3' : 'chip run';
    chip.textContent = `${formatSigned(rate, 0)}%`;
    const text = document.createElement('span');
    const hour = String(cell.hour).padStart(2, '0');
    text.textContent = `${WEEKDAYS[cell.weekday - 1]} ${hour}시 · 지난달 같은 요일보다 ${rate > 0 ? '많음' : '적음'}`;
    item.append(chip, text);
    return item;
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
