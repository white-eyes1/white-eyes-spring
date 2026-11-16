// 관리자 수정 창(SCR-A-003이 SCR-A-002와 같은 창을 쓴다). 행의 '수정'·'비밀번호 재설정' 버튼이 열고, 이름·메일·연락처는 목록 행의 값으로 채운다.
// '저장'(FNC-A-010)은 PATCH /api/v1/admins/{id}로 보내고, 성공하면 창을 닫고 목록을 다시 읽는다. 형식 오류는 칸 아래에 보인다.
// '비밀번호 재설정'(FNC-A-010)은 확인 창 뒤 POST /api/v1/admins/{id}/reset-password로 보내고,
// 돌려받은 임시 비밀번호를 복사 버튼과 함께 한 번만 보여 준다. 창을 닫으면 값을 지워 다시 볼 수 없다.
// 공용 API 모듈: ApiError는 서버 오류 응답(상태 코드·필드별 오류)을 담은 예외, patch/post는 JSON 요청 함수다.
import { ApiError, patch, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 관리자 REST API의 공통 주소. 뒤에 /{id}나 /{id}/reset-password를 붙여 쓴다.
const ADMINS_API = '/api/v1/admins';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const NOT_FOUND_MESSAGE = '관리자를 찾을 수 없습니다';
const EMPTY_VALUE = '';
const COPY_LABEL = '복사';
const COPIED_LABEL = '복사됨';
// 코드 값의 화면 표시 이름(conventions.md 'admin_account.status')
const STATUS_LABELS = { ACTIVE: '정상', DISABLED: '비활성화' };

// 아래는 admin 템플릿(admins.html)에 있는 수정 창·입력칸·버튼 요소를 id로 한 번만 찾아 두는 부분이다.
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

// 비밀번호 재설정 확인 창의 요소들. 수정 창 위에 겹쳐 열린다.
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
    // 현재 수정 대상과 상태를 초기화한다. 이전에 열었던 흔적(변경 여부)이 남지 않게 매번 false로 되돌린다.
    current = admin;
    changed = false;
    onClosed = onChanged;
    // 서버 값은 textContent로 넣어 HTML로 해석되지 않게 한다(XSS 방지). 상태 코드는 한글 이름으로 바꾸고, 모르는 코드면 원문을 보인다.
    title.textContent = admin.loginId;
    subtitle.textContent = `${STATUS_LABELS[admin.status] ?? admin.status} · 생성 ${admin.createdAt?.slice(0, 10) ?? ''}`;
    // 이름·메일·연락처는 목록 행에서 받은 값으로 채운다. 값이 없으면(null) 빈 문자열로 둔다.
    nameInput.value = admin.name ?? EMPTY_VALUE;
    emailInput.value = admin.email ?? EMPTY_VALUE;
    phoneInput.value = admin.phone ?? EMPTY_VALUE;
    // 이전에 열었을 때의 오류 문구와 임시 비밀번호를 지우고 버튼을 다시 누를 수 있게 한다.
    clearFormErrors();
    hideTempPassword();
    resetButton.disabled = false;
    saveButton.disabled = false;
    // 창을 보이게 한다(hidden 속성으로 열고 닫는다).
    detailModal.hidden = false;
    // 행의 '비밀번호 재설정' 버튼으로 열렸다면 수정 창과 함께 확인 창을 바로 띄우고, 아니면 이름 칸에 커서를 둔다.
    if (askReset) {
        openConfirm();
    } else {
        nameInput.focus();
    }
}

// 수정 창을 닫는다. 요청이 진행 중이면 응답 전에 상태가 어긋나지 않도록 무시하고, 저장·재설정으로 값이 바뀌었으면 목록 갱신 콜백을 부른다.
function closeDetail() {
    if (resetting || saving) {
        return; // 요청이 끝나기 전에는 닫지 않는다
    }
    // 임시 비밀번호는 창을 닫으면 지워서 다시 볼 수 없게 한다(한 번만 보여 주는 정책).
    hideTempPassword();
    detailModal.hidden = true;
    current = null;
    if (changed) {
        changed = false;
        onClosed();
    }
}

// 메일·연락처를 비우면 빈 문자열로 보내 지운다(api/public.md PATCH /api/v1/admins/{id}). 아이디·역할·상태는 보내지 않는다
// '저장' 제출 처리(FNC-A-010). 폼 기본 제출(페이지 이동)을 막고 PATCH로 보낸다.
async function save(event) {
    event.preventDefault();
    // 대상이 없거나 이미 저장·재설정 중이면 중복 요청을 막는다.
    if (!current || saving || resetting) {
        return;
    }
    clearFormErrors();
    // 요청 동안 저장 버튼을 잠가 더블클릭으로 같은 요청이 두 번 가지 않게 한다.
    saving = true;
    saveButton.disabled = true;
    try {
        // toast:false는 공용 모듈이 오류 토스트를 띄우지 않게 하는 옵션이다. 오류는 이 창의 칸 아래에 직접 보여 주기 때문이다.
        await patch(
            `${ADMINS_API}/${current.id}`,
            {
                name: nameInput.value.trim(),
                email: emailInput.value.trim(),
                phone: phoneInput.value.trim(),
            },
            { toast: false },
        );
        // 값이 바뀌었으므로 창을 닫을 때 목록을 다시 읽게 표시한다.
        changed = true;
        saving = false;
        // saving을 먼저 false로 풀어야 closeDetail의 '진행 중이면 닫지 않음' 검사를 통과한다.
        closeDetail(); // 처리 결과: 창 닫고 목록 갱신
        return;
    } catch (error) {
        saving = false;
        // 칸별 검증 오류(400)는 해당 칸 아래에, 없는 관리자(404)는 창을 닫고, 그 밖의 오류는 창 위 문구로 보인다.
        if (error instanceof ApiError && error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else if (error instanceof ApiError && error.status === 404) {
            handleError(error);
        } else {
            // ApiError가 아닌 예상 밖 예외(코드 버그·네트워크 등)는 원인 추적을 위해 콘솔에 남기고 사용자에게는 일반 문구를 보인다.
            if (!(error instanceof ApiError)) {
                console.error(error);
            }
            showFieldError('', error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
        }
    }
    // 실패해서 창이 열려 있는 경우에만 여기까지 오며, 고쳐서 다시 저장할 수 있게 버튼을 푼다.
    saveButton.disabled = false;
}

// 창 위 오류, 칸별 오류 문구, 빨간 테두리(err 클래스)를 모두 지운다.
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
// field 이름과 같은 data-error-for 칸을 찾아 문구를 보이고 입력칸에 오류 테두리를 준다. 대응하는 칸이 없으면 창 위 문구로 대신한다.
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

// 재설정 확인 창을 연다. 임시 비밀번호 발급과 세션 종료라는 결과를 미리 알려 실수로 누르지 않게 한다.
function openConfirm() {
    confirmMessage.textContent = `'${current.loginId}' 관리자의 비밀번호를 재설정할까요? 임시 비밀번호가 발급되고 이전 비밀번호는 쓸 수 없으며, 열려 있는 모든 세션이 종료됩니다.`;
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

// 확인 창을 닫는다. 재설정 요청 중에는 닫지 않는다. 닫은 뒤 수정 창이 남아 있으면 재설정 버튼으로 초점을 돌려 키보드 사용이 이어지게 한다.
function closeConfirm() {
    if (resetting) {
        return;
    }
    confirmModal.hidden = true;
    if (current) {
        resetButton.focus();
    }
}

// 확인 창의 '재설정' 처리(FNC-A-010). 서버가 만든 임시 비밀번호를 받아 화면에 한 번 보여 준다.
async function reset() {
    if (!current || resetting) {
        return;
    }
    // 중복 클릭 방지: 요청이 끝날 때까지 확인 버튼을 잠근다.
    resetting = true;
    confirmOk.disabled = true;
    try {
        // 본문 없이 POST한다. 응답의 temporaryPassword가 새 임시 비밀번호다.
        const result = await post(`${ADMINS_API}/${current.id}/reset-password`, undefined, {
            toast: false,
        });
        showTempPassword(result.temporaryPassword);
        // 재설정하면 대상 관리자의 세션이 끊겨 활동 상태가 바뀌므로 목록을 다시 읽어야 한다.
        changed = true;
    } catch (error) {
        handleError(error);
    }
    // 성공·실패와 무관하게 진행 중 표시를 풀고 확인 창을 닫는다(임시 비밀번호는 수정 창 안에 남는다).
    resetting = false;
    closeConfirm();
}

// 없는 관리자(다른 최고 관리자가 먼저 처리한 경우 등)는 창을 닫고 목록을 새로 읽는다
// 404는 다른 최고 관리자가 이미 지운 경우 등이므로 알리고 창을 모두 닫아 목록을 갱신한다. 그 외는 토스트로 알린다.
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

// 임시 비밀번호 상자를 보이고 복사 버튼 문구를 처음 상태로 되돌린다.
function showTempPassword(value) {
    // 서버 값은 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
    tempPassword.textContent = value;
    copyButton.textContent = COPY_LABEL;
    tempBox.hidden = false;
}

// 임시 비밀번호 값을 DOM에서 비우고 상자를 숨긴다.
function hideTempPassword() {
    tempPassword.textContent = '';
    tempBox.hidden = true;
}

// 클립보드를 쓸 수 없으면(HTTP 접속 등) 글자를 선택해 두어 직접 복사하게 한다
// 클립보드 복사에 성공하면 버튼 문구를 '복사됨'으로 바꾼다.
async function copyTempPassword() {
    try {
        await navigator.clipboard.writeText(tempPassword.textContent);
        copyButton.textContent = COPIED_LABEL;
    } catch {
        window.getSelection()?.selectAllChildren(tempPassword);
    }
}

// 아래부터는 이벤트 연결부다. 닫기 버튼(data-detail-close)은 모두 closeDetail로 연결한다.
detailModal
    .querySelectorAll('[data-detail-close]')
    .forEach((button) => button.addEventListener('click', closeDetail));
detailModal.addEventListener('mousedown', (event) => {
    // 임시 비밀번호를 보여 주는 중에는 바깥을 눌러 실수로 닫히지 않게 한다
    if (event.target === detailModal && tempBox.hidden) {
        closeDetail();
    }
});
// 확인 창의 닫기·취소 버튼(data-reset-close)도 같은 방식으로 연결한다.
confirmModal
    .querySelectorAll('[data-reset-close]')
    .forEach((button) => button.addEventListener('click', closeConfirm));
confirmModal.addEventListener('mousedown', (event) => {
    if (event.target === confirmModal) {
        closeConfirm();
    }
});
// 저장(폼 제출), 재설정 열기, 재설정 실행, 복사 버튼을 각 처리 함수에 연결한다.
detailForm.addEventListener('submit', save);
resetButton.addEventListener('click', openConfirm);
confirmOk.addEventListener('click', reset);
copyButton.addEventListener('click', copyTempPassword);
// Esc 키 처리: 확인 창이 위에 있으면 그것부터, 아니면 수정 창을 닫는다.
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
