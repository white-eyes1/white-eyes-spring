// 엑셀 다운로드 창(SCR-C-006, FNC-C-023). 통계 메인 통계 패널과 통계 그래프의 '엑셀 다운로드'에서 연다.
// 범위(구역/지역/전체)·기간·단위·항목을 고르면 GET /api/v1/stats/export로 파일을 받는다(작업 기록은 서버가 남김).
// 기간이 단위별 최대를 넘거나 항목이 없으면 다운로드 버튼을 끄고 이유를 보인다. 400이면 창 안에 문구를 보인다.
// 시각은 통계 그래프와 같이 브라우저 시각을 오프셋 없이 보내고, 끝 칸은 to에서 1분 뺀 시각을 보인다.
import { ApiError, download } from '../common/api.js';

// 파일을 받는 서버 API 주소
const EXPORT_API = '/api/v1/stats/export';
// 1분(밀리초). 끝 시각 계산·주소 해제 지연에 쓴다
const MINUTE_MS = 60_000;
// 단위별 최대 기간(conventions.md EXPORT_MAX_RANGE, 서버 StatExportRequest와 같은 값)
// 시간 단위 코드(서버가 받는 interval 값) -> 화면 이름, 최대 기간(max: shift에 넘길 값), 안내 문구
const INTERVALS = {
    '1m': { label: '1분', max: { days: 7 }, maxLabel: '7일' },
    '1h': { label: '1시간', max: { days: 93 }, maxLabel: '93일' },
    '1d': { label: '1일', max: { years: 10 }, maxLabel: '10년' },
    '1w': { label: '1주', max: { years: 10 }, maxLabel: '10년' },
    '1M': { label: '1월', max: { years: 10 }, maxLabel: '10년' },
    '1y': { label: '1년', max: { years: 10 }, maxLabel: '10년' },
};
// api.js의 기본 문구와 같다. ApiError가 아닌 예외에 쓴다
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 창 요소. initExportModal에서 채워지며, 창 마크업이 없는 화면에서는 null로 남는다
let modal = null;
let scopes = []; // 범위 선택지: { scope, targetId, label }

/** 창을 한 번 준비한다. 창 마크업(layout.html statExportModal)이 없는 화면에서는 아무것도 하지 않는다. */
export function initExportModal() {
    // 창 마크업이 없는 화면은 아무것도 하지 않는다
    modal = byId('export-modal');
    if (!modal) {
        return;
    }
    // 창 닫기: 닫기 버튼, 창 바깥 클릭, Esc
    modal
        .querySelectorAll('[data-export-close]')
        .forEach((button) => button.addEventListener('click', closeExportModal));
    // 내용 영역이 아니라 배경을 눌렀을 때만 닫는다
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
    // 범위를 바꾸면 '지역 합산' 옵션 표시를 갱신하고 입력을 다시 검사한다
    byId('export-scope').addEventListener('change', () => {
        renderSum();
        validate();
    });
    // 시작·끝·단위가 바뀔 때마다 기간 제한을 다시 검사한다
    ['export-from', 'export-end', 'export-interval'].forEach((id) =>
        byId(id).addEventListener('change', validate),
    );
    // 항목(체크박스)이 바뀔 때도 검사한다(하나도 안 고르면 다운로드 불가)
    itemBoxes().forEach((box) => box.addEventListener('change', validate));
    // 폼 전송을 막고 fetch로 파일을 받는다
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
    // 이 화면에 창이 없으면 아무것도 하지 않는다
    if (!modal) {
        return;
    }
    // 범위 선택지를 다시 만든다. 구역 > 지역 > 전체 순이며, 맨 앞이 기본값이다
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
    // 선택지 값은 scopes 배열의 순번이다(구역·지역 id는 selectedScope()에서 찾는다)
    scopeSelect.replaceChildren(
        ...scopes.map((entry, index) => {
            const option = document.createElement('option');
            option.value = String(index);
            option.textContent = entry.label;
            return option;
        }),
    );
    // 첫 번째(가장 좁은 범위)를 기본으로 고른다
    scopeSelect.value = '0';
    // 지역은 기본 소속 구역별 행, 합산은 직접 고를 때만(FNC-C-023 업무 규칙)
    byId('export-sum').checked = false;
    // 알 수 없는 단위가 오면 1시간으로
    byId('export-interval').value = INTERVALS[interval] ? interval : '1h';
    // 시작은 그대로, 끝 칸은 마지막 분을 포함해 보이도록 to에서 1분 뺀 값을 넣는다
    byId('export-from').value = toInputValue(from);
    byId('export-end').value = toInputValue(new Date(to.getTime() - MINUTE_MS));
    // 이전에 보이던 오류를 지우고 현재 입력을 검사해 버튼 상태를 맞춘다
    hideError();
    renderSum();
    validate();
    // 모든 값을 채운 뒤에 창을 보인다
    modal.hidden = false;
    // 키보드 사용자가 바로 범위를 바꿀 수 있게 포커스를 준다
    scopeSelect.focus();
}

/** 창을 닫는다(창이 없으면 아무것도 하지 않는다). */
export function closeExportModal() {
    if (modal) {
        modal.hidden = true;
    }
}

// 항목 체크박스들을 배열로 돌려준다
function itemBoxes() {
    return [...modal.querySelectorAll('#export-items input[type="checkbox"]')];
}

// 현재 고른 범위 선택지(scopes의 항목)
function selectedScope() {
    return scopes[Number(byId('export-scope').value)];
}

// '지역 합산으로 받기'는 지역일 때만
function renderSum() {
    byId('export-sum-row').hidden = selectedScope()?.scope !== 'REGION';
}

/** 지금 입력으로 받을 조건. 틀리면 { reason } */
function readConditions() {
    // 입력칸의 글자를 시각으로 바꾼다. 비었거나 형식이 틀리면 null
    const from = parseLocal(byId('export-from').value);
    const end = parseLocal(byId('export-end').value);
    if (!from || !end) {
        return { reason: '시작·끝 시각을 입력해 주세요.' };
    }
    // 끝 칸은 '마지막 분'이므로 서버에는 1분을 더한 값(끝을 포함하지 않는 구간의 끝)을 보낸다
    const to = new Date(end.getTime() + MINUTE_MS);
    // 끝이 시작보다 뒤여야 한다
    if (to <= from) {
        return { reason: '끝 시각은 시작 시각보다 뒤여야 합니다.' };
    }
    const interval = byId('export-interval').value;
    // 단위마다 받을 수 있는 최대 기간이 다르다(서버 검증과 같은 값)
    const spec = INTERVALS[interval];
    // from에 최대 기간을 더한 시각을 넘으면 거절
    if (to > shift(from, spec.max)) {
        return { reason: `${spec.label} 단위는 최대 ${spec.maxLabel}까지 내려받을 수 있습니다.` };
    }
    // 체크된 항목의 값(서버가 받는 항목 코드)을 모은다
    const items = itemBoxes()
        .filter((box) => box.checked)
        .map((box) => box.value);
    if (items.length === 0) {
        return { reason: '항목을 하나 이상 골라 주세요.' };
    }
    // 모두 통과하면 서버에 보낼 조건을 돌려준다(reason이 없음)
    return { from, to, interval, items };
}

// 초과·항목 없음이면 다운로드 버튼 비활성 + 이유 문구(screens.md SCR-C-006)
function validate() {
    // 조건이 틀리면 reason, 맞으면 undefined
    const { reason } = readConditions();
    const reasonText = byId('export-reason');
    reasonText.textContent = reason ?? '';
    reasonText.hidden = !reason;
    // 이유가 있으면 다운로드 버튼을 끈다
    byId('export-submit').disabled = Boolean(reason);
    return !reason;
}

async function submit() {
    // 버튼이 꺼져 있어도 Enter로 전송될 수 있어 한 번 더 검사한다
    const conditions = readConditions();
    if (conditions.reason) {
        validate();
        return;
    }
    // 선택한 범위 선택지에서 scope와 대상 id를 꺼낸다
    const { scope, targetId } = selectedScope();
    // 같은 이름(items)을 여러 번 보내야 해서 [이름, 값] 쌍 배열로 만든다(api.js download의 params 규칙)
    const params = [
        ['scope', scope],
        ['interval', conditions.interval],
        ['from', toInputValue(conditions.from)],
        ['to', toInputValue(conditions.to)],
        ...conditions.items.map((item) => ['items', item]),
    ];
    // 전체 범위는 대상 id가 없다
    if (targetId !== null) {
        params.push(['targetId', String(targetId)]);
    }
    // 합산 옵션은 지역 범위에서 체크했을 때만 보낸다
    if (scope === 'REGION' && byId('export-sum').checked) {
        params.push(['sum', 'true']);
    }

    const button = byId('export-submit');
    // 파일 만드는 중 두 번 눌리는 것을 막는다
    button.disabled = true;
    hideError();
    try {
        // 오류는 창 안 문구로 보이므로 api.js의 토스트는 끈다
        const { blob, fileName } = await download(EXPORT_API, params, { toast: false });
        // 서버가 파일 이름을 주지 않으면 stats.xlsx로 저장한다
        save(blob, fileName ?? 'stats.xlsx');
        closeExportModal();
    } catch (error) {
        // 오류 문구를 창 안에 보인다(창은 닫지 않는다)
        showError(errorText(error));
    } finally {
        button.disabled = false;
    }
}

function save(blob, fileName) {
    // 받은 blob을 보이지 않는 링크로 눌러 브라우저 저장을 시작한다
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = fileName;
    // 일부 브라우저는 DOM에 붙어 있어야 click이 동작한다
    document.body.append(link);
    link.click();
    link.remove();
    // 내려받기가 시작될 시간을 두고 지운다
    // 바로 해제하면 저장이 중단될 수 있어 1분 뒤에 임시 주소를 해제한다(메모리 정리)
    setTimeout(() => URL.revokeObjectURL(link.href), MINUTE_MS);
}

function errorText(error) {
    // ApiError가 아닌 예외는 콘솔에 남기고 기본 문구만 보인다
    if (!(error instanceof ApiError)) {
        console.error(error);
        return DEFAULT_ERROR_MESSAGE;
    }
    // 필드 검증 오류가 있으면 서버 문구에 필드별 이유를 이어 붙인다
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

// ---- 시각 도우미(서버 Period와 같은 규칙, 통계 그래프 stats-graph.js의 shift와 같다) --------------------------------------------

// 서버 Period/plus와 같게: 연·월을 더해 그 달에 없는 날이면 말일로 줄이고(2024-02-29 + 10년 = 2034-02-28), 일은 그 뒤에 더한다
function shift(date, { years = 0, months = 0, days = 0 }) {
    // 기준일의 '일'을 기억해 두었다가 연·월을 더한 뒤 말일을 넘지 않게 맞춘다
    const result = new Date(date.getTime());
    const day = result.getDate();
    // 1일로 옮겨 두고 연·월을 더한다(31일 상태에서 월을 더하면 다음 달로 넘어가는 문제를 피한다)
    result.setDate(1);
    result.setFullYear(result.getFullYear() + years, result.getMonth() + months, 1);
    // 더한 달의 말일(다음 달 0일 = 이번 달 마지막 날)
    const lastDay = new Date(result.getFullYear(), result.getMonth() + 1, 0).getDate();
    // 원래 일과 말일 중 작은 값에 일 수를 더한다
    result.setDate(Math.min(day, lastDay) + days);
    return result;
}

// 한 자리 숫자를 두 자리로 맞춘다
const pad = (value) => String(value).padStart(2, '0');

// datetime-local 칸과 요청에 쓰는 'yyyy-MM-ddTHH:mm'(브라우저 시각)
function toInputValue(date) {
    // datetime-local 칸 형식으로 만든다
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}

// datetime-local 값 'yyyy-MM-ddTHH:mm'을 브라우저 시각으로 읽는다. 틀리면 null
function parseLocal(text) {
    // 'yyyy-MM-ddTHH:mm' 형식만 받는다
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(text ?? '');
    if (!match) {
        return null;
    }
    // 월은 0부터 시작하므로 1을 뺀다
    const [, year, month, day, hour, minute] = match;
    return new Date(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
}
