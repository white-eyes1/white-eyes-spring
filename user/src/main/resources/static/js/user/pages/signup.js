// 회원가입 화면(SCR-U-001). 아이디 중복 확인(FNC-U-001)을 마쳐야 가입하기(FNC-U-002)가 켜진다.
import { ApiError, get, post, url } from '../../common/api.js';

// 중복 확인 결과 문구와 기본 오류 문구
const AVAILABLE_MESSAGE = '사용할 수 있습니다';
const TAKEN_MESSAGE = '이미 사용 중입니다';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
// 서버로 보내는 입력 필드 이름. 화면의 name 속성과 같다
const FIELDS = ['loginId', 'password', 'passwordConfirm', 'name', 'email', 'phone'];

// 폼과 버튼, 안내 문구 요소
const form = document.getElementById('signup-form');
const loginIdInput = form.elements.namedItem('loginId');
const checkButton = document.getElementById('check-login-id');
const submitButton = document.getElementById('signup-submit');
const loginIdStatus = document.getElementById('login-id-status');
const formError = document.getElementById('form-error');

// 중복 확인을 통과한 아이디. 아이디 칸을 바꾸면 비운다
let checkedLoginId = null;

// 아이디 칸을 바꾸면 이전 중복 확인은 무효가 되므로 결과·오류를 지우고 가입 버튼을 다시 계산한다
loginIdInput.addEventListener('input', () => {
    checkedLoginId = null;
    loginIdStatus.textContent = '';
    setFieldError('loginId', '');
    updateSubmitButton();
});

// '중복 확인' 버튼: 서버에 아이디가 사용 가능한지 묻는다
checkButton.addEventListener('click', async () => {
    // 확인을 시작한 시점의 아이디를 기억해 둔다(응답이 올 때 바뀌었는지 비교)
    const loginId = loginIdInput.value;
    loginIdStatus.textContent = '';
    setFieldError('loginId', '');
    formError.textContent = '';
    // 요청 중 중복 클릭 방지
    checkButton.disabled = true;
    try {
        // 입력 오류는 칸 아래에 보이려고 토스트를 끈다
        const { available } = await get('/api/v1/signup/check-id', { loginId }, { toast: false });
        // 응답을 기다리는 동안 아이디를 바꿨으면 이 결과는 쓰지 않는다
        if (loginIdInput.value !== loginId) {
            return;
        }
        // 사용할 수 있을 때만 '확인한 아이디'로 기억한다
        checkedLoginId = available ? loginId : null;
        loginIdStatus.textContent = available ? AVAILABLE_MESSAGE : TAKEN_MESSAGE;
    } catch (error) {
        showError(error);
    } finally {
        // 성공이든 실패든 버튼을 다시 켜고 가입 버튼 상태를 갱신한다
        checkButton.disabled = false;
        updateSubmitButton();
    }
});

// 가입하기: 기본 전송을 막고 fetch로 보낸다
form.addEventListener('submit', async (event) => {
    event.preventDefault();
    // 중복 확인을 하지 않았으면 서버에 보내지 않는다
    if (submitButton.disabled) {
        return;
    }
    clearErrors();
    submitButton.disabled = true;
    try {
        // 입력값 전체를 JSON으로 보낸다
        await post('/api/v1/signup', readForm(), { toast: false });
        // 완료 안내는 로그인 화면이 signup=done 으로 받아 화면 안에 보인다(alert는 막히거나 놓칠 수 있다)
        window.location.href = url('/login?signup=done');
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

// 가입 버튼은 지금 아이디가 중복 확인을 통과한 그 아이디일 때만 켠다
function updateSubmitButton() {
    submitButton.disabled = checkedLoginId === null || checkedLoginId !== loginIdInput.value;
}

// 이름 -> 입력값 객체
function readForm() {
    return Object.fromEntries(FIELDS.map((field) => [field, form.elements.namedItem(field).value]));
}

// 오류를 화면에 보인다: 예상 밖 예외는 기본 문구, 필드 오류가 없으면 폼 위 오류, 있으면 칸마다
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
// data-error-for로 칸 아래 오류 자리를 찾는다
function setFieldError(field, message) {
    const target = form.querySelector(`[data-error-for="${field}"]`) ?? formError;
    target.textContent = message;
}

// 모든 칸의 오류와 폼 오류를 지운다
function clearErrors() {
    FIELDS.forEach((field) => setFieldError(field, ''));
    formError.textContent = '';
}
