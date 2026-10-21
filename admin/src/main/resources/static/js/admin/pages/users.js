// 사용자 관리 화면(SCR-A-001). 사용자 목록(FNC-A-002)을 검색·정렬·페이지 이동하며 GET /api/v1/users로 불러온다.
import { ApiError, get } from '../../common/api.js';

const USERS_API = '/api/v1/users';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const EMPTY_VALUE = '-';
const ACTIVE_MARK = '●';
const ACTIVE_LABEL = '활동 중';
// 현재 페이지 앞뒤로 보여 줄 페이지 번호 수
const PAGE_WINDOW = 2;
// 코드 값의 화면 표시 이름(conventions.md 'user_account.status')
const STATUS_LABELS = { PENDING: '미승인', ACTIVE: '정상', WITHDRAWN: '탈퇴' };
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

const state = { filters: {}, size: 20, sortField: 'createdAt', sortDirection: 'desc', page: 0 };
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
        const page = await get(USERS_API, {
            ...state.filters,
            sort: `${state.sortField},${state.sortDirection}`,
            page: state.page,
            size: state.size,
        });
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
    renderSortState();
    renderPagination(page);
}

// 서버 값은 모두 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
function toRow(user) {
    const row = document.createElement('tr');
    row.append(
        cell(user.loginId),
        cell(user.name),
        cell(STATUS_LABELS[user.status] ?? user.status),
        cell(formatDateTime(user.createdAt)),
        cell(formatDateTime(user.lastActiveAt)),
        activeCell(user.active),
    );
    return row;
}

function cell(text) {
    const td = document.createElement('td');
    td.textContent = text ?? EMPTY_VALUE;
    return td;
}

// 색은 공통 CSS(common/static/css)가 생기면 active-dot class에 준다(#32에서 정함)
function activeCell(active) {
    const td = document.createElement('td');
    if (active) {
        const dot = document.createElement('span');
        dot.className = 'active-dot';
        dot.textContent = ACTIVE_MARK;
        dot.setAttribute('aria-label', ACTIVE_LABEL);
        dot.title = ACTIVE_LABEL;
        td.append(dot);
    }
    return td;
}

// 서버가 +09:00 시각을 주므로 브라우저 시간대로 바꾸지 않고 글자를 잘라 yyyy-MM-dd HH:mm로 보인다.
// common/format.js가 생기면 그쪽으로 옮긴다(code-conventions.md 5-3)
function formatDateTime(isoText) {
    if (!isoText) {
        return EMPTY_VALUE;
    }
    return isoText.slice(0, 16).replace('T', ' ');
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
            button.textContent = `${label} ${ascending ? '▲' : '▼'}`;
        } else {
            header.setAttribute('aria-sort', 'none');
            button.textContent = label;
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
