// 회원가입 화면(SCR-U-001). 아이디 중복 확인(FNC-U-001)을 마쳐야 가입하기(FNC-U-002)가 켜진다.
import { ApiError, get, post, url } from '../../common/api.js';

const AVAILABLE_MESSAGE = '사용할 수 있습니다';
const TAKEN_MESSAGE = '이미 사용 중입니다';
const SIGNUP_DONE_MESSAGE = '가입 신청이 완료되었습니다. 관리자 승인 후 이용할 수 있습니다.';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const FIELDS = ['loginId', 'password', 'passwordConfirm', 'name', 'email', 'phone'];

const form = document.getElementById('signup-form');
const loginIdInput = form.elements.namedItem('loginId');
const checkButton = document.getElementById('check-login-id');
const submitButton = document.getElementById('signup-submit');
const loginIdStatus = document.getElementById('login-id-status');
const formError = document.getElementById('form-error');

// 중복 확인을 통과한 아이디. 아이디 칸을 바꾸면 비운다
let checkedLoginId = null;

loginIdInput.addEventListener('input', () => {
    checkedLoginId = null;
    loginIdStatus.textContent = '';
    setFieldError('loginId', '');
    updateSubmitButton();
});

checkButton.addEventListener('click', async () => {
    const loginId = loginIdInput.value;
    loginIdStatus.textContent = '';
    setFieldError('loginId', '');
    formError.textContent = '';
    checkButton.disabled = true;
    try {
        const { available } = await get('/api/v1/signup/check-id', { loginId });
        // 응답을 기다리는 동안 아이디를 바꿨으면 이 결과는 쓰지 않는다
        if (loginIdInput.value !== loginId) {
            return;
        }
        checkedLoginId = available ? loginId : null;
        loginIdStatus.textContent = available ? AVAILABLE_MESSAGE : TAKEN_MESSAGE;
    } catch (error) {
        showError(error);
    } finally {
        checkButton.disabled = false;
        updateSubmitButton();
    }
});

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (submitButton.disabled) {
        return;
    }
    clearErrors();
    submitButton.disabled = true;
    try {
        await post('/api/v1/signup', readForm());
        window.alert(SIGNUP_DONE_MESSAGE);
        window.location.href = url('/login');
    } catch (error) {
        // 확인한 뒤 다른 사람이 먼저 가입한 경우라 다시 확인해야 한다
        if (error instanceof ApiError && error.code === 'DUPLICATE_LOGIN_ID') {
            checkedLoginId = null;
            loginIdStatus.textContent = '';
            setFieldError('loginId', error.message);
        } else {
            showError(error);
        }
        updateSubmitButton();
    }
});

function updateSubmitButton() {
    submitButton.disabled = checkedLoginId === null || checkedLoginId !== loginIdInput.value;
}

function readForm() {
    return Object.fromEntries(FIELDS.map((field) => [field, form.elements.namedItem(field).value]));
}

function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        formError.textContent = DEFAULT_ERROR_MESSAGE;
        return;
    }
    if (error.fieldErrors.length === 0) {
        formError.textContent = error.message;
        return;
    }
    error.fieldErrors.forEach(({ field, message }) => setFieldError(field, message));
}

// 칸이 없는 필드의 오류는 폼 위 오류 자리에 보인다
function setFieldError(field, message) {
    const target = form.querySelector(`[data-error-for="${field}"]`) ?? formError;
    target.textContent = message;
}

function clearErrors() {
    FIELDS.forEach((field) => setFieldError(field, ''));
    formError.textContent = '';
}
