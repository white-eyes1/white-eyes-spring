// 화면 아래 오른쪽에 잠깐 보이는 알림(we.css .toast). 서버 값은 textContent로만 넣는다(code-conventions 5-3).

const CONTAINER_ID = 'toast-stack';
// 새 이벤트 알림은 시안(SCR-A-010)처럼 헤더 아래 오른쪽 위에 쌓는다. 오류 안내 토스트(아래 오른쪽)와 섞이지 않게 따로 둔다
const NOTICE_CONTAINER_ID = 'notice-stack';
const DEFAULT_DURATION_MS = 5000;

/** 토스트를 보인다. durationMs 뒤에 사라진다. */
export function showToast(message, durationMs = DEFAULT_DURATION_MS) {
    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.setAttribute('role', 'alert');
    toast.textContent = message;
    getContainer().append(toast);
    window.setTimeout(() => toast.remove(), durationMs);
    return toast;
}

/**
 * 누르면 href로 가는 알림(SCR-A-010 시안의 새 이벤트 토스트): 색 점 + 제목 + 보조 문구.
 * dotClass는 we.css .dot 의 단계 클래스(l1·l2·l3·off)나 surge.
 */
export function showLinkToast({ href, dotClass, title, note }, durationMs = DEFAULT_DURATION_MS) {
    const toast = document.createElement('a');
    toast.className = 'toast toast-link';
    toast.href = href;
    toast.setAttribute('role', 'alert');
    const dot = document.createElement('span');
    dot.className = `dot ${dotClass}`;
    const strong = document.createElement('b');
    strong.textContent = title;
    const small = document.createElement('span');
    small.className = 'muted';
    small.textContent = note;
    toast.append(dot, strong, small);
    getContainer(NOTICE_CONTAINER_ID, 'toast-stack toast-stack-top').append(toast);
    window.setTimeout(() => toast.remove(), durationMs);
    return toast;
}

function getContainer(id = CONTAINER_ID, className = 'toast-stack') {
    let container = document.getElementById(id);
    if (!container) {
        container = document.createElement('div');
        container.id = id;
        container.className = className;
        document.body.append(container);
    }
    return container;
}
