// 현재 인원(5초마다, 통계 SSE의 event: now, FNC-C-022·A-054)을 받아 두고, 화면의 '현재 인원' 숫자만 새로고침 없이 바꾼다.
// 숫자 칸은 bindNowCount로 구역 번호를 달아 두면 이벤트가 올 때마다 그 칸만 다시 쓴다. 이벤트에 그 구역이 없거나
// 한참 소식이 없으면(15초) 칸에 적어 둔 원래 값(서버가 준 currentCount 또는 1분 평균)으로 되돌린다.
import { formatNumber } from './format.js';

// 서버의 NOW_COUNT_FRESH_SEC(15초)와 같다. 이벤트가 끊기면 서버가 보내지 않으므로 화면도 이 시간이 지나면 값을 버린다
const FRESH_MS = 15_000;
const EXPIRE_CHECK_MS = 5_000;

const counts = new Map(); // zoneId -> { count, receivedAt }
const listeners = [];

window.setInterval(() => {
    if (counts.size > 0) {
        notify();
    }
}, EXPIRE_CHECK_MS);

/** event: now 의 items를 받아 둔다. 이전에 받은 값은 모두 대신한다(이벤트에 없는 구역은 신선하지 않다는 뜻). */
export function setNowCounts(items) {
    counts.clear();
    const receivedAt = Date.now();
    items.forEach((item) => counts.set(Number(item.zoneId), { count: Number(item.currentCount), receivedAt }));
    notify();
}

/** 이 구역의 신선한 현재 인원. 없으면 undefined */
export function nowCountOf(zoneId) {
    const found = counts.get(Number(zoneId));
    return found && Date.now() - found.receivedAt <= FRESH_MS ? found.count : undefined;
}

/** 신선한 값들의 합(구독 지역 전체). 하나도 없으면 null */
export function nowTotal() {
    const fresh = [...counts.keys()].map(nowCountOf).filter((count) => count !== undefined);
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
    element.dataset.nowZone = String(zoneId);
    element.dataset.nowFallback = fallback;
    element.dataset.nowSuffix = suffix;
    render(element);
}

function render(element) {
    const count = nowCountOf(element.dataset.nowZone);
    element.textContent =
        count === undefined ? element.dataset.nowFallback : `${formatNumber(count)}${element.dataset.nowSuffix}`;
}

function notify() {
    document.querySelectorAll('[data-now-zone]').forEach(render);
    listeners.forEach((listener) => listener());
}
