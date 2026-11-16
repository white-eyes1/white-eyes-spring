// 작업 기록 화면 SCR-A-012(FNC-A-053). 필터 줄·작업 기록 표·페이지를 GET /api/v1/histories로 채운다.
// 서버 값은 textContent로만 넣는다(code-conventions 5-3).

// 공용 API 모듈(get, 오류 예외)과 날짜·숫자 표시 함수.
import { ApiError, get } from '../../common/api.js';
import { formatDateTime, formatNumber } from '../../common/format.js';

// 작업 기록 API 주소, 한 페이지 행 수, 한국 시간 오프셋(+09:00), 기본 조회 기간(일), 현재 쪽 앞뒤로 보일 번호 개수.
const HISTORIES_API = '/api/v1/histories';
const PAGE_SIZE = 20;
const KST_OFFSET = '+09:00';
const DEFAULT_DAYS = 7;
const PAGE_BUTTON_WINDOW = 2;
const EMPTY_VALUE = '-';
const NO_DETAIL_TEXT = '상세 정보가 없습니다.';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const LOAD_FAILED_TEXT = '목록을 불러오지 못했습니다.';
// 행 펼침 버튼의 접힘/펼침 표시.
const CLOSED_MARK = '▸';
const OPENED_MARK = '▾';

// 행위자·대상 종류 코드를 화면 이름으로 바꾸는 사전.
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

// 필터 줄과 표·페이지 영역의 요소들을 한 번만 찾아 둔다.
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

// page: 현재 쪽(0부터), requestSeq: 요청 번호(겹친 요청 중 마지막 것만 반영하려는 용도).
let page = 0;
let requestSeq = 0;

init();

// 화면 시작 처리: 기본 기간을 채우고, 조회 버튼 제출을 연결하고, 첫 목록을 부른다.
function init() {
    // 기본 조회 기간은 오늘을 포함한 최근 7일(screens.md SCR-A-012)
    const today = new Date();
    toInput.value = formatDate(today);
    fromInput.value = formatDate(addDays(today, -(DEFAULT_DAYS - 1)));
    // 조건은 [조회] 버튼(또는 아이디 칸에서 Enter)으로만 적용한다
    // 폼의 기본 제출(페이지 이동)을 막고 첫 쪽부터 다시 조회한다.
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        page = 0;
        refresh();
    });
    refresh();
}

// ---- 조회 ----

// 기간은 날짜 입력의 양끝 날짜를 포함한다(conventions.md '기간 조회'): from은 그날 00:00, to는 다음 날 00:00
// 입력칸 값으로 조회 조건을 만든다. 날짜 칸은 날짜만 있으므로 from은 그날 00:00, to는 선택한 날의 다음 날 00:00으로 보내 선택한 마지막 날 전체가 포함되게 한다(끝 시각이 포함되지 않는 구간).
function queryParams() {
    // 쪽 번호와 크기는 항상 보낸다.
    const params = { page: String(page), size: String(PAGE_SIZE) };
    if (fromInput.value) {
        params.from = `${fromInput.value}T00:00:00${KST_OFFSET}`;
    }
    if (toInput.value) {
        // 끝 날짜에 하루를 더하는 계산은 날짜 객체로 해서 월말·연말 경계가 자동으로 처리된다.
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

// 현재 조건으로 목록을 조회해 표를 다시 그린다.
async function refresh() {
    // 이번 요청의 번호. 더 새 요청이 나가면 이 응답은 버린다.
    const seq = ++requestSeq;
    hideError();
    try {
        // 오류 문구는 표 위에 보이므로 api.js의 토스트는 끈다
        const result = await get(HISTORIES_API, queryParams(), { toast: false });
        // 그 사이 조건이 바뀌어 더 새로운 요청이 나갔으면 이 응답은 버린다
        if (seq === requestSeq) {
            render(result);
        }
    // 실패하면 기존 결과를 지우고 표 위에 오류 문구를 보인다.
    } catch (error) {
        if (seq === requestSeq) {
            clearResult();
            showError(error);
        }
    }
}

// 오류 시 표·빈 목록 안내·페이지 버튼을 비우고 합계 자리에 실패 문구를 보인다.
function clearResult() {
    rowsEl.replaceChildren();
    emptyEl.hidden = true;
    pagesEl.replaceChildren();
    totalEl.textContent = LOAD_FAILED_TEXT;
}

// 날짜 형식 등 검증 오류는 첫 칸 메시지를, 그 밖의 서버 오류는 서버 메시지를, 서버 오류가 아닌 예외는 콘솔에 남기고 일반 문구를 보인다.
function showError(error) {
    if (error instanceof ApiError) {
        errorEl.textContent =
            error.fieldErrors.length > 0 ? error.fieldErrors[0].message : error.message;
    } else {
        console.error(error);
        errorEl.textContent = DEFAULT_ERROR_MESSAGE;
    }
    errorEl.hidden = false;
}

// 이전 오류 문구를 지운다.
function hideError() {
    errorEl.hidden = true;
    errorEl.textContent = '';
}

// ---- 표 ----

// 조회 결과로 표·빈 목록 안내·총 건수·페이지 버튼을 갱신한다.
function render(result) {
    rowsEl.replaceChildren(...result.items.map(buildRow));
    emptyEl.hidden = result.items.length > 0;
    totalEl.textContent = `총 ${formatNumber(result.totalElements)}건`;
    renderPages(result.page, result.totalPages);
}

// 기록 하나는 기록 줄과 펼침 줄(처음엔 숨김) 한 쌍이다
// 기록 하나를 기록 줄과 상세 줄 한 쌍으로 만든다. 상세 줄은 처음엔 숨겨 두고 기록 줄을 누를 때 펼친다.
function buildRow(history) {
    const group = el('div', 'hs-group');
    group.setAttribute('role', 'presentation');

    const row = el('div', 'tr hs-row');
    row.setAttribute('role', 'row');
    const toggle = el('button', 'hs-toggle', CLOSED_MARK);
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', '상세 펼치기');
    // 열: 일시, 행위자(아이디와 종류), 작업 이름, 대상, 펼침 버튼.
    row.append(
        cell(document.createTextNode(formatDateTime(history.at))),
        cell(
            textWithSmall(
                history.loginId,
                ACTOR_TYPE_LABEL[history.actorType] ?? history.actorType,
            ),
        ),
        cell(document.createTextNode(history.action)),
        cell(document.createTextNode(targetText(history))),
        cell(toggle),
    );

    const detailRow = el('div', 'tr hs-detail');
    detailRow.setAttribute('role', 'row');
    detailRow.hidden = true;
    const detailCell = el('div');
    detailCell.setAttribute('role', 'cell');
    // 상세는 JSON을 들여쓰기한 문자열을 pre 요소에 넣어 그대로 보인다.
    detailCell.append(el('pre', '', detailText(history.detail)));
    detailRow.append(detailCell);

    // 기록 줄을 누를 때마다 상세 줄을 펼치고 접으며, 선택 표시·펼침 표시·접근성 속성(aria-expanded)을 같이 바꾼다.
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
// 대상 칸 문구를 만든다: '종류 · 이름'. 이름이나 종류가 없으면 있는 것만, 둘 다 없으면 '-'.
function targetText(history) {
    const type = history.targetType
        ? (TARGET_TYPE_LABEL[history.targetType] ?? history.targetType)
        : null;
    return [type, history.targetName].filter(Boolean).join(' · ') || EMPTY_VALUE;
}

// 상세 값이 없으면 안내 문구를, 있으면 보기 좋게 들여쓴 JSON 문자열을 돌려준다.
function detailText(detail) {
    return detail === null || detail === undefined
        ? NO_DETAIL_TEXT
        : JSON.stringify(detail, null, 2);
}

// ---- 페이지 ----

// 페이지 버튼을 그린다. 한 쪽뿐이면 버튼을 만들지 않는다. 이전·다음 화살표와 현재 쪽 앞뒤 번호를 보인다.
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

// 페이지 이동 버튼. disabled면 눌러도 이동하지 않고, selected면 현재 쪽 표시(aria-current)를 단다.
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

// 요소 도우미. 글자는 textContent로 넣어 HTML로 해석되지 않게 한다.
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

// 표 칸(div role=cell) 도우미.
function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

// 큰 글자 아래에 작은 보조 글자를 붙인 조각을 만든다.
function textWithSmall(main, small) {
    const fragment = document.createDocumentFragment();
    fragment.append(document.createTextNode(main ?? EMPTY_VALUE));
    if (small) {
        fragment.append(el('small', '', small));
    }
    return fragment;
}

// Date를 입력칸 형식 'yyyy-MM-dd'로 바꾼다(브라우저 현지 날짜 기준).
function formatDate(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// 'yyyy-MM-dd' 문자열을 현지 날짜 객체로 바꾼다. new Date(문자열)은 UTC로 해석돼 날짜가 어긋날 수 있어 연·월·일을 직접 넘긴다.
function parseDate(text) {
    const [year, month, day] = text.split('-').map(Number);
    return new Date(year, month - 1, day);
}

// 날짜에 일수를 더한 새 날짜를 돌려준다. 시·분을 버리고 날짜만 복사한 뒤 더하며 원본은 바꾸지 않는다.
function addDays(date, days) {
    const moved = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    moved.setDate(moved.getDate() + days);
    return moved;
}
