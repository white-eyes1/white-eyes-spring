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
    // 창 안의 요소를 한 번에 찾아 둔다
    const modal = document.getElementById('dash-confirm');
    const title = document.getElementById('dash-confirm-title');
    const message = document.getElementById('dash-confirm-message');
    const error = document.getElementById('dash-confirm-error');
    const ok = document.getElementById('dash-confirm-ok');

    // current: 지금 열린 확인 요청(없으면 null). working: onConfirm이 진행 중인지(중복 클릭·닫기 방지)
    let current = null;
    let working = false;

    // 닫기 버튼, 창 바깥(배경) 클릭, Esc로 닫는다. 진행 중에는 close가 무시한다
    modal
        .querySelectorAll('[data-close]')
        .forEach((button) => button.addEventListener('click', close));
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
    // '확인' 버튼
    ok.addEventListener('click', confirm);

    // 창을 연다: 제목·메시지·버튼 글자를 채우고 이전 오류는 지운다
    function open(options) {
        current = options;
        title.textContent = options.title;
        message.textContent = options.message;
        ok.textContent = options.okLabel;
        showError('');
        ok.disabled = false;
        modal.hidden = false;
        // 확인 버튼에 포커스를 주어 키보드로 바로 결정할 수 있게 한다
        ok.focus();
    }

    function close() {
        // 서버 요청이 진행 중일 때는 닫지 않는다(요청 결과를 놓치지 않으려는 것)
        if (!working) {
            modal.hidden = true;
            current = null;
        }
    }

    // 확인을 눌렀을 때: onConfirm을 실행하고 성공하면 창을 닫는다
    async function confirm() {
        // 열린 요청이 없거나 이미 진행 중이면 무시(중복 클릭 방지)
        if (!current || working) {
            return;
        }
        // 진행 중 표시와 버튼 비활성
        working = true;
        ok.disabled = true;
        try {
            await current.onConfirm();
            working = false;
            modal.hidden = true;
            current = null;
        // onConfirm이 예외를 던지면 창을 닫지 않고 오류를 보인다
        } catch (failure) {
            working = false;
            ok.disabled = false;
            // 서버 응답이 아닌 실패(네트워크 끊김 등)는 일반 안내
            if (!(failure instanceof ApiError)) {
                showError('요청을 보내지 못했습니다. 연결을 확인하고 다시 시도해 주세요.');
                return;
            }
            // 호출한 쪽이 정한 코드별 문구가 있으면 그것을, 없으면 서버 메시지를 보인다
            showError(current.errorMessages?.[failure.code] ?? failure.message);
            // 서버가 거절했다는 것은 화면 값이 낡았다는 뜻일 수 있어 호출한 쪽이 다시 읽게 알린다
            current.onRejected?.(failure);
        }
    }

    // 오류 문구를 보이거나(빈 문자열이면) 숨긴다
    function showError(text) {
        error.textContent = text;
        error.hidden = !text;
    }

    return { open };
}
