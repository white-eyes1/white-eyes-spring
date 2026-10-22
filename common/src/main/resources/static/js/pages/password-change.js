// 비밀번호 변경 화면(SCR-C-002 비밀번호 변경 영역, FNC-C-007). 두 앱이 함께 쓴다.
import { ApiError, patch, url } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 서버 규칙(InputRules.PASSWORD_REGEX, decisions/0013)과 같다. 최종 판단은 서버가 하고, 여기서는 입력 중 안내만 한다
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]{8,64}$/;
const RULE_HINT = '8~64자, 영문·숫자를 각각 1자 이상(특수문자 가능)';
const MISMATCH_MESSAGE = '일치하지 않습니다';
const DONE_MESSAGE = '비밀번호가 변경되었습니다';
const MOVE_DELAY_MS = 1200;
const STATS_PATH = '/stats';

const form = document.getElementById('password-form');
const submitButton = document.getElementById('password-submit');
const inputs = {
    currentPassword: form.elements.namedItem('currentPassword'),
    newPassword: form.elements.namedItem('newPassword'),
    newPasswordConfirm: form.elements.namedItem('newPasswordConfirm'),
};

inputs.newPassword.addEventListener('input', () => {
    checkNewPassword();
    checkConfirm();
});
inputs.newPasswordConfirm.addEventListener('input', checkConfirm);
inputs.currentPassword.addEventListener('input', () => setState('currentPassword', '', ''));
form.addEventListener('submit', submit);

function checkNewPassword() {
    const value = inputs.newPassword.value;
    if (value === '') {
        setState('newPassword', '', RULE_HINT);
        return;
    }
    setState('newPassword', PASSWORD_PATTERN.test(value) ? 'ok' : 'err', RULE_HINT);
}

function checkConfirm() {
    const confirm = inputs.newPasswordConfirm.value;
    if (confirm === '') {
        setState('newPasswordConfirm', '', '');
        return;
    }
    const matches = confirm === inputs.newPassword.value;
    setState('newPasswordConfirm', matches ? 'ok' : 'err', matches ? '' : MISMATCH_MESSAGE);
}

// state: 'ok' | 'err' | ''. 서버가 준 문구도 textContent로만 넣는다(code-conventions 5-3)
function setState(field, state, hint) {
    const input = inputs[field];
    input.classList.toggle('ok', state === 'ok');
    input.classList.toggle('err', state === 'err');
    const hintElement = form.querySelector(`[data-hint-for="${field}"]`);
    hintElement.textContent = hint;
    hintElement.classList.toggle('ok', state === 'ok');
    hintElement.classList.toggle('err', state === 'err');
}

async function submit(event) {
    event.preventDefault();
    const body = {
        currentPassword: inputs.currentPassword.value,
        newPassword: inputs.newPassword.value,
        newPasswordConfirm: inputs.newPasswordConfirm.value,
    };
    submitButton.disabled = true;
    try {
        await patch('/api/v1/me/password', body, { toast: false });
        showToast(DONE_MESSAGE);
        window.setTimeout(() => window.location.assign(url(STATS_PATH)), MOVE_DELAY_MS);
    } catch (error) {
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
    if (error.code === 'WRONG_PASSWORD') {
        setState('currentPassword', 'err', error.message);
        return;
    }
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
