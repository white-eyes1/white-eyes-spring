// 기간 비교 분석 진입 파일 SCR-C-005(FNC-C-020): 대상·대상 기간·기준 기간·비정상 기준을 고르면 GET /api/v1/compare로
// 요일×시간 표와 전체 증감률을 그린다. 조건은 주소 쿼리로 유지하고, 대시보드에서 올 때도 쿼리로 자동 입력된다.
// 기간은 날짜(양끝 포함)로 주고받는다. 기본은 어제까지 최근 7일, 지난달 같은 요일, 기준 30%.
import { ApiError, get } from '../common/api.js';
import { formatDecimal, formatSigned } from '../common/format.js';
import { createTargetSelect } from './stats-compare/target.js';

const COMPARE_API = '/api/v1/compare';
const PRESETS = ['LAST_MONTH', 'LAST_YEAR'];
const CUSTOM = 'CUSTOM';
const DEFAULT_PRESET = 'LAST_MONTH';
const DEFAULT_DAYS = 7;
// 직접 지정으로 바꿀 때 처음 채우는 기준 기간: 지난달 프리셋과 같은 28일 전
const CUSTOM_SHIFT_DAYS = 28;
// 화면의 입력 범위(screens.md SCR-C-005, 서버 CompareRequest와 같은 값)
const DEFAULT_THRESHOLD = 30;
const MIN_THRESHOLD = 5;
const MAX_THRESHOLD = 100;
const WEEKDAYS = ['월', '화', '수', '목', '금', '토', '일'];
const HOURS = 24;
// 칸 배경은 |증감률| 50%에서 가장 진하고(시안과 같은 비율: +33% → 0.40) 그 위로는 더 진해지지 않는다
const FULL_RATE = 50;
const MAX_ALPHA = 0.6;
const FIELD_LABELS = {
    targetType: '대상',
    targetId: '대상',
    from: '대상 기간 시작',
    to: '대상 기간 끝',
    baseFrom: '기준 기간 시작',
    baseTo: '기준 기간 끝',
    basePreset: '기준 기간',
    thresholdPct: '비정상 기준',
};
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';

const byId = (id) => document.getElementById(id);
const fromInput = byId('target-from');
const toInput = byId('target-to');
const presetSelect = byId('base-preset');
const baseDates = byId('base-dates');
const baseFromInput = byId('base-from');
const baseToInput = byId('base-to');
const thresholdInput = byId('threshold');
const thresholdHint = byId('threshold-hint');
const errorNotice = byId('compare-error');
const grid = byId('compare-grid');
const totalRate = byId('total-rate');
const totalNote = byId('total-note');
const target = createTargetSelect({
    regionSelects: ['target-sido', 'target-sigungu', 'target-emd'].map(byId),
    zoneSelect: byId('target-zone'),
    onChange: load,
});

let threshold = DEFAULT_THRESHOLD;
// 조건을 빠르게 여러 번 바꾸면 마지막 요청의 결과만 그린다
let latestRequestId = 0;

start();

async function start() {
    const params = new URLSearchParams(window.location.search);
    const from = parseDate(params.get('from'));
    const to = parseDate(params.get('to'));
    if (from && to) {
        fromInput.value = from;
        toInput.value = to;
    } else {
        const yesterday = addDays(today(), -1);
        fromInput.value = addDays(yesterday, -(DEFAULT_DAYS - 1));
        toInput.value = yesterday;
    }
    const baseFrom = parseDate(params.get('baseFrom'));
    const baseTo = parseDate(params.get('baseTo'));
    if (PRESETS.includes(params.get('basePreset'))) {
        presetSelect.value = params.get('basePreset');
    } else if (baseFrom && baseTo) {
        presetSelect.value = CUSTOM;
        baseFromInput.value = baseFrom;
        baseToInput.value = baseTo;
    } else {
        presetSelect.value = DEFAULT_PRESET;
    }
    threshold = parseThreshold(params.get('thresholdPct')) ?? DEFAULT_THRESHOLD;
    thresholdInput.value = String(threshold);
    renderBaseDates();

    await target.select(params.get('targetType'), Number(params.get('targetId')));
    [fromInput, toInput, baseFromInput, baseToInput].forEach((input) => input.addEventListener('change', load));
    presetSelect.addEventListener('change', onPresetChange);
    thresholdInput.addEventListener('change', onThresholdChange);
    await load();
}

// ---- 조건 ---------------------------------------------------------------------------------------

function onPresetChange() {
    const filled = baseFromInput.value && baseToInput.value;
    if (presetSelect.value === CUSTOM && !filled && fromInput.value && toInput.value) {
        baseFromInput.value = addDays(fromInput.value, -CUSTOM_SHIFT_DAYS);
        baseToInput.value = addDays(toInput.value, -CUSTOM_SHIFT_DAYS);
    }
    renderBaseDates();
    load();
}

function renderBaseDates() {
    baseDates.hidden = presetSelect.value !== CUSTOM;
}

// 범위 밖이면 입력을 받지 않고(다시 조회하지 않음) 안내를 보인다
function onThresholdChange() {
    const value = parseThreshold(thresholdInput.value);
    thresholdInput.classList.toggle('err', value === null);
    thresholdHint.hidden = value !== null;
    if (value === null) {
        return;
    }
    threshold = value;
    load();
}

function parseThreshold(text) {
    if (text === null || !/^\d+$/.test(text.trim())) {
        return null;
    }
    const value = Number(text);
    return value >= MIN_THRESHOLD && value <= MAX_THRESHOLD ? value : null;
}

function currentParams() {
    const params = { ...target.current(), from: fromInput.value, to: toInput.value, thresholdPct: String(threshold) };
    if (presetSelect.value === CUSTOM) {
        params.baseFrom = baseFromInput.value;
        params.baseTo = baseToInput.value;
    } else {
        params.basePreset = presetSelect.value;
    }
    return params;
}

// ---- 조회·그리기 -------------------------------------------------------------------------------

async function load() {
    if (!target.current()) {
        return;
    }
    const params = currentParams();
    const url = new URL(window.location.href);
    url.search = new URLSearchParams(params).toString();
    window.history.replaceState(null, '', url);

    const requestId = ++latestRequestId;
    try {
        const data = await get(COMPARE_API, params, { toast: false });
        if (requestId !== latestRequestId) {
            return;
        }
        errorNotice.hidden = true;
        render(data);
    } catch (error) {
        if (requestId !== latestRequestId) {
            return;
        }
        // 기간 오류 문구(INVALID_RANGE)와 칸별 입력 오류를 조건 아래에 보이고 결과는 비운다
        showError(error);
        render(null);
    }
}

function showError(error) {
    let message = DEFAULT_ERROR_MESSAGE;
    if (error instanceof ApiError) {
        message =
            error.fieldErrors.length > 0
                ? error.fieldErrors.map((field) => `${FIELD_LABELS[field.field] ?? field.field}: ${field.message}`).join(' ')
                : error.message;
    }
    errorNotice.textContent = message;
    errorNotice.hidden = false;
}

function render(data) {
    renderTotal(data);
    renderGrid(data?.cells ?? []);
}

function renderTotal(data) {
    totalRate.classList.remove('up', 'down');
    if (!data || data.totalRate === null) {
        totalRate.textContent = '-';
        totalNote.textContent = data ? '비교할 기준 데이터 없음' : '';
        return;
    }
    const rate = Number(data.totalRate);
    const abnormalCount = data.cells.filter((cell) => cell.abnormal).length;
    totalRate.textContent = `${formatSigned(rate)}%`;
    if (rate !== 0) {
        totalRate.classList.add(rate > 0 ? 'up' : 'down');
    }
    const direction = rate > 0 ? '증가' : rate < 0 ? '감소' : '변화 없음';
    totalNote.textContent = `${direction} · 비정상 칸 ${abnormalCount}개`;
}

function renderGrid(cells) {
    const byKey = new Map(cells.map((cell) => [`${cell.weekday}-${cell.hour}`, cell]));
    const nodes = [header('')];
    for (let hour = 0; hour < HOURS; hour++) {
        nodes.push(header(String(hour)));
    }
    WEEKDAYS.forEach((label, index) => {
        nodes.push(header(label, 'day'));
        for (let hour = 0; hour < HOURS; hour++) {
            nodes.push(cellNode(byKey.get(`${index + 1}-${hour}`)));
        }
    });
    grid.replaceChildren(...nodes);
}

function header(text, extraClass) {
    const span = document.createElement('span');
    span.className = extraClass ? `hd ${extraClass}` : 'hd';
    span.textContent = text;
    return span;
}

function cellNode(cell) {
    const span = document.createElement('span');
    span.setAttribute('role', 'cell');
    if (!cell) {
        return span;
    }
    span.title = `대상 평균 ${formatAvg(cell.targetAvg)} · 기준 평균 ${formatAvg(cell.baseAvg)}`;
    // 기준 평균이 0이거나 어느 한쪽이 없는 칸은 비교 불가(회색)
    if (cell.rate === null) {
        span.className = 'na';
        span.textContent = '-';
        return span;
    }
    const rate = Number(cell.rate);
    span.textContent = formatSigned(rate, 0);
    if (rate !== 0) {
        const alpha = Math.min(Math.abs(rate) / FULL_RATE, 1) * MAX_ALPHA;
        span.style.background = rate > 0 ? `rgba(239, 77, 85, ${alpha})` : `rgba(79, 140, 255, ${alpha})`;
    }
    if (cell.abnormal) {
        span.classList.add('abnormal', rate > 0 ? 'up' : 'down');
    }
    return span;
}

function formatAvg(value) {
    return value === null ? '없음' : `${formatDecimal(value)}명`;
}

// ---- 날짜(yyyy-MM-dd, 브라우저 날짜) --------------------------------------------------------------

function parseDate(text) {
    return text && /^\d{4}-\d{2}-\d{2}$/.test(text) && !Number.isNaN(new Date(`${text}T00:00`).getTime()) ? text : null;
}

function today() {
    return toDateText(new Date());
}

function addDays(text, days) {
    const date = new Date(`${text}T00:00`);
    date.setDate(date.getDate() + days);
    return toDateText(date);
}

function toDateText(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
