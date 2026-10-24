// 작업 기록 화면 SCR-A-012(FNC-A-053). 필터 줄·작업 기록 표·페이지를 GET /api/v1/histories로 채운다.
// 서버 값은 textContent로만 넣는다(code-conventions 5-3).

import { ApiError, get } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';

const HISTORIES_API = '/api/v1/histories';
const PAGE_SIZE = 20;
const KST_OFFSET = '+09:00';
const DEFAULT_DAYS = 7;
const PAGE_BUTTON_WINDOW = 2;
const EMPTY_VALUE = '-';
const NO_DETAIL_TEXT = '상세 정보가 없습니다.';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const LOAD_FAILED_TEXT = '목록을 불러오지 못했습니다.';
const CLOSED_MARK = '▸';
const OPENED_MARK = '▾';

// 코드 값의 화면 표시 이름(conventions.md work_history.actor_type·target_type)
const ACTOR_TYPE_LABEL = { USER: '사용자', ADMIN: '관리자' };
const TARGET_TYPE_LABEL = {
    USER_ACCOUNT: '사용자',
    ADMIN_ACCOUNT: '관리자',
    ZONE: '구역',
    CAMERA: '캠',
    EVENT: '이벤트',
    REGION: '지역',
    ALL: '전체',
};

const form = document.getElementById('history-filter');
const fromInput = document.getElementById('filter-from');
const toInput = document.getElementById('filter-to');
const actorTypeSelect = document.getElementById('filter-actor-type');
const loginIdInput = document.getElementById('filter-login-id');
const actionSelect = document.getElementById('filter-action');
const errorEl = document.getElementById('history-error');
const rowsEl = document.getElementById('history-rows');
const emptyEl = document.getElementById('history-empty');
const totalEl = document.getElementById('history-total');
const pagesEl = document.getElementById('history-pages');

let page = 0;
let requestSeq = 0;

init();

function init() {
    // 기본 조회 기간은 오늘을 포함한 최근 7일(screens.md SCR-A-012)
    const today = new Date();
    toInput.value = formatDate(today);
    fromInput.value = formatDate(addDays(today, -(DEFAULT_DAYS - 1)));
    // 조건은 [조회] 버튼(또는 아이디 칸에서 Enter)으로만 적용한다
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        page = 0;
        refresh();
    });
    refresh();
}

// ---- 조회 ----

// 기간은 날짜 입력의 양끝 날짜를 포함한다(conventions.md '기간 조회'): from은 그날 00:00, to는 다음 날 00:00
function queryParams() {
    const params = { page: String(page), size: String(PAGE_SIZE) };
    if (fromInput.value) {
        params.from = `${fromInput.value}T00:00:00${KST_OFFSET}`;
    }
    if (toInput.value) {
        params.to = `${formatDate(addDays(parseDate(toInput.value), 1))}T00:00:00${KST_OFFSET}`;
    }
    if (actorTypeSelect.value) {
        params.actorType = actorTypeSelect.value;
    }
    const loginId = loginIdInput.value.trim();
    if (loginId) {
        params.loginId = loginId;
    }
    if (actionSelect.value) {
        params.action = actionSelect.value;
    }
    return params;
}

async function refresh() {
    const seq = ++requestSeq;
    hideError();
    try {
        // 오류 문구는 표 위에 보이므로 api.js의 토스트는 끈다
        const result = await get(HISTORIES_API, queryParams(), { toast: false });
        // 그 사이 조건이 바뀌어 더 새로운 요청이 나갔으면 이 응답은 버린다
        if (seq === requestSeq) {
            render(result);
        }
    } catch (error) {
        if (seq === requestSeq) {
            clearResult();
            showError(error);
        }
    }
}

function clearResult() {
    rowsEl.replaceChildren();
    emptyEl.hidden = true;
    pagesEl.replaceChildren();
    totalEl.textContent = LOAD_FAILED_TEXT;
}

function showError(error) {
    if (error instanceof ApiError) {
        errorEl.textContent = error.fieldErrors.length > 0 ? error.fieldErrors[0].message : error.message;
    } else {
        console.error(error);
        errorEl.textContent = DEFAULT_ERROR_MESSAGE;
    }
    errorEl.hidden = false;
}

function hideError() {
    errorEl.hidden = true;
    errorEl.textContent = '';
}

// ---- 표 ----

function render(result) {
    rowsEl.replaceChildren(...result.items.map(buildRow));
    emptyEl.hidden = result.items.length > 0;
    totalEl.textContent = `총 ${formatNumber(result.totalElements)}건`;
    renderPages(result.page, result.totalPages);
}

// 기록 하나는 기록 줄과 펼침 줄(처음엔 숨김) 한 쌍이다
function buildRow(history) {
    const group = el('div', 'hs-group');
    group.setAttribute('role', 'presentation');

    const row = el('div', 'tr hs-row');
    row.setAttribute('role', 'row');
    const toggle = el('button', 'hs-toggle', CLOSED_MARK);
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', '상세 펼치기');
    row.append(
        cell(document.createTextNode(formatDateTime(history.at))),
        cell(textWithSmall(history.loginId, ACTOR_TYPE_LABEL[history.actorType] ?? history.actorType)),
        cell(document.createTextNode(history.action)),
        cell(document.createTextNode(targetText(history))),
        cell(toggle),
    );

    const detailRow = el('div', 'tr hs-detail');
    detailRow.setAttribute('role', 'row');
    detailRow.hidden = true;
    const detailCell = el('div');
    detailCell.setAttribute('role', 'cell');
    detailCell.append(el('pre', '', detailText(history.detail)));
    detailRow.append(detailCell);

    row.addEventListener('click', () => {
        const opening = detailRow.hidden;
        detailRow.hidden = !opening;
        row.classList.toggle('sel', opening);
        toggle.textContent = opening ? OPENED_MARK : CLOSED_MARK;
        toggle.setAttribute('aria-expanded', String(opening));
    });
    group.append(row, detailRow);
    return group;
}

// '캠 · 은행선화1-캠'처럼 대상 종류 이름 · 대상 이름. 이름이 없으면 종류만, 종류도 없으면 '-'
function targetText(history) {
    const type = history.targetType ? (TARGET_TYPE_LABEL[history.targetType] ?? history.targetType) : null;
    return [type, history.targetName].filter(Boolean).join(' · ') || EMPTY_VALUE;
}

function detailText(detail) {
    return detail === null || detail === undefined ? NO_DETAIL_TEXT : JSON.stringify(detail, null, 2);
}

// ---- 페이지 ----

function renderPages(current, totalPages) {
    if (totalPages <= 1) {
        pagesEl.replaceChildren();
        return;
    }
    const buttons = [pageButton('‹', current - 1, current === 0, false)];
    const first = Math.max(0, current - PAGE_BUTTON_WINDOW);
    const last = Math.min(totalPages - 1, current + PAGE_BUTTON_WINDOW);
    for (let index = first; index <= last; index++) {
        buttons.push(pageButton(String(index + 1), index, false, index === current));
    }
    buttons.push(pageButton('›', current + 1, current >= totalPages - 1, false));
    pagesEl.replaceChildren(...buttons);
}

function pageButton(label, target, disabled, selected) {
    const button = el('button', selected ? 'on' : '', label);
    button.type = 'button';
    button.disabled = disabled;
    if (selected) {
        button.setAttribute('aria-current', 'page');
    }
    button.addEventListener('click', () => {
        page = target;
        refresh();
    });
    return button;
}

// ---- 도우미 ----

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined) {
        node.textContent = text;
    }
    return node;
}

function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

function textWithSmall(main, small) {
    const fragment = document.createDocumentFragment();
    fragment.append(document.createTextNode(main ?? EMPTY_VALUE));
    if (small) {
        fragment.append(el('small', '', small));
    }
    return fragment;
}

function formatDate(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function parseDate(text) {
    const [year, month, day] = text.split('-').map(Number);
    return new Date(year, month - 1, day);
}

function addDays(date, days) {
    const moved = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    moved.setDate(moved.getDate() + days);
    return moved;
}
