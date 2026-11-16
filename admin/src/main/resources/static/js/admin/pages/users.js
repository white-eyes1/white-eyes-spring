// 사용자 관리 화면(SCR-A-001). 사용자 목록(FNC-A-002)을 검색·정렬·페이지 이동하며 GET /api/v1/users로 불러오고,
// 미승인 행의 '승인'(FNC-A-006)은 POST /api/v1/users/{id}/approve로 보낸다.
// 정상 행의 '승인 취소'(FNC-A-007)는 확인 창 뒤 POST /api/v1/users/{id}/unapprove로 보낸다.
// 활동 중인 행의 '강제 로그아웃'(FNC-A-008)은 확인 창 뒤 POST /api/v1/users/{id}/logout으로 보낸다.
// 행을 누르면 사용자 상세 창(SCR-A-002, user-detail.js)이 열리고 거기서 비밀번호 재설정(FNC-A-005)을 한다.
// 공용 모듈(API 호출·오류 예외, 날짜·숫자 표시, 토스트)과 행을 누르면 열리는 사용자 상세 창을 가져온다.
import { ApiError, get, post } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { openUserDetail } from '../user-detail.js';

// 사용자 REST API 공통 주소와 일반 오류 문구.
const USERS_API = '/api/v1/users';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
// 승인하려는 사이 다른 관리자가 상태를 바꾼 경우(409)의 문구(screens.md SCR-A-001)
const STATUS_CHANGED_MESSAGE = '상태가 바뀌었습니다';
// 값이 없을 때 '-', 활동 중 점의 보조 설명 문구.
const EMPTY_VALUE = '-';
const ACTIVE_LABEL = '활동 중';
// 현재 페이지 앞뒤로 보여 줄 페이지 번호 수
const PAGE_WINDOW = 2;
// 사용자 상태 코드를 화면 이름과 칩 색(CSS 클래스)으로 바꾸는 사전, 정렬 방향 화살표.
// 코드 값의 화면 표시 이름(conventions.md 'user_account.status'). 칩 색은 시안(SCR-A-001)을 따른다
const STATUS_LABELS = { PENDING: '미승인', ACTIVE: '정상', WITHDRAWN: '탈퇴' };
const STATUS_CHIPS = { PENDING: 'prep', ACTIVE: 'done', WITHDRAWN: 'off' };
const SORT_MARKS = { asc: '↑', desc: '↓', none: '↕' };
// 다른 칸으로 정렬을 바꿀 때의 처음 방향: 시각은 최근 먼저, 아이디는 가나다순(#32에서 정함)
// 정렬 항목을 바꿀 때 처음 방향. 가입·마지막 활동은 최근이 먼저 보이도록 내림차순, 아이디는 오름차순.
const FIRST_DIRECTIONS = { createdAt: 'desc', lastActiveAt: 'desc', loginId: 'asc' };
// 서버가 칸별 검증 오류를 줄 때 필드 이름을 사용자에게 보일 한글 이름으로 바꾸는 사전(showError에서 사용).
const FIELD_LABELS = {
    keyword: '검색어',
    status: '상태',
    joinedFrom: '가입일 시작',
    joinedTo: '가입일 끝',
    active: '활동 중',
    sort: '정렬',
    page: '페이지',
    size: '표시 개수',
};

// 검색 폼, 조회 버튼, 표 본문, 정렬 버튼, 안내 문구들(축약 안내·빈 목록·오류), 페이지 영역, 총 인원 요소를 한 번만 찾아 둔다.
const form = document.getElementById('user-search-form');
const submitButton = form.querySelector('button[type="submit"]');
const tableBody = document.getElementById('user-table-body');
const sortButtons = document.querySelectorAll('#user-table button[data-sort]');
const truncatedNotice = document.getElementById('user-list-truncated');
const emptyNotice = document.getElementById('user-list-empty');
const errorNotice = document.getElementById('user-list-error');
const pagination = document.getElementById('user-pagination');
const totalText = document.getElementById('user-total');

// 승인 취소·강제 로그아웃이 함께 쓰는 확인 창의 요소들.
const confirmModal = document.getElementById('user-confirm-modal');
const confirmTitle = document.getElementById('user-confirm-title');
const confirmMessage = document.getElementById('user-confirm-message');
const confirmOk = document.getElementById('user-confirm-ok');

// 화면 상태: 검색 조건, 표시 개수, 정렬 항목·방향(기본은 가입일 최근순), 현재 쪽(0부터).
const state = { filters: {}, size: 20, sortField: 'createdAt', sortDirection: 'desc', page: 0 };
// 확인 창에서 누를 일(승인 취소·강제 로그아웃)과 요청 진행 중 여부
let confirmAction = null;
let confirming = false;
// 조회를 빠르게 여러 번 누르면 마지막 요청의 결과만 그린다
let latestRequestId = 0;

// '조회' 제출: 폼 값을 읽어 조건으로 저장하고 첫 쪽부터 다시 조회한다.
form.addEventListener('submit', (event) => {
    event.preventDefault();
    readForm();
    state.page = 0;
    loadUsers();
});

// 머리글 정렬 버튼: 같은 항목이면 방향을 뒤집고, 다른 항목이면 그 항목의 처음 방향으로 바꾼다. 정렬이 바뀌면 첫 쪽부터 읽는다.
sortButtons.forEach((button) => {
    button.addEventListener('click', () => {
        const field = button.dataset.sort;
        if (state.sortField === field) {
            state.sortDirection = state.sortDirection === 'asc' ? 'desc' : 'asc';
        } else {
            state.sortField = field;
            state.sortDirection = FIRST_DIRECTIONS[field] ?? 'asc';
        }
        state.page = 0;
        loadUsers();
    });
});

// 시작할 때 폼의 기본값(표시 개수 등)을 조건으로 읽고 첫 목록을 불러온다.
readForm();
loadUsers();

// 표시 개수도 검색 영역에 있으므로 '조회'를 누를 때 함께 적용한다(#32에서 정함)
// 폼의 검색 조건을 읽어 state에 저장한다. 빈 값은 보내지 않아 서버가 조건 없음으로 보게 한다.
function readForm() {
    const filters = {};
    // 값이 있는 조건만 모은다. 앞뒤 공백은 잘라 공백만 입력한 경우를 빈 값으로 처리한다.
    for (const name of ['keyword', 'status', 'joinedFrom', 'joinedTo', 'active']) {
        const value = form.elements.namedItem(name).value.trim();
        if (value !== '') {
            filters[name] = value;
        }
    }
    state.filters = filters;
    state.size = Number(form.elements.namedItem('size').value);
}

// 현재 조건·정렬·쪽으로 목록(FNC-A-002)을 불러온다. 조회 중에는 조회 버튼을 잠가 중복 요청을 막는다.
async function loadUsers() {
    // 요청 순서 번호를 받아 둔다. 응답이 뒤바뀌어 도착해도 마지막 요청의 결과만 그린다.
    const requestId = ++latestRequestId;
    submitButton.disabled = true;
    errorNotice.hidden = true;
    errorNotice.textContent = '';
    try {
        // 오류는 표 위 문구로 보이므로 api.js의 토스트는 끈다
        const page = await get(
            USERS_API,
            {
                ...state.filters,
                sort: `${state.sortField},${state.sortDirection}`,
                page: state.page,
                size: state.size,
            },
            { toast: false },
        );
        // 더 새 요청이 시작됐으면 이 응답은 버린다.
        if (requestId === latestRequestId) {
            render(page);
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            showError(error);
        }
    // 마지막 요청일 때만 버튼을 푼다. 앞선 요청이 늦게 끝나 새 요청 중 버튼을 풀어 버리는 일을 막는다.
    } finally {
        if (requestId === latestRequestId) {
            submitButton.disabled = false;
        }
    }
}

// 목록 응답으로 표, 빈 목록 안내, 축약 안내(결과가 많아 일부만 보임), 총 인원, 정렬 표시, 페이지 버튼을 갱신한다.
function render(page) {
    tableBody.replaceChildren(...page.items.map(toRow));
    emptyNotice.hidden = page.items.length > 0;
    truncatedNotice.hidden = !page.truncated;
    totalText.textContent = `총 ${formatNumber(page.totalElements)}명`;
    renderSortState();
    renderPagination(page);
}

// 서버 값은 모두 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
// 사용자 한 명을 표의 행으로 만든다. data-user-id는 행을 부분 갱신할 때 찾는 용도다.
function toRow(user) {
    const row = document.createElement('tr');
    row.dataset.userId = String(user.id);
    // 열 순서는 users.html의 머리글과 같다: 아이디, 이름, 상태, 가입일, 마지막 활동, 활동 중, 동작.
    row.append(
        cell(user.loginId),
        cell(user.name),
        statusCell(user.status),
        cell(formatDateTime(user.createdAt)),
        cell(formatDateTime(user.lastActiveAt)),
        activeCell(user.active),
        actionsCell(user),
    );
    // 행의 버튼(승인·강제 로그아웃)을 누른 것은 상세 창을 열지 않는다
    // 행 어디를 눌러도 상세 창이 열리고, 창에서 저장·재설정을 하면 목록을 다시 읽는다. 행 안의 버튼(승인·강제 로그아웃 등)을 누른 클릭은 제외한다.
    row.addEventListener('click', (event) => {
        if (!event.target.closest('button')) {
            openUserDetail(user, loadUsers);
        }
    });
    return row;
}

// PENDING이면 '승인', ACTIVE면 '승인 취소'(screens.md SCR-A-001)
// 행 끝 동작 칸: 미승인이면 '승인', 정상이면 '승인 취소', 탈퇴는 상태 버튼 없음. 활동 중이면 '강제 로그아웃'이 추가된다.
function actionsCell(user) {
    const td = document.createElement('td');
    td.className = 'user-actions';
    const wrap = document.createElement('div');
    wrap.className = 'row-actions';
    if (user.status === 'PENDING') {
        wrap.append(approveButton(user));
    } else if (user.status === 'ACTIVE') {
        wrap.append(unapproveButton(user));
    }
    // 활동 중일 때만 보인다(screens.md SCR-A-001)
    if (user.active) {
        wrap.append(forceLogoutButton(user));
    }
    td.append(wrap);
    return td;
}

// 확인 창 없이 바로 요청한다(#33에서 정함)
// '승인' 버튼 생성. 클릭하면 approve를 부른다.
function approveButton(user) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn primary sm';
    button.textContent = '승인';
    button.addEventListener('click', () => approve(button, user));
    return button;
}

// 승인(FNC-A-006) 요청. 중복 클릭을 막으려 버튼을 먼저 잠근다.
async function approve(button, user) {
    button.disabled = true;
    try {
        // 승인 요청은 본문이 필요 없다. 오류는 토스트를 직접 띄우려고 api.js의 토스트는 끈다.
        await post(`${USERS_API}/${user.id}/approve`, undefined, { toast: false });
        // 성공하면 그 행만 승인된 상태로 다시 그린다
        button.closest('tr').replaceWith(toRow({ ...user, status: 'ACTIVE' }));
    } catch (error) {
        // 그 사이 다른 관리자가 상태를 바꿔 이 요청이 맞지 않으면(409 INVALID_STATUS) 알리고 목록을 다시 읽는다. 그 외 오류는 알리고 버튼을 다시 켠다.
        if (error instanceof ApiError && error.code === 'INVALID_STATUS') {
            showToast(STATUS_CHANGED_MESSAGE);
            loadUsers();
            return;
        }
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
        button.disabled = false;
    }
}

// ---- 승인 취소 (FNC-A-007) ----

// '승인 취소' 버튼. 세션이 종료되는 동작이라 확인 창을 거치게 한다.
function unapproveButton(user) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '승인 취소';
    button.addEventListener('click', () =>
        openConfirm({
            title: '승인 취소',
            message: `'${user.loginId}' 사용자의 승인을 취소할까요? 미승인 상태로 돌아가고 열려 있는 모든 세션이 종료됩니다.`,
            okLabel: '승인 취소',
            run: () => unapprove(user),
        }),
    );
    return button;
}

// 성공하면 미승인·세션 없음으로 행을 다시 그린다. 그 사이 상태가 바뀌었으면(409) 목록을 새로 읽는다
// 승인 취소(FNC-A-007) 요청. 확인 창이 호출한다. 성공하면 서버를 다시 읽는 대신 그 행만 미승인·비활동 상태로 바꿔 그린다(세션이 모두 종료되므로 활동 중이 아님).
async function unapprove(user) {
    try {
        await post(`${USERS_API}/${user.id}/unapprove`, undefined, { toast: false });
        // 목록이 다시 그려졌을 수 있어 사용자 id로 현재 표에서 그 행을 다시 찾는다. 없으면(다른 쪽으로 이동 등) 건너뛴다.
        const row = [...tableBody.rows].find(
            (candidate) => candidate.dataset.userId === String(user.id),
        );
        row?.replaceWith(toRow({ ...user, status: 'PENDING', active: false }));
    } catch (error) {
        if (error instanceof ApiError && error.code === 'INVALID_STATUS') {
            showToast(STATUS_CHANGED_MESSAGE);
            loadUsers();
            return;
        }
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
    }
}

// ---- 강제 로그아웃 (FNC-A-008) ----

// '강제 로그아웃' 버튼. 확인 창을 거친다.
function forceLogoutButton(user) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '강제 로그아웃';
    button.addEventListener('click', () =>
        openConfirm({
            title: '강제 로그아웃',
            message: `'${user.loginId}' 사용자를 로그아웃시킬까요? 열려 있는 모든 세션이 종료됩니다.`,
            okLabel: '로그아웃',
            run: () => forceLogout(user),
        }),
    );
    return button;
}

// 강제 로그아웃(FNC-A-008) 요청. 종료된 세션 수를 알리고 활동 상태가 바뀌었으니 목록을 다시 읽는다.
async function forceLogout(user) {
    try {
        const result = await post(`${USERS_API}/${user.id}/logout`, undefined, { toast: false });
        showToast(`${result.deletedSessions}개 세션을 종료했습니다`);
        loadUsers();
    } catch (error) {
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
    }
}

// ---- 확인 창(승인 취소·강제 로그아웃이 함께 씀) ----

// 확인 창을 열고 제목·본문·버튼 문구를 호출한 쪽이 넘긴 값으로 채운다. 확인 시 실행할 동작(action.run)을 기억해 둔다.
function openConfirm(action) {
    confirmAction = action;
    confirmTitle.textContent = action.title;
    confirmMessage.textContent = action.message;
    confirmOk.textContent = action.okLabel;
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

// 확인 창을 닫는다. 요청 중이면 무시한다.
function closeConfirm() {
    if (confirming) {
        return; // 요청이 끝나기 전에는 닫지 않는다(끝난 뒤 새로 연 창을 닫는 일을 막는다)
    }
    confirmModal.hidden = true;
    confirmAction = null;
}

// 확인 버튼 처리. 동작이 끝날 때까지 버튼을 잠그고, 끝나면 창을 닫는다. 동작 안에서 오류를 처리하므로 여기서는 예외를 따로 받지 않는다.
async function runConfirm() {
    if (!confirmAction || confirming) {
        return;
    }
    const action = confirmAction;
    confirming = true;
    confirmOk.disabled = true;
    await action.run();
    confirming = false;
    closeConfirm();
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
confirmOk.addEventListener('click', runConfirm);
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !confirmModal.hidden) {
        closeConfirm();
    }
});

// 일반 텍스트 칸. 값이 없으면 '-'. textContent로 넣어 HTML로 해석되지 않게 한다.
function cell(text) {
    const td = document.createElement('td');
    td.textContent = text ?? EMPTY_VALUE;
    return td;
}

// 상태를 색 칩으로 보인다. 모르는 상태는 회색 칩에 원문을 보인다.
function statusCell(status) {
    const td = document.createElement('td');
    const chip = document.createElement('span');
    chip.className = `chip ${STATUS_CHIPS[status] ?? 'off'}`;
    chip.textContent = STATUS_LABELS[status] ?? status;
    td.append(chip);
    return td;
}

// 활동 중이면 초록 점(screens.md SCR-A-001)
// 활동 중이면 점을 보이고 role과 aria-label로 보조 기술에도 뜻을 알린다.
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

// 정렬 중인 항목에는 aria-sort와 방향 화살표를 달고, 나머지는 기본 표시(↕)로 되돌린다.
function renderSortState() {
    sortButtons.forEach((button) => {
        const field = button.dataset.sort;
        const header = button.closest('th');
        // 처음 한 번 원래 글자를 보관해 두어 화살표를 붙였다 떼도 원래 글자를 잃지 않게 한다.
        const label = button.dataset.label ?? button.textContent;
        button.dataset.label = label;
        if (field === state.sortField) {
            const ascending = state.sortDirection === 'asc';
            header.setAttribute('aria-sort', ascending ? 'ascending' : 'descending');
            button.textContent = `${label} ${SORT_MARKS[state.sortDirection]}`;
        } else {
            header.setAttribute('aria-sort', 'none');
            button.textContent = `${label} ${SORT_MARKS.none}`;
        }
    });
}

// 한 쪽뿐이면 페이지 버튼을 보이지 않는다. 그 외에는 이전·다음과 현재 쪽 앞뒤 번호를 보인다.
function renderPagination(page) {
    if (page.totalPages <= 1) {
        pagination.replaceChildren();
        return;
    }
    const buttons = [];
    buttons.push(pageButton('이전', page.page - 1, page.page === 0));
    const first = Math.max(0, page.page - PAGE_WINDOW);
    const last = Math.min(page.totalPages - 1, page.page + PAGE_WINDOW);
    for (let index = first; index <= last; index++) {
        const button = pageButton(String(index + 1), index, index === page.page);
        if (index === page.page) {
            button.setAttribute('aria-current', 'page');
        }
        buttons.push(button);
    }
    buttons.push(pageButton('다음', page.page + 1, page.page >= page.totalPages - 1));
    pagination.replaceChildren(...buttons);
}

// 페이지 이동 버튼. disabled면 눌러도 이동하지 않는다.
function pageButton(text, targetPage, disabled) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = text;
    button.disabled = disabled;
    button.addEventListener('click', () => {
        state.page = targetPage;
        loadUsers();
    });
    return button;
}

// 목록 오류를 표 위 문구로 보인다. 칸별 검증 오류는 한글 칸 이름과 함께 덧붙이고, 서버 오류가 아닌 예외는 콘솔에 남기고 일반 문구를 보인다.
function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        errorNotice.textContent = DEFAULT_ERROR_MESSAGE;
    } else if (error.fieldErrors.length > 0) {
        const details = error.fieldErrors.map(
            (fieldError) =>
                `${FIELD_LABELS[fieldError.field] ?? fieldError.field}: ${fieldError.message}`,
        );
        errorNotice.textContent = `${error.message} (${details.join(' / ')})`;
    } else {
        errorNotice.textContent = error.message;
    }
    errorNotice.hidden = false;
}
