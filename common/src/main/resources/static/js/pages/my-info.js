// 내 정보 화면(SCR-C-002 내 정보 영역, FNC-C-006). 두 앱이 함께 쓴다.
import { ApiError, get, patch } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 저장 성공 토스트 문구
const SAVED_MESSAGE = '저장되었습니다';
// 서버 역할 코드 -> 화면 이름
const ROLE_NAMES = { ADMIN: '관리자', SUPER_ADMIN: '최고 관리자' };
// 서버 규칙(InputRules, conventions.md '입력값 규칙')과 같다. 최종 판단은 서버가 하고, 여기서는 입력 중 안내만 한다
const NAME_MAX = 50;
const EMAIL_MAX = 100;
// 메일은 '@' 앞뒤에 글자가 있는지만 간단히 본다(정확한 검사는 서버)
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/;
// 연락처는 숫자와 하이픈만, 20자 이하(빈 값 허용)
const PHONE_PATTERN = /^[0-9-]{0,20}$/;
// 수정할 수 있는 입력칸. 로그인 아이디·역할은 읽기 전용
const EDITABLE_FIELDS = ['name', 'email', 'phone'];
// 입력칸 아래 기본 안내 문구
const DEFAULT_HINTS = {
    name: '1~50자',
    email: '선택, 100자 이하의 메일 형식',
    phone: '선택, 숫자와 -만, 20자 이하',
};

// 폼과 저장 버튼. 서버 값을 받기 전에는 저장 버튼이 꺼져 있어야 한다(load가 켠다)
const form = document.getElementById('profile-form');
const submitButton = document.getElementById('profile-submit');
// 폼 입력칸을 이름으로 모아 둔다. role은 관리자 앱에만 있을 수 있다(없으면 undefined)
const inputs = {
    loginId: form.elements.namedItem('loginId'),
    role: form.elements.namedItem('role'),
    name: form.elements.namedItem('name'),
    email: form.elements.namedItem('email'),
    phone: form.elements.namedItem('phone'),
};

// 입력할 때마다 그 칸을 검사한다
for (const field of EDITABLE_FIELDS) {
    inputs[field].addEventListener('input', () => validate(field));
}
form.addEventListener('submit', submit);
// 화면이 열리면 바로 내 정보를 읽는다
load();

async function load() {
    // 토스트는 끄고 showError에서 직접 안내한다(필드 오류와 일반 오류를 나누기 위해)
    try {
        const info = await get('/api/v1/me', undefined, { toast: false });
        fill(info);
        // 값이 채워진 뒤에만 저장할 수 있다
        submitButton.disabled = false;
    } catch (error) {
        showError(error);
    }
}

// 서버가 준 값은 value·textContent로만 넣는다(code-conventions 5-3)
function fill(info) {
    inputs.loginId.value = info.loginId;
    // 역할 칸이 없는 화면(사용자 앱)에서는 건너뛴다
    if (inputs.role) {
        inputs.role.value = ROLE_NAMES[info.role] ?? info.role ?? '';
    }
    inputs.name.value = info.name;
    // 메일·연락처는 선택 항목이라 null이면 빈 칸
    inputs.email.value = info.email ?? '';
    inputs.phone.value = info.phone ?? '';
}

function validate(field) {
    // 한 칸을 검사하고 상태 표시를 바꾼다. 반환값: 저장 가능한 값이면 true
    const value = inputs[field].value;
    if (value === '') {
        // 이름은 필수라 비어 있으면 오류, 메일·연락처는 비워 두면 값을 지운다
        setState(field, field === 'name' ? 'err' : '', DEFAULT_HINTS[field]);
        return field !== 'name';
    }
    // 칸별 규칙: 이름 1~50자(공백만은 불가), 메일 100자 이하+형식, 연락처 형식
    const valid = {
        name: value.trim() !== '' && value.length <= NAME_MAX,
        email: value.length <= EMAIL_MAX && EMAIL_PATTERN.test(value),
        phone: PHONE_PATTERN.test(value),
    }[field];
    setState(field, valid ? 'ok' : 'err', DEFAULT_HINTS[field]);
    return valid;
}

// state: 'ok' | 'err' | ''
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
    // 모든 칸을 검사해(map으로 전부 실행) 하나라도 틀리면 서버를 부르지 않는다
    if (!EDITABLE_FIELDS.map(validate).every(Boolean)) {
        return;
    }
    // 메일·연락처는 빈 문자열을 보내면 서버가 비운다
    // 값을 trim하지 않고 그대로 보낸다. 서버가 최종 판단한다
    const body = { name: inputs.name.value, email: inputs.email.value, phone: inputs.phone.value };
    // 응답을 기다리는 동안 중복 저장을 막는다
    submitButton.disabled = true;
    try {
        // 서버가 저장한 최신 값으로 칸을 다시 채운다
        fill(await patch('/api/v1/me', body, { toast: false }));
        // 저장이 끝났으므로 입력 중 표시를 모두 지운다
        for (const field of EDITABLE_FIELDS) {
            setState(field, '', DEFAULT_HINTS[field]);
        }
        showToast(SAVED_MESSAGE);
    } catch (error) {
        showError(error);
    } finally {
        submitButton.disabled = false;
    }
}

function showError(error) {
    // ApiError가 아니면 예상 밖 예외: 콘솔에 남기고 기본 문구만 보인다
    if (!(error instanceof ApiError)) {
        console.error(error);
        showToast('일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
        return;
    }
    // 서버의 필드별 오류는 해당 칸 아래에 보인다(없는 칸 이름은 무시)
    if (error.fieldErrors.length > 0) {
        for (const { field, message } of error.fieldErrors) {
            if (field in inputs) {
                setState(field, 'err', message);
            }
        }
        return;
    }
    // 그 밖의 오류는 토스트로
    showToast(error.message);
}
