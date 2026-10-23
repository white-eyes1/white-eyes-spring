// 관리자 관리 화면(SCR-A-003). 관리자 목록(FNC-A-003)을 GET /api/v1/admins로 불러와 아이디 정렬·페이지 이동한다.
// 시안·screens.md에 검색 줄이 없어 조건 없이 부른다(API는 검색 조건을 받음). 기본 순서는 API 기본값(최근 생성 순)이다.
import { ApiError, get } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';

const ADMINS_API = '/api/v1/admins';
const PAGE_SIZE = 20;
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const EMPTY_VALUE = '-';
const ACTIVE_LABEL = '활동 중';
const PAGE_WINDOW = 2;
// 코드 값의 화면 표시 이름(conventions.md admin_account.role·status). 칩 색은 시안(SCR-A-003)을 따른다
const ROLE_LABELS = { ADMIN: '관리자', SUPER_ADMIN: '최고 관리자' };
const STATUS_LABELS = { ACTIVE: '정상', DISABLED: '비활성화' };
const STATUS_CHIPS = { ACTIVE: 'done', DISABLED: 'off' };
const SORT_MARKS = { asc: '↑', desc: '↓', none: '↕' };

const tableBody = document.getElementById('admin-table-body');
const sortButtons = document.querySelectorAll('#admin-table button[data-sort]');
const emptyNotice = document.getElementById('admin-list-empty');
const errorNotice = document.getElementById('admin-list-error');
const pagination = document.getElementById('admin-pagination');
const totalText = document.getElementById('admin-total');

// sortField가 null이면 정렬을 보내지 않아 API 기본 순서를 쓴다
const state = { sortField: null, sortDirection: 'asc', page: 0 };
let latestRequestId = 0;

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

loadAdmins();

async function loadAdmins() {
    const requestId = ++latestRequestId;
    errorNotice.hidden = true;
    errorNotice.textContent = '';
    const params = { page: state.page, size: PAGE_SIZE };
    if (state.sortField) {
        params.sort = `${state.sortField},${state.sortDirection}`;
    }
    try {
        // 오류는 표 위 문구로 보이므로 api.js의 토스트는 끈다
        const page = await get(ADMINS_API, params, { toast: false });
        if (requestId === latestRequestId) {
            render(page);
        }
    } catch (error) {
        if (requestId === latestRequestId) {
            showError(error);
        }
    }
}

function render(page) {
    tableBody.replaceChildren(...page.items.map(toRow));
    emptyNotice.hidden = page.items.length > 0;
    totalText.textContent = `총 ${formatNumber(page.totalElements)}명`;
    renderSortState();
    renderPagination(page);
}

// 서버 값은 모두 textContent로 넣는다(code-conventions.md 5-3, XSS 방지)
function toRow(admin) {
    const row = document.createElement('tr');
    row.append(
        cell(admin.loginId),
        cell(admin.name),
        cell(ROLE_LABELS[admin.role] ?? admin.role),
        statusCell(admin.status),
        cell(formatDateTime(admin.lastActiveAt)),
        activeCell(admin.active),
    );
    return row;
}

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

// 활동 중이면 초록 점(사용자 관리 SCR-A-001과 같은 표시)
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
            header.setAttribute('aria-sort', state.sortDirection === 'asc' ? 'ascending' : 'descending');
            button.textContent = `${label} ${SORT_MARKS[state.sortDirection]}`;
        } else {
            header.setAttribute('aria-sort', 'none');
            button.textContent = `${label} ${SORT_MARKS.none}`;
        }
    });
}

// 시안처럼 한 쪽이어도 페이지 번호 1을 보인다
function renderPagination(page) {
    const totalPages = Math.max(page.totalPages, 1);
    const buttons = [];
    if (totalPages > 1) {
        buttons.push(pageButton('이전', page.page - 1, page.page === 0));
    }
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

function showError(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        errorNotice.textContent = DEFAULT_ERROR_MESSAGE;
    } else {
        errorNotice.textContent = error.message;
    }
    errorNotice.hidden = false;
}
