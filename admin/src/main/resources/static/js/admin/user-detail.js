// 사용자 상세 창(SCR-A-002). 행을 누르면 열리고, 이름·메일·연락처는 목록 행의 값으로 채운다.
// '저장'(FNC-A-004)은 PATCH /api/v1/users/{id}로 보내고, 성공하면 창을 닫고 목록을 다시 읽는다. 형식 오류는 칸 아래에 보인다.
// '비밀번호 재설정'(FNC-A-005)은 확인 창 뒤 POST /api/v1/users/{id}/reset-password로 보내고,
// 돌려받은 임시 비밀번호를 복사 버튼과 함께 한 번만 보여 준다. 창을 닫으면 값을 지워 다시 볼 수 없다.
// 상태가 '탈퇴'인 계정은 입력을 읽기 전용으로 하고 저장·비밀번호 재설정을 끄며 안내 문구를 보인다(API도 409로 거부한다).
// 공용 API 모듈(ApiError: 서버 오류 응답 예외, patch/post: JSON 요청)과 토스트 알림 함수.
import { ApiError, patch, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 사용자 REST API의 공통 주소.
const USERS_API = '/api/v1/users';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const NOT_FOUND_MESSAGE = '사용자를 찾을 수 없습니다';
// 탈퇴 상태 코드와 그 상태에서 서버가 409로 거절할 때 보일 문구.
const WITHDRAWN_STATUS = 'WITHDRAWN';
// 다른 관리자가 그 사이 탈퇴 처리한 계정에 보내면 409 INVALID_STATUS가 온다(api/public.md)
const WITHDRAWN_SAVE_MESSAGE = '탈퇴한 계정은 수정할 수 없습니다';
const WITHDRAWN_RESET_MESSAGE = '탈퇴한 계정은 비밀번호를 재설정할 수 없습니다';
const EMPTY_VALUE = '';
const COPY_LABEL = '복사';
const COPIED_LABEL = '복사됨';
// 코드 값의 화면 표시 이름(conventions.md 'user_account.status'). 시안의 '승인됨'은 정상(ACTIVE)이다
const STATUS_LABELS = { PENDING: '미승인', ACTIVE: '승인됨', WITHDRAWN: '탈퇴' };

// 사용자 상세 창(users.html)의 요소들을 id로 한 번만 찾아 둔다.
const detailModal = document.getElementById('user-detail-modal');
const detailForm = document.getElementById('user-detail-form');
const formError = document.getElementById('user-detail-error');
const saveButton = document.getElementById('user-detail-save');
const withdrawnNotice = document.getElementById('user-detail-withdrawn');
// 탈퇴 계정은 입력이 읽기 전용이므로 열릴 때 초점을 이 닫기 버튼에 둔다.
const closeButton = detailModal.querySelector('[data-detail-close]');
const title = document.getElementById('user-detail-title');
const subtitle = document.getElementById('user-detail-sub');
const nameInput = document.getElementById('user-detail-name');
const emailInput = document.getElementById('user-detail-email');
const phoneInput = document.getElementById('user-detail-phone');
const resetButton = document.getElementById('user-detail-reset');
const tempBox = document.getElementById('user-temp-password-box');
const tempPassword = document.getElementById('user-temp-password');
const copyButton = document.getElementById('user-temp-copy');

// 비밀번호 재설정 확인 창의 요소들.
const confirmModal = document.getElementById('user-reset-confirm-modal');
const confirmMessage = document.getElementById('user-reset-confirm-message');
const confirmOk = document.getElementById('user-reset-confirm-ok');

// 지금 열려 있는 사용자, 재설정·저장 요청 진행 중 여부,
// 닫을 때 목록을 새로 읽을지(저장하면 값이, 재설정하면 세션이 끊겨 활동 상태가 바뀐다)
let current = null;
let resetting = false;
let saving = false;
let changed = false;
let onClosed = () => {};

/** 사용자 한 명의 상세 창을 연다. 창을 닫을 때 저장·재설정을 했다면 onChanged를 불러 목록을 새로 읽게 한다. */
// 목록의 행을 눌렀을 때 호출된다. 현재 사용자와 상태를 초기화하고 값을 채워 창을 연다.
export function openUserDetail(user, onChanged) {
    current = user;
    changed = false;
    onClosed = onChanged;
    // 서버 값은 textContent로 넣어 HTML로 해석되지 않게 한다. 상태 코드는 한글 이름으로 바꾸고 모르는 코드는 원문을 보인다.
    title.textContent = user.loginId;
    subtitle.textContent = `${STATUS_LABELS[user.status] ?? user.status} · 가입 ${user.createdAt?.slice(0, 10) ?? ''}`;
    // 이름·메일·연락처는 목록 행의 값으로 채운다(없으면 빈 문자열).
    nameInput.value = user.name ?? EMPTY_VALUE;
    emailInput.value = user.email ?? EMPTY_VALUE;
    phoneInput.value = user.phone ?? EMPTY_VALUE;
    // 이전 오류 문구와 임시 비밀번호를 지운다.
    clearFormErrors();
    hideTempPassword();
    // 다른 상태(미승인·승인됨)는 그대로 켠다
    // 탈퇴 계정이면 입력·버튼을 잠그고 안내 문구를 보인다.
    const withdrawn = user.status === WITHDRAWN_STATUS;
    applyWithdrawn(withdrawn);
    detailModal.hidden = false;
    // 탈퇴 계정은 편집할 칸이 없으므로 닫기 버튼에, 그 외는 이름 칸에 초점을 둔다.
    (withdrawn ? closeButton : nameInput).focus();
}

// 탈퇴 계정은 로그인할 수 없고 정보도 더 쓰이지 않아, 입력은 읽기 전용이고 저장·비밀번호 재설정은 끈다(FNC-A-004·005)
// 탈퇴 여부에 따라 입력 읽기 전용, 버튼 비활성, 안내 문구 표시를 한꺼번에 바꾼다. 저장 중 409를 받았을 때도 같은 함수로 화면을 탈퇴 상태로 바꾼다.
function applyWithdrawn(withdrawn) {
    [nameInput, emailInput, phoneInput].forEach((input) => {
        input.readOnly = withdrawn;
    });
    resetButton.disabled = withdrawn;
    saveButton.disabled = withdrawn;
    withdrawnNotice.hidden = !withdrawn;
}

// 상세 창을 닫는다. 요청이 진행 중이면 닫지 않고, 임시 비밀번호는 지우며, 값이 바뀌었으면 목록 갱신 콜백을 부른다.
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

// 메일·연락처를 비우면 빈 문자열로 보내 지운다(api/public.md PATCH /api/v1/users/{id}). 아이디는 보내지 않는다
// '저장' 제출 처리(FNC-A-004). 폼 기본 제출을 막고 PATCH로 보낸다.
async function save(event) {
    event.preventDefault();
    // 대상이 없거나 요청 중이면 중복 요청을 막는다.
    if (!current || saving || resetting) {
        return;
    }
    clearFormErrors();
    // 더블클릭으로 요청이 두 번 가지 않게 저장 버튼을 잠근다.
    saving = true;
    saveButton.disabled = true;
    try {
        // toast:false: 오류는 이 창 안에 직접 보여 주므로 공용 토스트를 끈다. 아이디는 바꿀 수 없어 보내지 않는다.
        await patch(
            `${USERS_API}/${current.id}`,
            {
                name: nameInput.value.trim(),
                email: emailInput.value.trim(),
                phone: phoneInput.value.trim(),
            },
            { toast: false },
        );
        // 성공하면 닫을 때 목록을 다시 읽도록 표시하고, saving을 풀어야 closeDetail이 실제로 닫는다.
        changed = true;
        saving = false;
        closeDetail(); // 처리 결과: 창 닫고 목록 갱신
        return;
    } catch (error) {
        saving = false;
        // 칸별 검증 오류는 해당 칸 아래에, 404는 창을 닫고, 409(그 사이 탈퇴 처리됨)는 탈퇴 화면으로 바꾸며, 나머지는 창 위 문구로 보인다.
        if (error instanceof ApiError && error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else if (error instanceof ApiError && error.status === 404) {
            handleError(error);
        } else if (error instanceof ApiError && error.status === 409) {
            changed = true; // 상태가 바뀐 계정이라 닫을 때 목록을 다시 읽는다
            applyWithdrawn(true);
            showFieldError('', WITHDRAWN_SAVE_MESSAGE);
            // 409면 계정이 탈퇴 상태가 되어 저장 버튼을 다시 켜지 않고 여기서 끝낸다.
            return;
        } else {
            // ApiError가 아닌 예상 밖 예외는 콘솔에 남겨 원인을 추적할 수 있게 한다.
            if (!(error instanceof ApiError)) {
                console.error(error);
            }
            showFieldError('', error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
        }
    }
    // 실패한 뒤 창이 열려 있으면 다시 저장할 수 있게 버튼을 푼다.
    saveButton.disabled = false;
}

// 창 위 오류, 칸별 오류, 빨간 테두리를 모두 지운다.
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
// field와 같은 data-error-for 칸에 문구를 보이고 입력칸에 오류 테두리를 준다. 대응하는 칸이 없으면 창 위 문구로 대신한다.
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

// 재설정 확인 창을 연다. 임시 비밀번호 발급과 전체 세션 종료를 미리 알려 실수를 막는다.
function openConfirm() {
    confirmMessage.textContent = `'${current.loginId}' 사용자의 비밀번호를 재설정할까요? 임시 비밀번호가 발급되고 이전 비밀번호는 쓸 수 없으며, 열려 있는 모든 세션이 종료됩니다.`;
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

// 확인 창을 닫는다(요청 중에는 닫지 않음). 상세 창이 열려 있으면 재설정 버튼으로 초점을 돌린다.
function closeConfirm() {
    if (resetting) {
        return;
    }
    confirmModal.hidden = true;
    if (current) {
        resetButton.focus();
    }
}

// 확인 창의 '재설정' 처리(FNC-A-005). 서버가 만든 임시 비밀번호를 받아 한 번 보여 준다.
async function reset() {
    if (!current || resetting) {
        return;
    }
    // 요청이 끝날 때까지 확인 버튼을 잠가 중복 요청을 막는다.
    resetting = true;
    confirmOk.disabled = true;
    try {
        // 본문 없이 POST한다. 응답의 temporaryPassword가 새 임시 비밀번호다.
        const result = await post(`${USERS_API}/${current.id}/reset-password`, undefined, {
            toast: false,
        });
        showTempPassword(result.temporaryPassword);
        // 재설정하면 그 사용자의 세션이 끊겨 활동 상태가 바뀌므로 목록을 다시 읽도록 표시한다.
        changed = true;
    } catch (error) {
        handleError(error);
    }
    // 성공·실패와 관계없이 진행 중 표시를 풀고 확인 창을 닫는다.
    resetting = false;
    closeConfirm();
}

// 없는 사용자(다른 관리자가 먼저 처리한 경우 등)는 창을 닫고 목록을 새로 읽는다
// 404(이미 없는 사용자)는 알리고 창을 모두 닫아 목록을 갱신한다. 409(탈퇴한 계정)는 화면을 탈퇴 상태로 바꾼다. 그 외는 토스트로 알린다.
function handleError(error) {
    if (error instanceof ApiError && error.status === 404) {
        showToast(NOT_FOUND_MESSAGE);
        changed = true;
        resetting = false;
        confirmModal.hidden = true;
        closeDetail();
        return;
    }
    if (error instanceof ApiError && error.status === 409) {
        changed = true;
        applyWithdrawn(true);
        showToast(WITHDRAWN_RESET_MESSAGE);
        return;
    }
    showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
}

// 임시 비밀번호 상자를 보이고 복사 버튼 문구를 처음 상태로 되돌린다.
function showTempPassword(value) {
    // 서버 값은 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
    tempPassword.textContent = value;
    copyButton.textContent = COPY_LABEL;
    tempBox.hidden = false;
}

// 임시 비밀번호를 DOM에서 지우고 상자를 숨긴다.
function hideTempPassword() {
    tempPassword.textContent = '';
    tempBox.hidden = true;
}

// 클립보드를 쓸 수 없으면(HTTP 접속 등) 글자를 선택해 두어 직접 복사하게 한다
// 복사에 성공하면 버튼 문구를 '복사됨'으로 바꾼다.
async function copyTempPassword() {
    try {
        await navigator.clipboard.writeText(tempPassword.textContent);
        copyButton.textContent = COPIED_LABEL;
    } catch {
        window.getSelection()?.selectAllChildren(tempPassword);
    }
}

// 아래부터는 이벤트 연결부다. 상세 창의 닫기 버튼은 closeDetail로 연결한다.
detailModal
    .querySelectorAll('[data-detail-close]')
    .forEach((button) => button.addEventListener('click', closeDetail));
detailModal.addEventListener('mousedown', (event) => {
    // 임시 비밀번호를 보여 주는 중에는 바깥을 눌러 실수로 닫히지 않게 한다
    if (event.target === detailModal && tempBox.hidden) {
        closeDetail();
    }
});
// 확인 창의 닫기·취소 버튼과 배경 클릭을 closeConfirm으로 연결한다.
confirmModal
    .querySelectorAll('[data-reset-close]')
    .forEach((button) => button.addEventListener('click', closeConfirm));
confirmModal.addEventListener('mousedown', (event) => {
    if (event.target === confirmModal) {
        closeConfirm();
    }
});
// 저장·재설정 열기·재설정 실행·복사 버튼을 각 처리 함수에 연결한다.
detailForm.addEventListener('submit', save);
resetButton.addEventListener('click', openConfirm);
confirmOk.addEventListener('click', reset);
copyButton.addEventListener('click', copyTempPassword);
// Esc 키: 확인 창이 열려 있으면 그것부터, 아니면 상세 창을 닫는다.
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
