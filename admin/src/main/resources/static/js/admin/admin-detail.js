// 관리자 수정 창(SCR-A-003이 SCR-A-002와 같은 창을 쓴다). 행의 '수정'·'비밀번호 재설정' 버튼이 열고, 이름·메일·연락처는 목록 행의 값으로 채운다.
// '저장'(FNC-A-010)은 PATCH /api/v1/admins/{id}로 보내고, 성공하면 창을 닫고 목록을 다시 읽는다. 형식 오류는 칸 아래에 보인다.
// '비밀번호 재설정'(FNC-A-010)은 확인 창 뒤 POST /api/v1/admins/{id}/reset-password로 보내고,
// 돌려받은 임시 비밀번호를 복사 버튼과 함께 한 번만 보여 준다. 창을 닫으면 값을 지워 다시 볼 수 없다.
import { ApiError, patch, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

const ADMINS_API = '/api/v1/admins';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const NOT_FOUND_MESSAGE = '관리자를 찾을 수 없습니다';
const EMPTY_VALUE = '';
const COPY_LABEL = '복사';
const COPIED_LABEL = '복사됨';
// 코드 값의 화면 표시 이름(conventions.md 'admin_account.status')
const STATUS_LABELS = { ACTIVE: '정상', DISABLED: '비활성화' };

const detailModal = document.getElementById('admin-detail-modal');
const detailForm = document.getElementById('admin-detail-form');
const formError = document.getElementById('admin-detail-error');
const saveButton = document.getElementById('admin-detail-save');
const title = document.getElementById('admin-detail-title');
const subtitle = document.getElementById('admin-detail-sub');
const nameInput = document.getElementById('admin-detail-name');
const emailInput = document.getElementById('admin-detail-email');
const phoneInput = document.getElementById('admin-detail-phone');
const resetButton = document.getElementById('admin-detail-reset');
const tempBox = document.getElementById('admin-temp-password-box');
const tempPassword = document.getElementById('admin-detail-temp-password');
const copyButton = document.getElementById('admin-detail-temp-copy');

const confirmModal = document.getElementById('admin-reset-confirm-modal');
const confirmMessage = document.getElementById('admin-reset-confirm-message');
const confirmOk = document.getElementById('admin-reset-confirm-ok');

// 지금 열려 있는 관리자, 재설정·저장 요청 진행 중 여부,
// 닫을 때 목록을 새로 읽을지(저장하면 값이, 재설정하면 세션이 끊겨 활동 상태가 바뀐다)
let current = null;
let resetting = false;
let saving = false;
let changed = false;
let onClosed = () => {};

/**
 * 관리자 한 명의 수정 창을 연다. 창을 닫을 때 저장·재설정을 했다면 onChanged를 불러 목록을 새로 읽게 한다.
 * askReset이 true면 행의 '비밀번호 재설정' 버튼으로 열었으므로 곧바로 재설정 확인 창을 띄운다.
 */
export function openAdminDetail(admin, onChanged, askReset = false) {
    current = admin;
    changed = false;
    onClosed = onChanged;
    title.textContent = admin.loginId;
    subtitle.textContent = `${STATUS_LABELS[admin.status] ?? admin.status} · 생성 ${admin.createdAt?.slice(0, 10) ?? ''}`;
    nameInput.value = admin.name ?? EMPTY_VALUE;
    emailInput.value = admin.email ?? EMPTY_VALUE;
    phoneInput.value = admin.phone ?? EMPTY_VALUE;
    clearFormErrors();
    hideTempPassword();
    resetButton.disabled = false;
    saveButton.disabled = false;
    detailModal.hidden = false;
    if (askReset) {
        openConfirm();
    } else {
        nameInput.focus();
    }
}

function closeDetail() {
    if (resetting || saving) {
        return; // 요청이 끝나기 전에는 닫지 않는다
    }
    hideTempPassword();
    detailModal.hidden = true;
    current = null;
    if (changed) {
        changed = false;
        onClosed();
    }
}

// 메일·연락처를 비우면 빈 문자열로 보내 지운다(api/public.md PATCH /api/v1/admins/{id}). 아이디·역할·상태는 보내지 않는다
async function save(event) {
    event.preventDefault();
    if (!current || saving || resetting) {
        return;
    }
    clearFormErrors();
    saving = true;
    saveButton.disabled = true;
    try {
        await patch(
            `${ADMINS_API}/${current.id}`,
            { name: nameInput.value.trim(), email: emailInput.value.trim(), phone: phoneInput.value.trim() },
            { toast: false },
        );
        changed = true;
        saving = false;
        closeDetail(); // 처리 결과: 창 닫고 목록 갱신
        return;
    } catch (error) {
        saving = false;
        if (error instanceof ApiError && error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else if (error instanceof ApiError && error.status === 404) {
            handleError(error);
        } else {
            if (!(error instanceof ApiError)) {
                console.error(error);
            }
            showFieldError('', error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
        }
    }
    saveButton.disabled = false;
}

function clearFormErrors() {
    formError.hidden = true;
    formError.textContent = '';
    detailForm.querySelectorAll('[data-error-for]').forEach((hint) => {
        hint.hidden = true;
        hint.textContent = '';
    });
    detailForm.querySelectorAll('.input.err').forEach((input) => input.classList.remove('err'));
}

// 칸이 없는 오류(요청 본문 형식 등)는 창 위 문구로 보인다
function showFieldError(field, message) {
    const hint = detailForm.querySelector(`[data-error-for="${field}"]`);
    if (!hint) {
        formError.textContent = message;
        formError.hidden = false;
        return;
    }
    hint.textContent = message;
    hint.hidden = false;
    detailForm.elements[field]?.classList?.add('err');
}

function openConfirm() {
    confirmMessage.textContent =
        `'${current.loginId}' 관리자의 비밀번호를 재설정할까요? 임시 비밀번호가 발급되고 이전 비밀번호는 쓸 수 없으며, 열려 있는 모든 세션이 종료됩니다.`;
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

function closeConfirm() {
    if (resetting) {
        return;
    }
    confirmModal.hidden = true;
    if (current) {
        resetButton.focus();
    }
}

async function reset() {
    if (!current || resetting) {
        return;
    }
    resetting = true;
    confirmOk.disabled = true;
    try {
        const result = await post(`${ADMINS_API}/${current.id}/reset-password`, undefined, { toast: false });
        showTempPassword(result.temporaryPassword);
        changed = true;
    } catch (error) {
        handleError(error);
    }
    resetting = false;
    closeConfirm();
}

// 없는 관리자(다른 최고 관리자가 먼저 처리한 경우 등)는 창을 닫고 목록을 새로 읽는다
function handleError(error) {
    if (error instanceof ApiError && error.status === 404) {
        showToast(NOT_FOUND_MESSAGE);
        changed = true;
        resetting = false;
        confirmModal.hidden = true;
        closeDetail();
        return;
    }
    showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
}

function showTempPassword(value) {
    // 서버 값은 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
    tempPassword.textContent = value;
    copyButton.textContent = COPY_LABEL;
    tempBox.hidden = false;
}

function hideTempPassword() {
    tempPassword.textContent = '';
    tempBox.hidden = true;
}

// 클립보드를 쓸 수 없으면(HTTP 접속 등) 글자를 선택해 두어 직접 복사하게 한다
async function copyTempPassword() {
    try {
        await navigator.clipboard.writeText(tempPassword.textContent);
        copyButton.textContent = COPIED_LABEL;
    } catch {
        window.getSelection()?.selectAllChildren(tempPassword);
    }
}

detailModal.querySelectorAll('[data-detail-close]').forEach((button) => button.addEventListener('click', closeDetail));
detailModal.addEventListener('mousedown', (event) => {
    // 임시 비밀번호를 보여 주는 중에는 바깥을 눌러 실수로 닫히지 않게 한다
    if (event.target === detailModal && tempBox.hidden) {
        closeDetail();
    }
});
confirmModal.querySelectorAll('[data-reset-close]').forEach((button) => button.addEventListener('click', closeConfirm));
confirmModal.addEventListener('mousedown', (event) => {
    if (event.target === confirmModal) {
        closeConfirm();
    }
});
detailForm.addEventListener('submit', save);
resetButton.addEventListener('click', openConfirm);
confirmOk.addEventListener('click', reset);
copyButton.addEventListener('click', copyTempPassword);
document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') {
        return;
    }
    // 겹쳐 열려 있으면 위의 확인 창부터 닫는다
    if (!confirmModal.hidden) {
        closeConfirm();
    } else if (!detailModal.hidden) {
        closeDetail();
    }
});
