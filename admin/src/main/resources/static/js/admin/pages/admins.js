// 관리자 관리 화면(SCR-A-003). 관리자 목록(FNC-A-003)을 GET /api/v1/admins로 불러와 아이디 정렬·페이지 이동한다.
// 시안·screens.md에 검색 줄이 없어 조건 없이 부른다(API는 검색 조건을 받음). 기본 순서는 API 기본값(최근 생성 순)이다.
// '관리자 추가' 창은 POST /api/v1/admins(FNC-A-009)로 계정을 만들고 임시 비밀번호를 한 번만 보여 준다.
// 활동 중인 관리자 행의 '강제 로그아웃'(FNC-A-008)은 확인 창 뒤 POST /api/v1/admins/{id}/logout으로,
// '비활성화·활성화'(FNC-A-011)는 확인 창 뒤 POST /api/v1/admins/{id}/disable·enable로 보낸다.
// 공용 모듈: API 호출(get/post)과 오류 예외, 날짜·숫자 표시 형식, 토스트 알림, 그리고 수정 창(admin-detail.js)을 가져온다.
import { ApiError, get, post } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { openAdminDetail } from '../admin-detail.js';

// 관리자 REST API 공통 주소, 한 페이지에 보일 행 수, 값이 없을 때 표시할 '-', 현재 쪽 앞뒤로 보일 페이지 번호 개수(PAGE_WINDOW).
const ADMINS_API = '/api/v1/admins';
const PAGE_SIZE = 20;
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const EMPTY_VALUE = '-';
const ACTIVE_LABEL = '활동 중';
const PAGE_WINDOW = 2;
// 아래 표들은 서버 코드 값을 화면에 보일 한글 이름, 상태 칩 색(CSS 클래스), 정렬 표시 화살표로 바꾸는 사전이다.
// 코드 값의 화면 표시 이름(conventions.md admin_account.role·status). 칩 색은 시안(SCR-A-003)을 따른다
const ROLE_LABELS = { ADMIN: '관리자', SUPER_ADMIN: '최고 관리자' };
const STATUS_LABELS = { ACTIVE: '정상', DISABLED: '비활성화' };
const STATUS_CHIPS = { ACTIVE: 'done', DISABLED: 'off' };
const SORT_MARKS = { asc: '↑', desc: '↓', none: '↕' };

const SELF_LABEL = '본인 계정';
// 서버가 화면에 넣어 준 현재 로그인 아이디(본인 행 표시용)
// 표를 그리는 서버가 data-current-login-id 속성으로 심어 둔 현재 로그인 아이디. 본인 행에 '본인 계정'을 보이는 데 쓴다.
const currentLoginId = document.getElementById('admin-table').dataset.currentLoginId;

// 화면 요소(표 본문, 정렬 버튼, 빈 목록·오류 안내, 페이지 버튼 영역, 총 인원)를 한 번만 찾아 둔다.
const tableBody = document.getElementById('admin-table-body');
const sortButtons = document.querySelectorAll('#admin-table button[data-sort]');
const emptyNotice = document.getElementById('admin-list-empty');
const errorNotice = document.getElementById('admin-list-error');
const pagination = document.getElementById('admin-pagination');
const totalText = document.getElementById('admin-total');

// sortField가 null이면 정렬을 보내지 않아 API 기본 순서를 쓴다
// 화면 상태: 정렬 항목·방향과 현재 쪽(0부터).
const state = { sortField: null, sortDirection: 'asc', page: 0 };
// 요청 순서 번호. 빠르게 정렬·페이지를 바꿔 요청이 겹쳤을 때 가장 마지막 요청의 응답만 화면에 반영하려고 쓴다.
let latestRequestId = 0;

// 머리글 정렬 버튼: 같은 항목을 다시 누르면 방향을 뒤집고, 다른 항목이면 오름차순으로 시작한다. 정렬이 바뀌면 첫 쪽부터 다시 읽는다.
sortButtons.forEach((button) => {
    button.addEventListener('click', () => {
        const field = button.dataset.sort;
        if (state.sortField === field) {
            state.sortDirection = state.sortDirection === 'asc' ? 'desc' : 'asc';
        } else {
            state.sortField = field;
            state.sortDirection = 'asc';
        }
        state.page = 0;
        loadAdmins();
    });
});

// 화면이 열리면 첫 목록을 불러온다.
loadAdmins();

// 현재 정렬·쪽 조건으로 관리자 목록(FNC-A-003)을 불러와 표를 다시 그린다.
async function loadAdmins() {
    // 이번 요청의 번호를 받아 둔다(앞서 시작된 응답이 늦게 와도 덮어쓰지 않게 하려는 장치).
    const requestId = ++latestRequestId;
    errorNotice.hidden = true;
    errorNotice.textContent = '';
    // 서버 쪽 페이지 번호는 0부터. 정렬은 '항목,방향' 형식으로 보낸다.
    const params = { page: state.page, size: PAGE_SIZE };
    if (state.sortField) {
        params.sort = `${state.sortField},${state.sortDirection}`;
    }
    try {
        // 오류는 표 위 문구로 보이므로 api.js의 토스트는 끈다
        const page = await get(ADMINS_API, params, { toast: false });
        // 그 사이 더 새 요청이 시작됐다면 이 응답은 버린다.
        if (requestId === latestRequestId) {
            render(page);
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            showError(error);
        }
    }
}

// 목록 응답으로 표 본문·빈 목록 안내·총 인원·정렬 표시·페이지 버튼을 갱신한다.
function render(page) {
    // 기존 행을 모두 새 행으로 바꾼다.
    tableBody.replaceChildren(...page.items.map(toRow));
    emptyNotice.hidden = page.items.length > 0;
    totalText.textContent = `총 ${formatNumber(page.totalElements)}명`;
    renderSortState();
    renderPagination(page);
}

// 서버 값은 모두 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
// 관리자 한 명을 표의 한 행(tr)으로 만든다. 열 순서는 admins.html 표 머리글과 같아야 한다.
function toRow(admin) {
    const row = document.createElement('tr');
    row.append(
        cell(admin.loginId),
        cell(admin.name),
        cell(ROLE_LABELS[admin.role] ?? admin.role),
        statusCell(admin.status),
        cell(formatDateTime(admin.lastActiveAt)),
        activeCell(admin.active),
        actionsCell(admin),
    );
    return row;
}

// 최고 관리자 행에는 버튼이 없고(screens.md SCR-A-003), 본인 행에는 '본인 계정'만 보인다.
// 수정·비밀번호 재설정(FNC-A-010) 버튼은 같은 수정 창을 연다(SCR-A-002와 같은 창)
// 행 끝의 동작 버튼 칸. 역할과 본인 여부, 활동 중 여부에 따라 보이는 버튼이 달라진다.
function actionsCell(admin) {
    const td = document.createElement('td');
    td.className = 'admin-actions';
    const wrap = document.createElement('div');
    wrap.className = 'row-actions';
    // 최고 관리자 행은 수정·정지 같은 버튼이 없다. 단, 로그인한 본인 행에만 '본인 계정' 표시를 한다.
    if (admin.role === 'SUPER_ADMIN') {
        if (admin.loginId === currentLoginId) {
            const self = document.createElement('span');
            self.className = 'muted admin-self';
            self.textContent = SELF_LABEL;
            wrap.append(self);
        }
    // 일반 관리자 행: 수정, 비밀번호 재설정, 비활성화/활성화 버튼을 보인다.
    } else {
        wrap.append(
            detailButton(admin, '수정', false),
            detailButton(admin, '비밀번호 재설정', true),
        );
        wrap.append(statusButton(admin));
        // 활동 중일 때만 보인다(screens.md SCR-A-003)
        if (admin.active) {
            wrap.append(forceLogoutButton(admin));
        }
    }
    td.append(wrap);
    return td;
}

// ---- 행 동작 확인 창 (강제 로그아웃 FNC-A-008, 비활성화·활성화 FNC-A-011 공용) ----

// 행 동작 확인 창의 요소들. 강제 로그아웃과 비활성화·활성화가 같은 창을 쓴다.
const confirmModal = document.getElementById('admin-confirm-modal');
const confirmTitle = document.getElementById('admin-confirm-title');
const confirmMessage = document.getElementById('admin-confirm-message');
const confirmOk = document.getElementById('admin-confirm-ok');
// 열려 있는 확인 창이 확인되면 할 일. run은 성공 시 토스트로 보일 문장을 돌려준다
// confirmAction: 확인을 누르면 실행할 함수. confirming: 실행 중 여부.
let confirmAction = null;
let confirming = false;

// danger가 false면 확인 버튼을 파란 기본 버튼으로 보인다(되살리는 '활성화')
// 확인 창을 연다. 제목·본문·확인 버튼 문구를 호출한 쪽이 정하고, danger 여부로 버튼 색(위험 빨강/기본 파랑)을 고른다.
function openConfirm({ title, message, okLabel, run, danger = true }) {
    confirmAction = run;
    confirmTitle.textContent = title;
    confirmMessage.textContent = message;
    confirmOk.textContent = okLabel;
    confirmOk.className = danger ? 'btn danger' : 'btn primary';
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

// 확인 창을 닫고 대기 중이던 동작을 비운다. 실행 중이면 무시한다.
function closeConfirm() {
    if (confirming) {
        return; // 요청이 끝나기 전에는 닫지 않는다(끝난 뒤 새로 연 창을 닫는 일을 막는다)
    }
    confirmModal.hidden = true;
    confirmAction = null;
}

// 확인 버튼 처리. 동작 함수를 먼저 꺼내 두고 실행하며, 성공하면 성공 문장을 토스트로 보이고 목록을 다시 읽는다.
async function runConfirmed() {
    if (!confirmAction || confirming) {
        return;
    }
    const run = confirmAction;
    // 중복 클릭 방지로 확인 버튼을 잠근다.
    confirming = true;
    confirmOk.disabled = true;
    let succeeded = false;
    try {
        // run은 서버 요청을 보낸 뒤 성공 토스트 문장을 돌려준다.
        showToast(await run());
        succeeded = true;
    // 실패하면 서버 메시지(ApiError)나 일반 문구를 토스트로 알리고 목록은 그대로 둔다.
    } catch (error) {
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
    }
    confirming = false;
    closeConfirm();
    if (succeeded) {
        loadAdmins();
    }
}

// '수정'과 '비밀번호 재설정'(FNC-A-010)은 같은 수정 창을 연다. 재설정은 곧바로 확인 창을 띄운다. 저장·재설정을 했으면 창을 닫을 때 목록을 새로 읽는다
// 수정/비밀번호 재설정 버튼을 만든다. 누르면 수정 창을 열고, 창에서 변경이 있으면 loadAdmins로 목록을 새로 읽는다.
function detailButton(admin, label, askReset) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = label;
    button.addEventListener('click', () => openAdminDetail(admin, loadAdmins, askReset));
    return button;
}

// 사용 중이면 '비활성화', 비활성화됐으면 '활성화'(FNC-A-011). 둘 다 확인 창을 거친다
// 상태에 따라 '비활성화'(위험 색) 또는 '활성화'(기본 색) 버튼을 만든다. 누르면 확인 창이 열리고, 확인 시 disable/enable API를 부른다.
function statusButton(admin) {
    // 현재 정상 상태면 이번 동작은 '비활성화', 아니면 '활성화'다.
    const disabling = admin.status === 'ACTIVE';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = disabling ? 'btn danger sm' : 'btn primary sm';
    button.textContent = disabling ? '비활성화' : '활성화';
    button.addEventListener('click', () =>
        openConfirm({
            title: disabling ? '관리자 비활성화' : '관리자 활성화',
            message: disabling
                ? `'${admin.loginId}' 관리자를 비활성화할까요? 로그인 중인 세션이 바로 종료되고 다시 로그인할 수 없습니다.`
                : `'${admin.loginId}' 관리자를 다시 사용하게 할까요?`,
            okLabel: disabling ? '비활성화' : '활성화',
            danger: disabling,
            // 확인 창에서 확인하면 실행된다. 서버가 오류를 보내면 예외가 runConfirmed로 올라가 토스트로 보인다.
            run: async () => {
                await post(
                    `${ADMINS_API}/${admin.id}/${disabling ? 'disable' : 'enable'}`,
                    undefined,
                    { toast: false },
                );
                return disabling
                    ? `'${admin.loginId}' 관리자를 비활성화했습니다`
                    : `'${admin.loginId}' 관리자를 활성화했습니다`;
            },
        }),
    );
    return button;
}

// '강제 로그아웃' 버튼(FNC-A-008). 확인 후 해당 관리자의 모든 세션을 종료시키고, 종료된 세션 수를 알린다.
function forceLogoutButton(admin) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '강제 로그아웃';
    button.addEventListener('click', () =>
        openConfirm({
            title: '강제 로그아웃',
            message: `'${admin.loginId}' 관리자를 로그아웃시킬까요? 열려 있는 모든 세션이 종료됩니다.`,
            okLabel: '로그아웃',
            run: async () => {
                const result = await post(`${ADMINS_API}/${admin.id}/logout`, undefined, {
                    toast: false,
                });
                return `${result.deletedSessions}개 세션을 종료했습니다`;
            },
        }),
    );
    return button;
}

// 확인 창의 닫기 버튼, 배경 클릭, 확인 버튼, Esc 키를 각 동작에 연결한다.
confirmModal
    .querySelectorAll('[data-confirm-close]')
    .forEach((button) => button.addEventListener('click', closeConfirm));
confirmModal.addEventListener('mousedown', (event) => {
    if (event.target === confirmModal) {
        closeConfirm();
    }
});
confirmOk.addEventListener('click', runConfirmed);
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !confirmModal.hidden) {
        closeConfirm();
    }
});

// 일반 텍스트 칸. 값이 없으면 '-'를 보이고, textContent를 써서 값이 HTML로 해석되지 않게 한다.
function cell(text) {
    const td = document.createElement('td');
    td.textContent = text ?? EMPTY_VALUE;
    return td;
}

// 상태를 색 칩으로 보인다. 모르는 상태는 회색(off) 칩에 원문을 보인다.
function statusCell(status) {
    const td = document.createElement('td');
    const chip = document.createElement('span');
    chip.className = `chip ${STATUS_CHIPS[status] ?? 'off'}`;
    chip.textContent = STATUS_LABELS[status] ?? status;
    td.append(chip);
    return td;
}

// 활동 중이면 초록 점(사용자 관리 SCR-A-001과 같은 표시)
// 현재 활동 중(로그인 세션 있음)이면 점을 보이고, 보조 기술을 위해 role과 aria-label로 뜻을 알린다.
function activeCell(active) {
    const td = document.createElement('td');
    if (active) {
        const dot = document.createElement('i');
        dot.className = 'dot l1';
        dot.setAttribute('role', 'img');
        dot.setAttribute('aria-label', ACTIVE_LABEL);
        dot.title = ACTIVE_LABEL;
        td.append(dot);
    }
    return td;
}

// 정렬 중인 항목은 aria-sort와 화살표로 방향을 알리고, 나머지는 기본 표시(↕)로 되돌린다.
function renderSortState() {
    sortButtons.forEach((button) => {
        const field = button.dataset.sort;
        const header = button.closest('th');
        // 처음 한 번 원래 글자를 data-label에 보관해 두고, 화살표를 붙였다 떼도 원래 글자를 잃지 않게 한다.
        const label = button.dataset.label ?? button.textContent;
        button.dataset.label = label;
        if (field === state.sortField) {
            header.setAttribute(
                'aria-sort',
                state.sortDirection === 'asc' ? 'ascending' : 'descending',
            );
            button.textContent = `${label} ${SORT_MARKS[state.sortDirection]}`;
        } else {
            header.setAttribute('aria-sort', 'none');
            button.textContent = `${label} ${SORT_MARKS.none}`;
        }
    });
}

// 시안처럼 한 쪽이어도 페이지 번호 1을 보인다
// 이전/다음 버튼과 현재 쪽 앞뒤 번호 버튼을 만든다. 쪽이 하나뿐이어도 번호 1은 보인다.
function renderPagination(page) {
    // 서버가 0쪽이라고 알려 줘도 최소 1쪽으로 본다.
    const totalPages = Math.max(page.totalPages, 1);
    const buttons = [];
    if (totalPages > 1) {
        buttons.push(pageButton('이전', page.page - 1, page.page === 0));
    }
    // 현재 쪽 앞뒤 PAGE_WINDOW개 범위로 번호를 제한한다.
    const first = Math.max(0, page.page - PAGE_WINDOW);
    const last = Math.min(totalPages - 1, page.page + PAGE_WINDOW);
    for (let index = first; index <= last; index++) {
        const button = pageButton(String(index + 1), index, index === page.page);
        if (index === page.page) {
            button.setAttribute('aria-current', 'page');
        }
        buttons.push(button);
    }
    if (totalPages > 1) {
        buttons.push(pageButton('다음', page.page + 1, page.page >= totalPages - 1));
    }
    pagination.replaceChildren(...buttons);
}

// 페이지 이동 버튼 하나. disabled면 눌러도 이동하지 않는다(현재 쪽, 처음·마지막 경계).
function pageButton(text, targetPage, disabled) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = text;
    button.disabled = disabled;
    button.addEventListener('click', () => {
        state.page = targetPage;
        loadAdmins();
    });
    return button;
}

// 목록 오류를 표 위 문구로 보인다. 서버 오류가 아닌 예외는 콘솔에 남기고 일반 문구를 보인다.
function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        errorNotice.textContent = DEFAULT_ERROR_MESSAGE;
    } else {
        errorNotice.textContent = error.message;
    }
    errorNotice.hidden = false;
}

// ---- 관리자 추가 창 (FNC-A-009) ----

// 관리자 추가 창의 요소들. formParts는 입력 화면, createdParts는 생성 완료(임시 비밀번호) 화면이라 둘 중 하나만 보인다.
const modal = document.getElementById('admin-modal');
const form = document.getElementById('admin-form');
const formError = document.getElementById('admin-form-error');
const saveButton = document.getElementById('admin-save');
const formParts = ['admin-form-fields', 'admin-form-foot'].map((id) => document.getElementById(id));
const createdParts = ['admin-created', 'admin-created-foot'].map((id) =>
    document.getElementById(id),
);
const tempPassword = document.getElementById('admin-temp-password');
const copyButton = document.getElementById('admin-temp-copy');
// 409 아이디 중복은 아이디 칸 아래에 보인다
// 서버 오류 코드 중 특정 칸에 붙일 것: 아이디 중복(409)은 아이디 칸 아래에 보인다.
const FORM_ERROR_TARGET = { DUPLICATE_LOGIN_ID: 'loginId' };
const COPY_LABEL = '복사';
const COPIED_LABEL = '복사됨';
// 만든 뒤 창을 닫을 때만 목록을 새로 읽는다
// 계정을 만들었는지. 만든 경우에만 창을 닫을 때 목록을 새로 읽는다.
let created = false;

// 창을 처음 상태로 되돌려 연다(입력 지움, 오류 지움, 입력 화면 표시).
function openModal() {
    form.reset();
    clearFormErrors();
    showCreated(false);
    created = false;
    modal.hidden = false;
    form.elements.loginId.focus();
}

// 닫으면 임시 비밀번호를 화면에서 지운다(conventions.md '임시 비밀번호': 한 번만 보여 줌)
// 창을 닫고 임시 비밀번호를 DOM에서 지운다. 새 계정이 생겼다면 첫 쪽부터 목록을 다시 읽어 새 계정(최근 생성)이 보이게 한다.
function closeModal() {
    modal.hidden = true;
    tempPassword.textContent = '';
    if (created) {
        created = false;
        state.page = 0;
        loadAdmins();
    }
}

// true면 생성 완료 화면, false면 입력 화면을 보인다.
function showCreated(visible) {
    formParts.forEach((part) => {
        part.hidden = visible;
    });
    createdParts.forEach((part) => {
        part.hidden = !visible;
    });
}

// 창 위 오류와 칸별 오류, 빨간 테두리를 모두 지운다.
function clearFormErrors() {
    formError.hidden = true;
    formError.textContent = '';
    form.querySelectorAll('[data-error-for]').forEach((hint) => {
        hint.hidden = true;
        hint.textContent = '';
    });
    form.querySelectorAll('.input.err').forEach((input) => input.classList.remove('err'));
}

// field 이름과 같은 data-error-for 칸에 문구를 보이고 입력칸에 오류 테두리를 준다. 칸이 없으면 창 위 문구로 보인다.
function showFieldError(field, message) {
    const hint = form.querySelector(`[data-error-for="${field}"]`);
    if (!hint) {
        formError.textContent = message;
        formError.hidden = false;
        return;
    }
    hint.textContent = message;
    hint.hidden = false;
    form.elements[field]?.classList?.add('err');
}

// 관리자 추가 제출(FNC-A-009). 서버가 임시 비밀번호를 만들어 돌려준다.
async function submitForm(event) {
    event.preventDefault();
    clearFormErrors();
    // 공백을 잘라 보낸다. 형식 검증은 서버가 하고 오류는 칸별로 돌려받는다.
    const body = {
        loginId: form.elements.loginId.value.trim(),
        name: form.elements.name.value.trim(),
        email: form.elements.email.value.trim(),
        phone: form.elements.phone.value.trim(),
    };
    // 요청 중 중복 제출을 막는다. finally에서 푼다.
    saveButton.disabled = true;
    try {
        // 성공하면 생성 완료 화면으로 바꾸고 새 아이디와 임시 비밀번호를 보인다.
        const result = await post(ADMINS_API, body, { toast: false });
        created = true;
        document.getElementById('admin-created-login-id').textContent = body.loginId;
        tempPassword.textContent = result.temporaryPassword;
        copyButton.textContent = COPY_LABEL;
        showCreated(true);
    // 예상 밖 예외는 콘솔에 남기고 일반 문구, 칸별 검증 오류는 각 칸 아래에, 그 외 서버 오류는 코드에 맞는 칸(없으면 창 위)에 보인다.
    } catch (error) {
        if (!(error instanceof ApiError)) {
            console.error(error);
            showFieldError('', DEFAULT_ERROR_MESSAGE);
        } else if (error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else {
            showFieldError(FORM_ERROR_TARGET[error.code] ?? '', error.message);
        }
    } finally {
        saveButton.disabled = false;
    }
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

// 이벤트 연결부: '관리자 추가' 버튼, 창 닫기 버튼, 배경 클릭, Esc 키, 폼 제출, 복사 버튼.
document.getElementById('admin-add').addEventListener('click', openModal);
modal
    .querySelectorAll('[data-close]')
    .forEach((button) => button.addEventListener('click', closeModal));
modal.addEventListener('mousedown', (event) => {
    // 임시 비밀번호를 보여 주는 중에는 바깥을 눌러 실수로 닫히지 않게 한다
    // 임시 비밀번호를 보는 중(created)에는 실수로 닫혀 비밀번호를 놓치지 않게 배경 클릭·Esc로는 닫히지 않는다. 닫기 버튼으로만 닫는다.
    if (event.target === modal && !created) {
        closeModal();
    }
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !modal.hidden && !created) {
        closeModal();
    }
});
form.addEventListener('submit', submitForm);
copyButton.addEventListener('click', copyTempPassword);
