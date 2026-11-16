// 통계 패널의 비정상 인원 변동 접기 영역(SCR-C-003, FNC-C-020).
// 펼칠 때 GET /api/v1/compare(어제까지 최근 7일, basePreset=LAST_MONTH)로 읽고, 비정상 칸 수와 |증감률|이 큰 칸 3개를 보인다.
// '자세히'는 같은 지역·같은 7일의 통계 그래프(1시간 단위, SCR-C-004)를 연다(기간 비교 화면은 결정 0016으로 없앰).
// 시간별 통계라 1분마다 바뀌지 않으므로 새 1분 통계(SSE)에는 다시 읽지 않고, 지역이 바뀌거나 다시 펼칠 때 읽는다.
import { get, url } from '../../common/api.js';
import { formatNumber, formatSigned } from '../../common/format.js';

// 기간 비교 API와 비교 기준(지난달 같은 요일·시간)
const COMPARE_API = '/api/v1/compare';
const BASE_PRESET = 'LAST_MONTH';
// 비교할 일 수(어제까지 최근 7일)
const DAYS = 7;
// 화면에 보일 가장 변동이 큰 칸 수
const TOP_CELLS = 3;
// 요일 번호(1=월 ~ 7=일) -> 글자
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
const NO_DATA = '데이터 없음';
const LOAD_ERROR = '불러오지 못했습니다.';

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 접기 영역 하나를 만든다. 화면 쪽은 setRegion만 부른다
export function createAbnormalPanel() {
    const box = byId('abnormal-box');
    const count = byId('abnormal-count');
    const list = byId('abnormal-list');
    const detail = byId('abnormal-detail');
    // regionId: 현재 지역, loadedRegionId: 읽어서 그린 지역, latestRequestId: 늦게 온 응답을 버리는 번호
    let regionId = null;
    let loadedRegionId = null;
    let latestRequestId = 0;

    // 접기 영역을 펼칠 때 값을 읽는다(접혀 있는 동안은 읽지 않는다)
    box.addEventListener('toggle', () => {
        if (box.open) {
            load();
        }
    });

    /** 통계 대상 지역이 바뀌면 부른다. 펼쳐 있으면 바로 다시 읽는다 */
    function setRegion(id) {
        // '자세히' 링크는 같은 지역·같은 7일의 통계 그래프 화면을 연다
        regionId = id;
        detail.href = url(`/stats/graph?${new URLSearchParams(graphParams()).toString()}`);
        // 펼쳐 있고 아직 이 지역을 읽은 적이 없으면 읽는다
        if (box.open && loadedRegionId !== id) {
            load();
        }
    }

    // 그래프의 to는 끝을 빼므로 오늘 0시(비교의 to는 어제 날짜를 포함)
    // 통계 그래프 화면 주소의 쿼리. 그래프의 to는 끝을 포함하지 않으므로 오늘 0시로 준다
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

    // 비교 API 쿼리: 어제를 끝으로 7일. 비교 기준은 지난달 같은 요일
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

    // 지역을 읽어 요약과 목록을 그린다
    async function load() {
        // 지역이 아직 정해지지 않았으면 읽지 않는다
        if (regionId === null) {
            return;
        }
        // 요청 번호로 가장 마지막 요청의 응답만 그린다
        const requestId = ++latestRequestId;
        const requested = params();
        try {
            // 오류 토스트는 끄고 영역 안에 문구로 보인다
            const data = await get(COMPARE_API, requested, { toast: false });
            if (requestId !== latestRequestId) {
                return;
            }
            // 이 지역을 읽었음을 기록해 같은 지역으로 다시 펼쳐도 중복해서 읽지 않게 한다
            loadedRegionId = Number(requested.targetId);
            render(data);
        } catch {
            if (requestId === latestRequestId) {
                // 읽기 실패: 오류 문구를 보이고 목록은 비운다
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
        // abnormal이 true인 칸(비정상으로 판정된 요일·시간 칸)만
        const abnormal = data.cells.filter((cell) => cell.abnormal);
        count.textContent =
            abnormal.length > 0
                ? `비정상 칸 ${formatNumber(abnormal.length)}개`
                : '비정상 변동 없음';
        // 증감률의 절댓값이 큰 순서대로 상위 칸만 보인다
        const top = [...abnormal]
            .sort((a, b) => Math.abs(Number(b.rate)) - Math.abs(Number(a.rate)))
            .slice(0, TOP_CELLS);
        list.replaceChildren(...top.map(row));
    }

    return { setRegion };
}

// '+42%  금 18시 · 지난달 같은 요일보다 많음'
function row(cell) {
    // 한 칸을 한 줄로 보인다. 증가는 위험색 칩, 감소는 다른 칩 색
    const rate = Number(cell.rate);
    const item = document.createElement('div');
    item.className = 'row';
    const chip = document.createElement('span');
    chip.className = rate > 0 ? 'chip l3' : 'chip run';
    chip.textContent = `${formatSigned(rate, 0)}%`;
    const text = document.createElement('span');
    // 두 자리 시각
    const hour = String(cell.hour).padStart(2, '0');
    // 요일 번호는 1부터라 배열에서는 1을 빼서 읽는다
    text.textContent = `${WEEKDAYS[cell.weekday - 1]} ${hour}시 · 지난달 같은 요일보다 ${rate > 0 ? '많음' : '적음'}`;
    item.append(chip, text);
    return item;
}

function addDays(date, days) {
    // 시·분·초 없이 날짜만 복사한 뒤 일 수를 더한다(시각이 날짜 계산에 끼어들지 않게)
    const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    copy.setDate(copy.getDate() + days);
    return copy;
}

function toDateText(date) {
    // 서버가 받는 날짜 형식(yyyy-MM-dd)
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
