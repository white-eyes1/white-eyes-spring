// 화면 아래 오른쪽에 잠깐 보이는 알림(we.css .toast). 서버 값은 textContent로만 넣는다(code-conventions 5-3).

// 오류 안내 토스트가 쌓이는 컨테이너 id(화면 아래 오른쪽)
const CONTAINER_ID = 'toast-stack';
// 새 이벤트 알림은 시안(SCR-A-010)처럼 헤더 아래 오른쪽 위에 쌓는다. 오류 안내 토스트(아래 오른쪽)와 섞이지 않게 따로 둔다
const NOTICE_CONTAINER_ID = 'notice-stack';
// 토스트 기본 표시 시간 5초
const DEFAULT_DURATION_MS = 5000;

/** 토스트를 보인다. durationMs 뒤에 사라진다. */
export function showToast(message, durationMs = DEFAULT_DURATION_MS) {
    // textContent로 넣어 서버 문구에 HTML이 섞여도 실행되지 않게 한다(XSS 방지)
    const toast = document.createElement('div');
    toast.className = 'toast';
    // 스크린리더가 즉시 읽도록 alert 역할을 준다
    toast.setAttribute('role', 'alert');
    toast.textContent = message;
    // 컨테이너가 없으면 만들고 그 안에 쌓는다
    getContainer().append(toast);
    // 표시 시간이 지나면 스스로 지운다
    window.setTimeout(() => toast.remove(), durationMs);
    return toast;
}

/**
 * 누르면 href로 가는 알림(SCR-A-010 시안의 새 이벤트 토스트): 색 점 + 제목 + 보조 문구.
 * dotClass는 we.css .dot 의 단계 클래스(l1·l2·l3·off)나 surge.
 */
export function showLinkToast({ href, dotClass, title, note }, durationMs = DEFAULT_DURATION_MS) {
    // 누르면 이동해야 하므로 a 태그로 만든다
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
    // 이벤트 알림은 위쪽 컨테이너에 쌓는다(오류 토스트와 구분)
    getContainer(NOTICE_CONTAINER_ID, 'toast-stack toast-stack-top').append(toast);
    window.setTimeout(() => toast.remove(), durationMs);
    return toast;
}

// id로 컨테이너를 찾고, 없으면 만든다(첫 토스트가 뜰 때 한 번 생성)
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
