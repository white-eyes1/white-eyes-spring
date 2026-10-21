// 화면 아래 오른쪽에 잠깐 보이는 알림(we.css .toast). 서버 값은 textContent로만 넣는다(code-conventions 5-3).

const CONTAINER_ID = 'toast-stack';
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

function getContainer() {
    let container = document.getElementById(CONTAINER_ID);
    if (!container) {
        container = document.createElement('div');
        container.id = CONTAINER_ID;
        container.className = 'toast-stack';
        document.body.append(container);
    }
    return container;
}
