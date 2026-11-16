// 현재 인원(5초마다, 통계 SSE의 event: now, FNC-C-022·A-054)을 받아 두고, 화면의 '현재 인원' 숫자만 새로고침 없이 바꾼다.
// 숫자 칸은 bindNowCount로 구역 번호를 달아 두면 이벤트가 올 때마다 그 칸만 다시 쓴다. 이벤트에 그 구역이 없거나
// 한참 소식이 없으면(15초) 칸에 적어 둔 원래 값(서버가 준 currentCount 또는 1분 평균)으로 되돌린다.
import { formatNumber } from './format.js';

// 서버의 NOW_COUNT_FRESH_SEC(15초)와 같다. 이벤트가 끊기면 서버가 보내지 않으므로 화면도 이 시간이 지나면 값을 버린다
// 신선도 15초 / 만료 검사 주기 5초
const FRESH_MS = 15_000;
const EXPIRE_CHECK_MS = 5_000;

// 구역별 최신 현재 인원 저장소
const counts = new Map(); // zoneId -> { count, receivedAt }
// 값이 바뀔 때 알림받을 함수들
const listeners = [];

// 주기적으로 칸을 다시 그려, 새 이벤트가 안 와도 오래된 값이 원래 값으로 돌아가게 한다
window.setInterval(() => {
    if (counts.size > 0) {
        notify();
    }
}, EXPIRE_CHECK_MS);

/** event: now 의 items를 받아 둔다. 이전에 받은 값은 모두 대신한다(이벤트에 없는 구역은 신선하지 않다는 뜻). */
export function setNowCounts(items) {
    // 이전 값은 모두 버린다: 새 이벤트에 없는 구역은 서버가 신선하다고 보지 않는다는 뜻이기 때문
    counts.clear();
    // 같은 이벤트에서 온 값은 수신 시각이 같다
    const receivedAt = Date.now();
    items.forEach((item) =>
        // 서버 값이 문자열일 수 있어 숫자로 통일한다
        counts.set(Number(item.zoneId), { count: Number(item.currentCount), receivedAt }),
    );
    // 값이 바뀌었으니 화면 칸과 구독자를 갱신한다
    notify();
}

/** 이 구역의 신선한 현재 인원. 없으면 undefined */
export function nowCountOf(zoneId) {
    // 15초가 지난 값은 없는 것으로 본다
    const found = counts.get(Number(zoneId));
    return found && Date.now() - found.receivedAt <= FRESH_MS ? found.count : undefined;
}

/** 신선한 값들의 합(구독 지역 전체). 하나도 없으면 null */
export function nowTotal() {
    // 신선한 구역만 모은다
    const fresh = [...counts.keys()].map(nowCountOf).filter((count) => count !== undefined);
    // 신선한 값이 없으면 0이 아니라 null: '0명'과 '모름'을 구분하기 위해서다
    return fresh.length === 0 ? null : fresh.reduce((sum, count) => sum + count, 0);
}

/** 값이 바뀔 때마다(또는 만료를 확인할 때마다) 부른다. 구역 칸이 아닌 합계 같은 곳을 직접 다시 쓸 때 쓴다. */
export function onNowCounts(listener) {
    listeners.push(listener);
}

/**
 * 숫자 칸 하나를 이 구역의 현재 인원에 묶는다.
 * @param {HTMLElement} element 숫자를 넣을 칸
 * @param {number} zoneId 구역 번호
 * @param {string} fallback 신선한 값이 없을 때 보일 글자(서버가 준 currentCount 또는 1분 평균)
 * @param {string} suffix 숫자 뒤에 붙일 글자(예: '명')
 */
export function bindNowCount(element, zoneId, fallback, suffix = '') {
    // 나중에 다시 그릴 때 읽을 정보를 data 속성에 달아 둔다
    element.dataset.nowZone = String(zoneId);
    element.dataset.nowFallback = fallback;
    element.dataset.nowSuffix = suffix;
    // 바로 한 번 그려 첫 화면에 서버 값이 보이게 한다
    render(element);
}

function render(element) {
    // 신선한 값이 있으면 그 값, 없으면 서버가 준 원래 값
    const count = nowCountOf(element.dataset.nowZone);
    element.textContent =
        count === undefined
            ? element.dataset.nowFallback
            : `${formatNumber(count)}${element.dataset.nowSuffix}`;
}

function notify() {
    // 화면에 있는 모든 숫자 칸을 다시 쓰고
    document.querySelectorAll('[data-now-zone]').forEach(render);
    // 합계 등 칸이 아닌 곳은 구독자가 직접 갱신한다
    listeners.forEach((listener) => listener());
}
