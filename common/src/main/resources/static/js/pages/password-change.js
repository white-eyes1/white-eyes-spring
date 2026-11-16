// 비밀번호 변경 화면(SCR-C-002 비밀번호 변경 영역, FNC-C-007). 두 앱이 함께 쓴다.
import { ApiError, patch, url } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 서버 규칙(InputRules.PASSWORD_REGEX, decisions/0013)과 같다. 최종 판단은 서버가 하고, 여기서는 입력 중 안내만 한다
// 영문 1자 이상, 숫자 1자 이상(앞쪽 lookahead), 공백 없는 출력 가능한 ASCII 8~64자
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]{8,64}$/;
// 규칙 안내 문구
const RULE_HINT = '8~64자, 영문·숫자를 각각 1자 이상(특수문자 가능)';
const MISMATCH_MESSAGE = '일치하지 않습니다';
const DONE_MESSAGE = '비밀번호가 변경되었습니다';
// 변경 완료 토스트를 잠깐 보여 준 뒤 이동하는 지연(1.2초)
const MOVE_DELAY_MS = 1200;
// 변경 후 이동할 화면(통계 메인)
const STATS_PATH = '/stats';

// 폼과 저장 버튼, 입력칸 세 개
const form = document.getElementById('password-form');
const submitButton = document.getElementById('password-submit');
const inputs = {
    currentPassword: form.elements.namedItem('currentPassword'),
    newPassword: form.elements.namedItem('newPassword'),
    newPasswordConfirm: form.elements.namedItem('newPasswordConfirm'),
};

// 새 비밀번호를 바꾸면 규칙 검사와 확인 칸 일치 검사를 둘 다 다시 한다
inputs.newPassword.addEventListener('input', () => {
    checkNewPassword();
    checkConfirm();
});
inputs.newPasswordConfirm.addEventListener('input', checkConfirm);
// 현재 비밀번호를 고치기 시작하면 이전 서버 오류 표시를 지운다
inputs.currentPassword.addEventListener('input', () => setState('currentPassword', '', ''));
form.addEventListener('submit', submit);

function checkNewPassword() {
    // 입력이 비어 있으면 상태 표시 없이 안내만
    const value = inputs.newPassword.value;
    if (value === '') {
        setState('newPassword', '', RULE_HINT);
        return;
    }
    // 규칙에 맞으면 ok, 아니면 err
    setState('newPassword', PASSWORD_PATTERN.test(value) ? 'ok' : 'err', RULE_HINT);
}

function checkConfirm() {
    // 확인 칸이 비어 있으면 표시 없음
    const confirm = inputs.newPasswordConfirm.value;
    if (confirm === '') {
        setState('newPasswordConfirm', '', '');
        return;
    }
    // 새 비밀번호와 같은지
    const matches = confirm === inputs.newPassword.value;
    setState('newPasswordConfirm', matches ? 'ok' : 'err', matches ? '' : MISMATCH_MESSAGE);
}

// state: 'ok' | 'err' | ''. 서버가 준 문구도 textContent로만 넣는다(code-conventions 5-3)
function setState(field, state, hint) {
    // 입력칸과 그 아래 안내 줄의 표시를 바꾼다
    const input = inputs[field];
    input.classList.toggle('ok', state === 'ok');
    input.classList.toggle('err', state === 'err');
    const hintElement = form.querySelector(`[data-hint-for="${field}"]`);
    hintElement.textContent = hint;
    // 빈 안내 줄이 입력 칸 사이 간격을 벌리지 않게 숨긴다(시안과 같은 간격)
    hintElement.hidden = hint === '';
    hintElement.classList.toggle('ok', state === 'ok');
    hintElement.classList.toggle('err', state === 'err');
}

async function submit(event) {
    // 폼 전송을 막고 fetch로 처리한다
    event.preventDefault();
    // 세 값을 그대로 서버에 보낸다. 일치·규칙의 최종 판단은 서버가 한다
    const body = {
        currentPassword: inputs.currentPassword.value,
        newPassword: inputs.newPassword.value,
        newPasswordConfirm: inputs.newPasswordConfirm.value,
    };
    submitButton.disabled = true;
    try {
        // toast:false: 오류는 입력 아래에 보이기 위해 직접 처리한다
        await patch('/api/v1/me/password', body, { toast: false });
        showToast(DONE_MESSAGE);
        // 안내 토스트를 볼 수 있게 잠깐 뒤에 이동한다
        window.setTimeout(() => window.location.assign(url(STATS_PATH)), MOVE_DELAY_MS);
    } catch (error) {
        // 실패하면 다시 시도할 수 있게 버튼을 켠다(성공하면 화면을 떠나므로 켜지 않는다)
        submitButton.disabled = false;
        showError(error);
    }
}

function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        showToast('일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
        return;
    }
    // 현재 비밀번호가 틀린 경우는 그 칸 아래에 서버 문구를 보인다
    if (error.code === 'WRONG_PASSWORD') {
        setState('currentPassword', 'err', error.message);
        return;
    }
    // 서버의 필드별 검증 오류를 해당 칸에 보인다
    if (error.fieldErrors.length > 0) {
        for (const { field, message } of error.fieldErrors) {
            if (field in inputs) {
                setState(field, 'err', message);
            }
        }
        return;
    }
    showToast(error.message);
}
