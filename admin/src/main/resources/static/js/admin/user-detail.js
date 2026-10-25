// 사용자 상세 창(SCR-A-002). 행을 누르면 열리고, 이름·메일·연락처는 목록 행의 값을 읽기 전용으로 보여 준다(수정은 FNC-A-004).
// '비밀번호 재설정'(FNC-A-005)은 확인 창 뒤 POST /api/v1/users/{id}/reset-password로 보내고,
// 돌려받은 임시 비밀번호를 복사 버튼과 함께 한 번만 보여 준다. 창을 닫으면 값을 지워 다시 볼 수 없다.
import { ApiError, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

const USERS_API = '/api/v1/users';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const NOT_FOUND_MESSAGE = '사용자를 찾을 수 없습니다';
const EMPTY_VALUE = '';
const COPY_LABEL = '복사';
const COPIED_LABEL = '복사됨';
// 코드 값의 화면 표시 이름(conventions.md 'user_account.status'). 시안의 '승인됨'은 정상(ACTIVE)이다
const STATUS_LABELS = { PENDING: '미승인', ACTIVE: '승인됨', WITHDRAWN: '탈퇴' };

const detailModal = document.getElementById('user-detail-modal');
const title = document.getElementById('user-detail-title');
const subtitle = document.getElementById('user-detail-sub');
const nameInput = document.getElementById('user-detail-name');
const emailInput = document.getElementById('user-detail-email');
const phoneInput = document.getElementById('user-detail-phone');
const resetButton = document.getElementById('user-detail-reset');
const tempBox = document.getElementById('user-temp-password-box');
const tempPassword = document.getElementById('user-temp-password');
const copyButton = document.getElementById('user-temp-copy');

const confirmModal = document.getElementById('user-reset-confirm-modal');
const confirmMessage = document.getElementById('user-reset-confirm-message');
const confirmOk = document.getElementById('user-reset-confirm-ok');

// 지금 열려 있는 사용자, 재설정 요청 진행 중 여부, 닫을 때 목록을 새로 읽을지(재설정하면 세션이 끊겨 활동 상태가 바뀐다)
let current = null;
let resetting = false;
let changed = false;
let onClosed = () => {};

/** 사용자 한 명의 상세 창을 연다. 창을 닫을 때 재설정을 했다면 onChanged를 불러 목록을 새로 읽게 한다. */
export function openUserDetail(user, onChanged) {
    current = user;
    changed = false;
    onClosed = onChanged;
    title.textContent = user.loginId;
    subtitle.textContent = `${STATUS_LABELS[user.status] ?? user.status} · 가입 ${user.createdAt?.slice(0, 10) ?? ''}`;
    nameInput.value = user.name ?? EMPTY_VALUE;
    emailInput.value = user.email ?? EMPTY_VALUE;
    phoneInput.value = user.phone ?? EMPTY_VALUE;
    hideTempPassword();
    resetButton.disabled = false;
    detailModal.hidden = false;
    resetButton.focus();
}

function closeDetail() {
    if (resetting) {
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

function openConfirm() {
    confirmMessage.textContent =
        `'${current.loginId}' 사용자의 비밀번호를 재설정할까요? 임시 비밀번호가 발급되고 이전 비밀번호는 쓸 수 없으며, 열려 있는 모든 세션이 종료됩니다.`;
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
        const result = await post(`${USERS_API}/${current.id}/reset-password`, undefined, { toast: false });
        showTempPassword(result.temporaryPassword);
        changed = true;
    } catch (error) {
        handleError(error);
    }
    resetting = false;
    closeConfirm();
}

// 없는 사용자(다른 관리자가 먼저 처리한 경우 등)는 창을 닫고 목록을 새로 읽는다
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
