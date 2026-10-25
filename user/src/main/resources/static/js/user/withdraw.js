// 회원 탈퇴(FNC-U-004, 사용자 앱만). 내 정보 화면의 '회원 탈퇴' → 탈퇴 창(SCR-U-003)에서 비밀번호 입력 →
// 확인 창 한 번 더 → DELETE /api/v1/me. 성공하면 '탈퇴되었습니다'를 보이고 로그인 화면으로 간다(세션은 서버가 지웠다).
// 비밀번호가 틀리면(WRONG_PASSWORD) 확인 창을 닫고 비밀번호 칸 아래에 문구를 보인다.
import { ApiError, del, url } from '../common/api.js';
import { showToast } from '../common/toast.js';

const ME_API = '/api/v1/me';
const DONE_MESSAGE = '탈퇴되었습니다';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const LOGIN_PATH = '/login';
const MOVE_DELAY_MS = 1200;

const byId = (id) => document.getElementById(id);
const openButton = byId('withdraw-open');
const modal = byId('withdraw-modal');
const form = byId('withdraw-form');
const passwordInput = byId('withdraw-password');
const submitButton = byId('withdraw-submit');
const formError = byId('withdraw-error');
const passwordHint = form.querySelector('[data-error-for="password"]');
const confirmModal = byId('withdraw-confirm-modal');
const confirmOk = byId('withdraw-confirm-ok');

// 요청 진행 중이거나 탈퇴가 끝나 이동을 기다리는 중이면 창을 닫거나 다시 보내지 않는다
let busy = false;

function openModal() {
    form.reset();
    clearErrors();
    submitButton.disabled = true;
    modal.hidden = false;
    passwordInput.focus();
}

function closeModal() {
    if (busy) {
        return;
    }
    modal.hidden = true;
    passwordInput.value = '';
}

// 빈 값이면 버튼 비활성(screens.md SCR-U-003)
function onPasswordInput() {
    submitButton.disabled = passwordInput.value === '';
    clearErrors();
}

function openConfirm(event) {
    event.preventDefault();
    if (passwordInput.value === '' || busy) {
        return;
    }
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

function closeConfirm() {
    if (busy) {
        return;
    }
    confirmModal.hidden = true;
}

async function withdraw() {
    if (busy) {
        return;
    }
    busy = true;
    confirmOk.disabled = true;
    submitButton.disabled = true;
    try {
        await del(ME_API, { body: { password: passwordInput.value }, toast: false });
        passwordInput.value = '';
        showToast(DONE_MESSAGE);
        window.setTimeout(() => window.location.assign(url(LOGIN_PATH)), MOVE_DELAY_MS);
        return; // 이동할 때까지 busy를 풀지 않는다
    } catch (error) {
        busy = false;
        confirmModal.hidden = true;
        showError(error);
        submitButton.disabled = passwordInput.value === '';
        passwordInput.focus();
    }
}

function showError(error) {
    if (error instanceof ApiError && error.code === 'WRONG_PASSWORD') {
        showPasswordError(error.message);
    } else if (error instanceof ApiError && error.fieldErrors.length > 0) {
        showPasswordError(error.fieldErrors[0].message);
    } else {
        if (!(error instanceof ApiError)) {
            console.error(error);
        }
        formError.textContent = error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE;
        formError.hidden = false;
    }
}

function showPasswordError(message) {
    passwordHint.textContent = message;
    passwordHint.hidden = false;
    passwordInput.classList.add('err');
}

function clearErrors() {
    formError.hidden = true;
    formError.textContent = '';
    passwordHint.hidden = true;
    passwordHint.textContent = '';
    passwordInput.classList.remove('err');
}

openButton.addEventListener('click', openModal);
passwordInput.addEventListener('input', onPasswordInput);
form.addEventListener('submit', openConfirm);
modal.querySelectorAll('[data-withdraw-close]').forEach((button) => button.addEventListener('click', closeModal));
confirmModal.querySelectorAll('[data-withdraw-confirm-close]').forEach((button) => button.addEventListener('click', closeConfirm));
confirmOk.addEventListener('click', withdraw);
document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') {
        return;
    }
    // 겹쳐 열려 있으면 위의 확인 창부터 닫는다
    if (!confirmModal.hidden) {
        closeConfirm();
    } else if (!modal.hidden) {
        closeModal();
    }
});
