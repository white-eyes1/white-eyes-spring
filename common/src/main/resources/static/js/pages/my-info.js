// 내 정보 화면(SCR-C-002 내 정보 영역, FNC-C-006). 두 앱이 함께 쓴다.
import { ApiError, get, patch } from '../common/api.js';
import { showToast } from '../common/toast.js';

const SAVED_MESSAGE = '저장되었습니다';
const ROLE_NAMES = { ADMIN: '관리자', SUPER_ADMIN: '최고 관리자' };
// 서버 규칙(InputRules, conventions.md '입력값 규칙')과 같다. 최종 판단은 서버가 하고, 여기서는 입력 중 안내만 한다
const NAME_MAX = 50;
const EMAIL_MAX = 100;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/;
const PHONE_PATTERN = /^[0-9-]{0,20}$/;
const EDITABLE_FIELDS = ['name', 'email', 'phone'];
const DEFAULT_HINTS = {
    name: '1~50자',
    email: '선택, 100자 이하의 메일 형식',
    phone: '선택, 숫자와 -만, 20자 이하',
};

const form = document.getElementById('profile-form');
const submitButton = document.getElementById('profile-submit');
const inputs = {
    loginId: form.elements.namedItem('loginId'),
    role: form.elements.namedItem('role'),
    name: form.elements.namedItem('name'),
    email: form.elements.namedItem('email'),
    phone: form.elements.namedItem('phone'),
};

for (const field of EDITABLE_FIELDS) {
    inputs[field].addEventListener('input', () => validate(field));
}
form.addEventListener('submit', submit);
load();

async function load() {
    try {
        const info = await get('/api/v1/me', undefined, { toast: false });
        fill(info);
        submitButton.disabled = false;
    } catch (error) {
        showError(error);
    }
}

// 서버가 준 값은 value·textContent로만 넣는다(code-conventions 5-3)
function fill(info) {
    inputs.loginId.value = info.loginId;
    if (inputs.role) {
        inputs.role.value = ROLE_NAMES[info.role] ?? info.role ?? '';
    }
    inputs.name.value = info.name;
    inputs.email.value = info.email ?? '';
    inputs.phone.value = info.phone ?? '';
}

function validate(field) {
    const value = inputs[field].value;
    if (value === '') {
        // 이름은 필수라 비어 있으면 오류, 메일·연락처는 비워 두면 값을 지운다
        setState(field, field === 'name' ? 'err' : '', DEFAULT_HINTS[field]);
        return field !== 'name';
    }
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
    if (!EDITABLE_FIELDS.map(validate).every(Boolean)) {
        return;
    }
    // 메일·연락처는 빈 문자열을 보내면 서버가 비운다
    const body = { name: inputs.name.value, email: inputs.email.value, phone: inputs.phone.value };
    submitButton.disabled = true;
    try {
        fill(await patch('/api/v1/me', body, { toast: false }));
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
    if (!(error instanceof ApiError)) {
        console.error(error);
        showToast('일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.');
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
