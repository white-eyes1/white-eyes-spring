// 엑셀 다운로드 창(SCR-C-006, FNC-C-023). 통계 메인 통계 패널과 통계 그래프의 '엑셀 다운로드'에서 연다.
// 범위(구역/지역/전체)·기간·단위·항목을 고르면 GET /api/v1/stats/export로 파일을 받는다(작업 기록은 서버가 남김).
// 기간이 단위별 최대를 넘거나 항목이 없으면 다운로드 버튼을 끄고 이유를 보인다. 400이면 창 안에 문구를 보인다.
// 시각은 통계 그래프와 같이 브라우저 시각을 오프셋 없이 보내고, 끝 칸은 to에서 1분 뺀 시각을 보인다.
import { ApiError, download } from '../common/api.js';

const EXPORT_API = '/api/v1/stats/export';
const MINUTE_MS = 60_000;
// 단위별 최대 기간(conventions.md EXPORT_MAX_RANGE, 서버 StatExportRequest와 같은 값)
const INTERVALS = {
    '1m': { label: '1분', max: { days: 7 }, maxLabel: '7일' },
    '1h': { label: '1시간', max: { days: 93 }, maxLabel: '93일' },
    '1d': { label: '1일', max: { years: 10 }, maxLabel: '10년' },
    '1w': { label: '1주', max: { years: 10 }, maxLabel: '10년' },
    '1M': { label: '1월', max: { years: 10 }, maxLabel: '10년' },
    '1y': { label: '1년', max: { years: 10 }, maxLabel: '10년' },
};
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';

const byId = (id) => document.getElementById(id);

let modal = null;
let scopes = []; // 범위 선택지: { scope, targetId, label }

/** 창을 한 번 준비한다. 창 마크업(layout.html statExportModal)이 없는 화면에서는 아무것도 하지 않는다. */
export function initExportModal() {
    modal = byId('export-modal');
    if (!modal) {
        return;
    }
    modal.querySelectorAll('[data-export-close]').forEach((button) => button.addEventListener('click', closeExportModal));
    modal.addEventListener('mousedown', (event) => {
        if (event.target === modal) {
            closeExportModal();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !modal.hidden) {
            closeExportModal();
        }
    });
    byId('export-scope').addEventListener('change', () => {
        renderExpand();
        validate();
    });
    ['export-from', 'export-end', 'export-interval'].forEach((id) => byId(id).addEventListener('change', validate));
    itemBoxes().forEach((box) => box.addEventListener('change', validate));
    modal.querySelector('form').addEventListener('submit', (event) => {
        event.preventDefault();
        submit();
    });
}

/**
 * 현재 대상·단위·기간으로 창을 연다(screens.md SCR-C-003 '현재 대상 전달', SCR-C-004 '현재 대상·단위·기간 전달').
 * @param {{ zone?: {id, name, deleted?}, region?: {id, name}, interval: string, from: Date, to: Date }} target
 *     zone이 있으면 구역이 기본 범위, 없으면 region. 전체는 언제나 고를 수 있다. 삭제된 구역은 이름에 '(삭제됨)'(FNC-A-017)
 */
export function openExportModal({ zone = null, region = null, interval, from, to }) {
    if (!modal) {
        return;
    }
    scopes = [];
    if (zone) {
        const name = zone.deleted ? `${zone.name} (삭제됨)` : zone.name;
        scopes.push({ scope: 'ZONE', targetId: zone.id, label: `구역 · ${name}` });
    }
    if (region) {
        scopes.push({ scope: 'REGION', targetId: region.id, label: `지역 · ${region.name}` });
    }
    scopes.push({ scope: 'ALL', targetId: null, label: '전체' });
    const scopeSelect = byId('export-scope');
    scopeSelect.replaceChildren(...scopes.map((entry, index) => {
        const option = document.createElement('option');
        option.value = String(index);
        option.textContent = entry.label;
        return option;
    }));
    scopeSelect.value = '0';
    // 지역은 기본 합산(FNC-C-023 업무 규칙)
    byId('export-expand').checked = false;
    byId('export-interval').value = INTERVALS[interval] ? interval : '1h';
    byId('export-from').value = toInputValue(from);
    byId('export-end').value = toInputValue(new Date(to.getTime() - MINUTE_MS));
    hideError();
    renderExpand();
    validate();
    modal.hidden = false;
    scopeSelect.focus();
}

export function closeExportModal() {
    if (modal) {
        modal.hidden = true;
    }
}

function itemBoxes() {
    return [...modal.querySelectorAll('#export-items input[type="checkbox"]')];
}

function selectedScope() {
    return scopes[Number(byId('export-scope').value)];
}

// '구역별 펼치기'는 지역일 때만
function renderExpand() {
    byId('export-expand-row').hidden = selectedScope()?.scope !== 'REGION';
}

/** 지금 입력으로 받을 조건. 틀리면 { reason } */
function readConditions() {
    const from = parseLocal(byId('export-from').value);
    const end = parseLocal(byId('export-end').value);
    if (!from || !end) {
        return { reason: '시작·끝 시각을 입력해 주세요.' };
    }
    const to = new Date(end.getTime() + MINUTE_MS);
    if (to <= from) {
        return { reason: '끝 시각은 시작 시각보다 뒤여야 합니다.' };
    }
    const interval = byId('export-interval').value;
    const spec = INTERVALS[interval];
    if (to > shift(from, spec.max)) {
        return { reason: `${spec.label} 단위는 최대 ${spec.maxLabel}까지 내려받을 수 있습니다.` };
    }
    const items = itemBoxes().filter((box) => box.checked).map((box) => box.value);
    if (items.length === 0) {
        return { reason: '항목을 하나 이상 골라 주세요.' };
    }
    return { from, to, interval, items };
}

// 초과·항목 없음이면 다운로드 버튼 비활성 + 이유 문구(screens.md SCR-C-006)
function validate() {
    const { reason } = readConditions();
    const reasonText = byId('export-reason');
    reasonText.textContent = reason ?? '';
    reasonText.hidden = !reason;
    byId('export-submit').disabled = Boolean(reason);
    return !reason;
}

async function submit() {
    const conditions = readConditions();
    if (conditions.reason) {
        validate();
        return;
    }
    const { scope, targetId } = selectedScope();
    const params = [
        ['scope', scope],
        ['interval', conditions.interval],
        ['from', toInputValue(conditions.from)],
        ['to', toInputValue(conditions.to)],
        ...conditions.items.map((item) => ['items', item]),
    ];
    if (targetId !== null) {
        params.push(['targetId', String(targetId)]);
    }
    if (scope === 'REGION' && byId('export-expand').checked) {
        params.push(['expand', 'true']);
    }

    const button = byId('export-submit');
    button.disabled = true;
    hideError();
    try {
        // 오류는 창 안 문구로 보이므로 api.js의 토스트는 끈다
        const { blob, fileName } = await download(EXPORT_API, params, { toast: false });
        save(blob, fileName ?? 'stats.xlsx');
        closeExportModal();
    } catch (error) {
        showError(errorText(error));
    } finally {
        button.disabled = false;
    }
}

function save(blob, fileName) {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    // 내려받기가 시작될 시간을 두고 지운다
    setTimeout(() => URL.revokeObjectURL(link.href), MINUTE_MS);
}

function errorText(error) {
    if (!(error instanceof ApiError)) {
        console.error(error);
        return DEFAULT_ERROR_MESSAGE;
    }
    if (error.fieldErrors.length > 0) {
        return `${error.message} (${error.fieldErrors.map((fieldError) => fieldError.message).join(' / ')})`;
    }
    return error.message;
}

function showError(message) {
    const notice = byId('export-error');
    notice.textContent = message;
    notice.hidden = false;
}

function hideError() {
    const notice = byId('export-error');
    notice.hidden = true;
    notice.textContent = '';
}

// ---- 시각 도우미(통계 그래프 stats-graph.js와 같은 규칙) --------------------------------------------

function shift(date, { years = 0, months = 0, days = 0 }) {
    const result = new Date(date.getTime());
    result.setFullYear(result.getFullYear() + years, result.getMonth() + months, result.getDate() + days);
    return result;
}

const pad = (value) => String(value).padStart(2, '0');

// datetime-local 칸과 요청에 쓰는 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

// datetime-local 값 'yyyy-MM-ddTHH:mm'을 브라우저 시각으로 읽는다. 틀리면 null
function parseLocal(text) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(text ?? '');
    if (!match) {
        return null;
    }
    const [, year, month, day, hour, minute] = match;
    return new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
}
