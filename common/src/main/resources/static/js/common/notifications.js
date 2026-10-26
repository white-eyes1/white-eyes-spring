// 관리자 알림(FNC-A-048): 알림 스트림에 연결해 헤더 배지와 토스트를 갱신하고, 각 화면이 쓸 소식을 window로 전한다.
// 화면 쪽은 window.addEventListener('we:event-created' | 'we:camera-status', ...) 로 받는다.
import { get, url } from './api.js';
import { connectStream } from './sse.js';
import { showLinkToast } from './toast.js';

const TYPE_LABEL = { CONGESTION: '혼잡', SURGE: '급증', CAM_FAULT: '캠 이상' };
const LEVEL_LABEL = { 2: '주의', 3: '위험' };
const LEVEL_DOT = { 2: 'l2', 3: 'l3' };
const MAX_BADGE = 99;

const badge = document.getElementById('unchecked-badge');

/** 알림 스트림에 연결한다. 연결 직후 서버가 현재 미확인 수를 보내 주므로 따로 조회하지 않는다. */
export function startNotifications() {
    connectStream('/api/v1/notifications/stream', {
        'unchecked-count': ({ count }) => renderBadge(count),
        'event-created': (event) => {
            showLinkToast(toastOf(event));
            window.dispatchEvent(new CustomEvent('we:event-created', { detail: event }));
        },
        'camera-status': (status) => window.dispatchEvent(new CustomEvent('we:camera-status', { detail: status })),
    });
}

// 연결이 안 되는 동안 숫자가 낡지 않게, 화면을 열 때 한 번은 직접 읽어 둔다
export async function loadUncheckedCount() {
    try {
        const { count } = await get('/api/v1/events/unchecked-count', undefined, { toast: false });
        renderBadge(count);
    } catch {
        // 스트림이 이어지면 곧 같은 값이 온다
    }
}

function renderBadge(count) {
    if (!badge) {
        return;
    }
    badge.hidden = count <= 0;
    badge.textContent = count > MAX_BADGE ? `${MAX_BADGE}+` : String(count);
}

// 시안 문구: '[혼잡] 은행선화동 3구역 위험'. 급증·캠 이상은 단계가 없어 종류만 붙인다
function toastOf({ id, type, zoneName, level }) {
    const label = TYPE_LABEL[type] ?? type;
    const tail = type === 'CONGESTION' ? ` ${LEVEL_LABEL[level] ?? ''}`.trimEnd() : '';
    return {
        href: url(`/events?eventId=${encodeURIComponent(id)}`),
        dotClass: type === 'CONGESTION' ? (LEVEL_DOT[level] ?? 'l2') : type === 'SURGE' ? 'surge' : 'off',
        title: `[${label}] ${zoneName}${tail}`,
        note: '방금',
    };
}
