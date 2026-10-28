// 대시보드 확인 창(SCR-C-003, 관리자 앱만): 구역 삭제·캠 삭제처럼 한 번 더 묻는 일에 같이 쓴다.
// 창 칸(#dash-confirm)은 templates/stats/main.html에 있다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).
import { ApiError } from '../../common/api.js';

/**
 * 확인 창을 만든다. 화면마다 한 번만 부른다.
 * @returns {{open: (options: {title: string, message: string, okLabel: string, onConfirm: () => Promise<void>, errorMessages?: Record<string, string>}) => void}}
 *   onConfirm이 ApiError로 거절되면 창을 닫지 않고 오류 문구를 창 안에 보인다(errorMessages에 코드가 있으면 그 문구, 없으면 서버 메시지).
 *   그때 onRejected도 부른다: 서버가 거절한 까닭(이미 가동됨, 이미 지워짐 등)은 화면 값이 낡았다는 뜻이라, 화면이 목록·지도를 다시 읽게 한다.
 *   ApiError가 아닌 실패(네트워크 끊김 등)는 창 안에 일반 안내를 보인다.
 */
export function createConfirmDialog() {
    const modal = document.getElementById('dash-confirm');
    const title = document.getElementById('dash-confirm-title');
    const message = document.getElementById('dash-confirm-message');
    const error = document.getElementById('dash-confirm-error');
    const ok = document.getElementById('dash-confirm-ok');

    let current = null;
    let working = false;

    modal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', close));
    modal.addEventListener('mousedown', (event) => {
        if (event.target === modal) {
            close();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !modal.hidden) {
            close();
        }
    });
    ok.addEventListener('click', confirm);

    function open(options) {
        current = options;
        title.textContent = options.title;
        message.textContent = options.message;
        ok.textContent = options.okLabel;
        showError('');
        ok.disabled = false;
        modal.hidden = false;
        ok.focus();
    }

    function close() {
        if (!working) {
            modal.hidden = true;
            current = null;
        }
    }

    async function confirm() {
        if (!current || working) {
            return;
        }
        working = true;
        ok.disabled = true;
        try {
            await current.onConfirm();
            working = false;
            modal.hidden = true;
            current = null;
        } catch (failure) {
            working = false;
            ok.disabled = false;
            if (!(failure instanceof ApiError)) {
                showError('요청을 보내지 못했습니다. 연결을 확인하고 다시 시도해 주세요.');
                return;
            }
            showError(current.errorMessages?.[failure.code] ?? failure.message);
            current.onRejected?.(failure);
        }
    }

    function showError(text) {
        error.textContent = text;
        error.hidden = !text;
    }

    return { open };
}
