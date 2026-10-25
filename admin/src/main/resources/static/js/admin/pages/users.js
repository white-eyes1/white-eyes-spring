// 사용자 관리 화면(SCR-A-001). 사용자 목록(FNC-A-002)을 검색·정렬·페이지 이동하며 GET /api/v1/users로 불러오고,
// 미승인 행의 '승인'(FNC-A-006)은 POST /api/v1/users/{id}/approve로 보낸다.
// 활동 중인 행의 '강제 로그아웃'(FNC-A-008)은 확인 창 뒤 POST /api/v1/users/{id}/logout으로 보낸다.
// 행을 누르면 사용자 상세 창(SCR-A-002, user-detail.js)이 열리고 거기서 비밀번호 재설정(FNC-A-005)을 한다.
import { ApiError, get, post } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { openUserDetail } from '../user-detail.js';

const USERS_API = '/api/v1/users';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
// 승인하려는 사이 다른 관리자가 상태를 바꾼 경우(409)의 문구(screens.md SCR-A-001)
const STATUS_CHANGED_MESSAGE = '상태가 바뀌었습니다';
const EMPTY_VALUE = '-';
const ACTIVE_LABEL = '활동 중';
// 현재 페이지 앞뒤로 보여 줄 페이지 번호 수
const PAGE_WINDOW = 2;
// 코드 값의 화면 표시 이름(conventions.md 'user_account.status'). 칩 색은 시안(SCR-A-001)을 따른다
const STATUS_LABELS = { PENDING: '미승인', ACTIVE: '정상', WITHDRAWN: '탈퇴' };
const STATUS_CHIPS = { PENDING: 'prep', ACTIVE: 'done', WITHDRAWN: 'off' };
const SORT_MARKS = { asc: '↑', desc: '↓', none: '↕' };
// 다른 칸으로 정렬을 바꿀 때의 처음 방향: 시각은 최근 먼저, 아이디는 가나다순(#32에서 정함)
const FIRST_DIRECTIONS = { createdAt: 'desc', lastActiveAt: 'desc', loginId: 'asc' };
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

const form = document.getElementById('user-search-form');
const submitButton = form.querySelector('button[type="submit"]');
const tableBody = document.getElementById('user-table-body');
const sortButtons = document.querySelectorAll('#user-table button[data-sort]');
const truncatedNotice = document.getElementById('user-list-truncated');
const emptyNotice = document.getElementById('user-list-empty');
const errorNotice = document.getElementById('user-list-error');
const pagination = document.getElementById('user-pagination');
const totalText = document.getElementById('user-total');

const confirmModal = document.getElementById('user-confirm-modal');
const confirmMessage = document.getElementById('user-confirm-message');
const confirmOk = document.getElementById('user-confirm-ok');

const state = { filters: {}, size: 20, sortField: 'createdAt', sortDirection: 'desc', page: 0 };
// 확인 창에서 고른 사용자와 요청 진행 중 여부
let confirmTarget = null;
let forcing = false;
// 조회를 빠르게 여러 번 누르면 마지막 요청의 결과만 그린다
let latestRequestId = 0;

form.addEventListener('submit', (event) => {
    event.preventDefault();
    readForm();
    state.page = 0;
    loadUsers();
});

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

readForm();
loadUsers();

// 표시 개수도 검색 영역에 있으므로 '조회'를 누를 때 함께 적용한다(#32에서 정함)
function readForm() {
    const filters = {};
    for (const name of ['keyword', 'status', 'joinedFrom', 'joinedTo', 'active']) {
        const value = form.elements.namedItem(name).value.trim();
        if (value !== '') {
            filters[name] = value;
        }
    }
    state.filters = filters;
    state.size = Number(form.elements.namedItem('size').value);
}

async function loadUsers() {
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
        if (requestId === latestRequestId) {
            render(page);
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            showError(error);
        }
    } finally {
        if (requestId === latestRequestId) {
            submitButton.disabled = false;
        }
    }
}

function render(page) {
    tableBody.replaceChildren(...page.items.map(toRow));
    emptyNotice.hidden = page.items.length > 0;
    truncatedNotice.hidden = !page.truncated;
    totalText.textContent = `총 ${formatNumber(page.totalElements)}명`;
    renderSortState();
    renderPagination(page);
}

// 서버 값은 모두 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
function toRow(user) {
    const row = document.createElement('tr');
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
    row.addEventListener('click', (event) => {
        if (!event.target.closest('button')) {
            openUserDetail(user, loadUsers);
        }
    });
    return row;
}

// 승인 취소(FNC-A-007) 버튼은 그 기능의 이슈에서 이 칸에 더한다
function actionsCell(user) {
    const td = document.createElement('td');
    td.className = 'user-actions';
    const wrap = document.createElement('div');
    wrap.className = 'row-actions';
    if (user.status === 'PENDING') {
        wrap.append(approveButton(user));
    }
    // 활동 중일 때만 보인다(screens.md SCR-A-001)
    if (user.active) {
        wrap.append(forceLogoutButton(user));
    }
    td.append(wrap);
    return td;
}

// 확인 창 없이 바로 요청한다(#33에서 정함)
function approveButton(user) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn primary sm';
    button.textContent = '승인';
    button.addEventListener('click', () => approve(button, user));
    return button;
}

async function approve(button, user) {
    button.disabled = true;
    try {
        await post(`${USERS_API}/${user.id}/approve`, undefined, { toast: false });
        // 성공하면 그 행만 승인된 상태로 다시 그린다
        button.closest('tr').replaceWith(toRow({ ...user, status: 'ACTIVE' }));
    } catch (error) {
        if (error instanceof ApiError && error.code === 'INVALID_STATUS') {
            showToast(STATUS_CHANGED_MESSAGE);
            loadUsers();
            return;
        }
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
        button.disabled = false;
    }
}

// ---- 강제 로그아웃 (FNC-A-008) ----

function forceLogoutButton(user) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '강제 로그아웃';
    button.addEventListener('click', () => openConfirm(user));
    return button;
}

function openConfirm(user) {
    confirmTarget = user;
    confirmMessage.textContent = `'${user.loginId}' 사용자를 로그아웃시킬까요? 열려 있는 모든 세션이 종료됩니다.`;
    confirmOk.disabled = false;
    confirmModal.hidden = false;
    confirmOk.focus();
}

function closeConfirm() {
    if (forcing) {
        return; // 요청이 끝나기 전에는 닫지 않는다(끝난 뒤 새로 연 창을 닫는 일을 막는다)
    }
    confirmModal.hidden = true;
    confirmTarget = null;
}

async function forceLogout() {
    if (!confirmTarget || forcing) {
        return;
    }
    const user = confirmTarget;
    forcing = true;
    confirmOk.disabled = true;
    try {
        const result = await post(`${USERS_API}/${user.id}/logout`, undefined, { toast: false });
        showToast(`${result.deletedSessions}개 세션을 종료했습니다`);
        forcing = false;
        closeConfirm();
        loadUsers();
        return;
    } catch (error) {
        showToast(error instanceof ApiError ? error.message : DEFAULT_ERROR_MESSAGE);
    }
    forcing = false;
    closeConfirm();
}

confirmModal.querySelectorAll('[data-confirm-close]').forEach((button) => button.addEventListener('click', closeConfirm));
confirmModal.addEventListener('mousedown', (event) => {
    if (event.target === confirmModal) {
        closeConfirm();
    }
});
confirmOk.addEventListener('click', forceLogout);
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !confirmModal.hidden) {
        closeConfirm();
    }
});

function cell(text) {
    const td = document.createElement('td');
    td.textContent = text ?? EMPTY_VALUE;
    return td;
}

function statusCell(status) {
    const td = document.createElement('td');
    const chip = document.createElement('span');
    chip.className = `chip ${STATUS_CHIPS[status] ?? 'off'}`;
    chip.textContent = STATUS_LABELS[status] ?? status;
    td.append(chip);
    return td;
}

// 활동 중이면 초록 점(screens.md SCR-A-001)
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

function renderSortState() {
    sortButtons.forEach((button) => {
        const field = button.dataset.sort;
        const header = button.closest('th');
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

function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        errorNotice.textContent = DEFAULT_ERROR_MESSAGE;
    } else if (error.fieldErrors.length > 0) {
        const details = error.fieldErrors.map(
            (fieldError) => `${FIELD_LABELS[fieldError.field] ?? fieldError.field}: ${fieldError.message}`,
        );
        errorNotice.textContent = `${error.message} (${details.join(' / ')})`;
    } else {
        errorNotice.textContent = error.message;
    }
    errorNotice.hidden = false;
}
