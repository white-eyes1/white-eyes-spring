/*
 * [해석본] 원본: common/src/main/resources/static/js/pages/signup.js (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002), #29(FNC-U-001), 화면: SCR-U-001
 *
 * [무엇] 회원가입 화면의 동작. 아이디 중복 확인 → 가입하기 켜짐 → 가입 요청 → 결과 표시.
 * [위치] package-structure.md 6장에는 사용자 앱 전용 JS 위치가 없다. common의 pages/ 아래에 두었다(PR '검토 필요').
 * [범위] #29에서 미룬 '아이디 입력 + 중복 확인' UI도 여기 있다. 가입하기가 중복 확인에 묶여 있어 따로 만들 수 없다.
 */
// 회원가입 화면(SCR-U-001). 아이디 중복 확인(FNC-U-001)을 마쳐야 가입하기(FNC-U-002)가 켜진다.
// [해석] 템플릿이 type="module"로 불러서 import를 쓸 수 있다(code-conventions 5-1). 전역 변수를 만들지 않는다.
import { ApiError, get, post, url } from '../common/api.js';

// [해석] 세 문구는 screens.md SCR-U-001에 적힌 그대로다.
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
// [해석] '확인을 마쳤다'를 true/false가 아니라 확인한 아이디 값으로 기억한다. 가입하기를 켤 때 지금 칸의 값과 같은지
//        한 번 더 보므로, 이벤트 순서가 꼬여도 확인하지 않은 아이디로 가입 요청이 나가지 않는다.
let checkedLoginId = null;

// [해석] SCR-U-001 '아이디를 바꾸면 확인 상태 초기화'. 글자 하나만 바꿔도 다시 확인해야 한다.
loginIdInput.addEventListener('input', () => {
    checkedLoginId = null;
    loginIdStatus.textContent = '';
    setFieldError('loginId', '');
    updateSubmitButton();
});

// [해석] 아이디 형식 검사는 서버(400 fieldErrors)에 맡긴다. 규칙을 JS에 한 번 더 쓰면 두 곳을 같이 고쳐야 한다.
//        확인하는 동안 버튼을 꺼서 여러 번 눌러도 요청이 겹치지 않게 한다.
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
    // [해석] 버튼이 꺼져 있을 때 다른 방법(Enter 등)으로 제출되는 경우를 막는 방어.
    if (submitButton.disabled) {
        return;
    }
    clearErrors();
    submitButton.disabled = true;
    try {
        await post('/api/v1/signup', readForm());
        // [해석] FNC-U-002 처리 순서 4 '가입 완료 안내 후 로그인 화면으로 이동'. 안내 방식이 명세에 없어 가장 단순한 alert로 했다.
        //        /login 화면은 #3(FNC-C-001)이 만든다. 그 전까지는 이동하면 404다.
        window.alert(SIGNUP_DONE_MESSAGE);
        window.location.href = url('/login');
    } catch (error) {
        // 확인한 뒤 다른 사람이 먼저 가입한 경우라 다시 확인해야 한다
        // [해석] 중복 확인을 마친 뒤 가입하기 전 사이에 다른 사람이 같은 아이디로 가입한 경우(동시 가입).
        //        SCR-U-001 '409면 아이디 칸 오류'. 확인 상태를 지워 다시 확인해야 가입하기가 켜진다.
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

// [해석] 빈 메일·연락처도 ''로 보낸다. 서버가 NULL로 바꿔 저장한다.
function readForm() {
    return Object.fromEntries(FIELDS.map((field) => [field, form.elements.namedItem(field).value]));
}

function showError(error) {
    // [해석] ApiError가 아니면 이 파일의 버그다. 사용자에게는 기본 문구를 보이고, 원인은 console.error로 남긴다(5-3에서 허용).
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
// [해석] textContent만 쓴다. 서버 문구를 innerHTML에 넣지 않는다(XSS, code-conventions 5-3).
function setFieldError(field, message) {
    const target = form.querySelector(`[data-error-for="${field}"]`) ?? formError;
    target.textContent = message;
}

function clearErrors() {
    FIELDS.forEach((field) => setFieldError(field, ''));
    formError.textContent = '';
}
