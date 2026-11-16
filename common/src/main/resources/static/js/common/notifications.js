// 관리자 알림(FNC-A-048): 알림 스트림에 연결해 헤더 배지와 토스트를 갱신하고, 각 화면이 쓸 소식을 window로 전한다.
// 화면 쪽은 window.addEventListener('we:event-created' | 'we:camera-status', ...) 로 받는다.
import { get, url } from './api.js';
import { connectStream } from './sse.js';
import { showLinkToast } from './toast.js';

// 이벤트 종류 코드 -> 화면 라벨. 혼잡/급증/카메라 이상
const TYPE_LABEL = { CONGESTION: '혼잡', SURGE: '급증', CAM_FAULT: '캠 이상' };
// 혼잡 단계 번호 -> 라벨(주의·위험). 알림에는 2·3단계만 쓴다
const LEVEL_LABEL = { 2: '주의', 3: '위험' };
// 단계 번호 -> 토스트 점 색 CSS 클래스
const LEVEL_DOT = { 2: 'l2', 3: 'l3' };
// 배지에 보일 최대 숫자. 넘으면 '99+'
const MAX_BADGE = 99;
// 새 이벤트 토스트는 10초 동안 보인다(screens.md SCR-A-013 '새 이벤트 토스트'). 토스트 기본값(5초)을 쓰지 않는다
export const EVENT_TOAST_MS = 10000;

// 헤더의 미확인 배지 요소. 사용자 헤더에는 없으므로 null일 수 있다
const badge = document.getElementById('unchecked-badge');

/** 알림 스트림에 연결한다. 연결 직후 서버가 현재 미확인 수를 보내 주므로 따로 조회하지 않는다. */
export function startNotifications() {
    // 서버 SSE 이벤트 이름별 처리기를 단다
    connectStream('/api/v1/notifications/stream', {
        // 미확인 건수가 바뀌면 배지를 다시 그린다
        'unchecked-count': ({ count }) => renderBadge(count),
        'event-created': (event) => {
            // 새 이벤트는 화면 위 토스트로 알리고
            showLinkToast(toastOf(event), EVENT_TOAST_MS);
            // 이벤트 목록 등 현재 화면도 반응할 수 있게 window 이벤트로 중계한다
            window.dispatchEvent(new CustomEvent('we:event-created', { detail: event }));
        },
        // 카메라 상태 변경도 같은 방식으로 중계한다
        'camera-status': (status) =>
            window.dispatchEvent(new CustomEvent('we:camera-status', { detail: status })),
    });
}

// 연결이 안 되는 동안 숫자가 낡지 않게, 화면을 열 때 한 번은 직접 읽어 둔다
export async function loadUncheckedCount() {
    try {
        const { count } = await get('/api/v1/events/unchecked-count', undefined, { toast: false });
        // 실패해도 토스트를 띄우지 않는다(스트림이 같은 값을 곧 보내 주기 때문)
        renderBadge(count);
    } catch {
        // 스트림이 이어지면 곧 같은 값이 온다
    }
}

// 배지 요소가 없는 화면이면 무시
function renderBadge(count) {
    if (!badge) {
        return;
    }
    // 0건이면 배지를 숨긴다
    badge.hidden = count <= 0;
    // 99건을 넘으면 '99+'로 줄여 폭을 지킨다
    badge.textContent = count > MAX_BADGE ? `${MAX_BADGE}+` : String(count);
}

// 시안 문구: '[혼잡] 은행선화동 3구역 위험'. 급증·캠 이상은 단계가 없어 종류만 붙인다
// 알림 하나를 토스트 표시 정보로 바꾼다. 누르면 해당 이벤트가 선택된 이벤트 화면으로 간다
function toastOf({ id, type, zoneName, level }) {
    // 모르는 종류는 코드를 그대로 보인다
    const label = TYPE_LABEL[type] ?? type;
    // 단계 라벨은 혼잡일 때만 붙인다(앞 공백 포함해 만든 뒤 단계를 모르면 빈 문자열이라 trimEnd로 공백 제거)
    const tail = type === 'CONGESTION' ? ` ${LEVEL_LABEL[level] ?? ''}`.trimEnd() : '';
    return {
        // 이벤트 id는 주소에 들어가므로 인코딩한다
        href: url(`/events?eventId=${encodeURIComponent(id)}`),
        // 점 색: 혼잡은 단계별 색, 급증은 surge, 그 밖(캠 이상)은 off
        dotClass:
            type === 'CONGESTION' ? (LEVEL_DOT[level] ?? 'l2') : type === 'SURGE' ? 'surge' : 'off',
        title: `[${label}] ${zoneName}${tail}`,
        note: '방금',
    };
}
