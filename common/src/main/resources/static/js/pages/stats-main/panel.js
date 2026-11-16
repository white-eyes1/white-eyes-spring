// 통계 메인 왼쪽 통계 패널(SCR-C-003, FNC-C-009·016·017·025): 구역별 현재 인원 도넛·목록, 요약 6칸, 유입·유출, 혼잡 기준.
// 서버 값은 textContent로만 넣는다(code-conventions 5-3). 5분 안에 통계가 없으면 '데이터 없음'(api/public.md).
import { formatDateTime, formatDecimal, formatNumber } from '../../common/format.js';
import { nowTotal } from '../../common/now-count.js';
import { levelColor } from '../../map/map-view.js';

// 데이터가 없을 때의 문구
const NO_DATA = '데이터 없음';
// 도넛·목록에 개별로 보이는 구역 수(나머지는 '기타'로 합침)
const TOP_ZONES = 5;
// '기타'·'통계 없음'의 회색
const OTHER_COLOR = '#4a5768';
// 방향 코드 -> 화면 이름
const DIRECTIONS = { N: '북(N)', E: '동(E)', S: '남(S)', W: '서(W)' };

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

/** 'yyyy-MM-dd HH:mm' 중 시각만 */
// 통계 시각에서 시각(HH:mm)만 잘라 낸다
function timeOf(statAt) {
    return statAt ? formatDateTime(statAt).slice(11) : '';
}

/** '그래프 보기'(FNC-C-012)에 현재 지역을 넘긴다. 단위·기간은 그래프 화면의 기본값(1h, 오늘)을 쓴다 */
export function renderGraphLink(regionId) {
    // 링크의 기존 주소(템플릿이 준 /stats/graph)에 대상 쿼리를 붙인다
    const link = byId('graph-link');
    const url = new URL(link.href);
    url.search = new URLSearchParams({
        targetType: 'REGION',
        targetId: String(regionId),
    }).toString();
    link.href = url.toString();
}

/** 패널 머리의 '은행선화동 · 캠 3대' */
export function renderTarget(name, cameraCount) {
    byId('stat-target').textContent = cameraCount === null ? name : `${name} · 캠 ${cameraCount}대`;
}

/**
 * 구역별 현재 인원. tab: count(현재 인원) | event(오늘 이벤트) | level(단계별 구역 수).
 * @param {object[]} items GET /map/status?unit=ZONE 의 items
 * @param {object[]} levels GET /meta/levels
 */
export function renderZonePanel(items, tab, levels) {
    // 탭별로 도넛에 그릴 항목 목록과 합계를 만든다
    const entries = zoneEntries(items, tab, levels);
    const total = entries.reduce((sum, entry) => sum + entry.value, 0);
    // 가장 최근 통계 시각(문자열 정렬이 시각순과 같은 ISO 형식)
    const latest = items
        .map((item) => item.statAt)
        .filter(Boolean)
        .sort()
        .pop();
    byId('zone-time').textContent = latest ? `${timeOf(latest)} 기준` : '';

    const donut = byId('zone-donut');
    const list = byId('zone-list');
    // 구역이 없거나 값이 모두 0이면 비운 상태로 보인다(단계별 탭은 개수라서 예외)
    if (
        items.length === 0 ||
        (tab !== 'level' && total === 0 && !items.some((item) => item.level))
    ) {
        donut.style.background = '';
        byId('zone-total').textContent = '-';
        list.replaceChildren(emptyText());
        return;
    }
    // 값의 비율로 도넛 색을 만든다
    donut.style.background = conic(entries, total);
    byId('zone-total').textContent =
        // 탭에 맞는 단위를 붙인다(명·건·곳)
        formatNumber(total) + (tab === 'count' ? '명' : tab === 'event' ? '건' : '곳');
    // 목록 막대는 가장 큰 값을 100%로 맞춘다(0일 때 나누기 방지로 최소 1)
    const max = Math.max(...entries.map((entry) => entry.value), 1);
    list.replaceChildren(...entries.map((entry) => listRow(entry, max)));
}

// 탭에 따라 도넛·목록의 항목을 만든다
function zoneEntries(items, tab, levels) {
    // 단계별 탭: 단계마다 구역 개수를 센다. 통계가 없는 구역은 '데이터 없음'으로 따로 센다
    if (tab === 'level') {
        const rows = levels.map(({ level, name }) => ({
            label: name,
            value: items.filter((item) => item.level === level).length,
            color: levelColor(level),
        }));
        rows.push({
            label: NO_DATA,
            value: items.filter((item) => !item.level).length,
            color: OTHER_COLOR,
        });
        return rows.filter((row) => row.value > 0);
    }
    // 현재 인원 탭은 평균 인원(반올림), 이벤트 탭은 오늘 이벤트 수
    const valueOf = (item) =>
        tab === 'event'
            ? item.todayEventCount
            : item.countAvg === null
              ? 0
              : Math.round(Number(item.countAvg));
    // 값이 큰 순서로 정렬해 상위 5개만 두고, 나머지는 '기타' 하나로 합친다
    const sorted = items
        .map((item) => ({ label: item.name, value: valueOf(item), color: levelColor(item.level) }))
        .sort((a, b) => b.value - a.value);
    const top = sorted.slice(0, TOP_ZONES);
    const rest = sorted.slice(TOP_ZONES).reduce((sum, row) => sum + row.value, 0);
    if (sorted.length > TOP_ZONES) {
        top.push({ label: '기타', value: rest, color: OTHER_COLOR });
    }
    return top;
}

// 도넛을 칠하는 conic-gradient 문자열을 만든다. 각 항목이 차지할 구간(%)을 누적한다
function conic(entries, total) {
    // 합계가 0이면 그릴 것이 없다
    if (total === 0) {
        return '';
    }
    let from = 0;
    const stops = entries
        .filter((entry) => entry.value > 0)
        .map((entry) => {
            const to = from + (entry.value / total) * 100;
            const stop = `${entry.color} ${from}% ${to}%`;
            from = to;
            return stop;
        });
    return `conic-gradient(${stops.join(', ')})`;
}

// 목록 한 줄: 색 점+이름, 값, 값 비율 막대
function listRow({ label, value, color }, max) {
    const row = document.createElement('div');
    const head = document.createElement('div');
    head.className = 'row-between';
    const name = document.createElement('span');
    const dot = document.createElement('i');
    dot.className = 'dot';
    dot.style.background = color;
    name.append(dot, label);
    const amount = document.createElement('b');
    amount.textContent = formatNumber(value);
    head.append(name, amount);
    const bar = document.createElement('div');
    bar.className = 'bar';
    const fill = document.createElement('i');
    fill.style.width = `${Math.round((value / max) * 100)}%`;
    fill.style.background = color;
    bar.append(fill);
    row.append(head, bar);
    return row;
}

// 데이터 없음 한 줄
function emptyText() {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = NO_DATA;
    return empty;
}

/** 혼잡 단계 칩(색은 we.css .chip.l1~l3). 단계가 없으면 '데이터 없음' */
// 단계 번호로 이름을 찾아 칩을 만든다. 모르는 단계는 회색 칩
export function levelChip(level, levels) {
    const chip = document.createElement('span');
    const found = levels.find((candidate) => candidate.level === level);
    chip.className = 'chip ' + (found ? 'l' + level : 'off');
    chip.textContent = found ? found.name : NO_DATA;
    return chip;
}

/**
 * 요약의 '현재 인원'만 바꾼다(5초마다, 통계 SSE event: now). 이벤트로 받은 합이 있으면 그것을, 없으면 요약 응답의
 * currentCount(5초 인원), 그것도 없으면 1분 평균을 보인다. 1분 통계가 없어도 currentCount가 있으면 보인다(FNC-C-016).
 * @param {object|null} summary GET /dashboard/summary 응답
 * @param {number|null} liveTotal event: now 로 받은 구독 지역 구역 합. 없으면 null
 */
// 현재 인원 우선순위: 5초 값(liveTotal) > 요약의 currentCount > 1분 평균
export function renderSummaryCount(summary, liveTotal) {
    const count =
        liveTotal ??
        summary?.currentCount ??
        (summary?.countAvg === null || summary?.countAvg === undefined
            ? null
            : Math.round(Number(summary.countAvg)));
    byId('sum-count').textContent =
        count === null || count === undefined ? '-' : `${formatNumber(count)}명`;
}

/** 요약 6칸과 유입·유출. summary는 GET /dashboard/summary 응답 */
export function renderSummary(summary, levels) {
    // 통계 시각이 있어야 통계 값을 보인다
    const hasStat = summary.statAt !== null && summary.statAt !== undefined;
    byId('summary-time').textContent = hasStat ? `${timeOf(summary.statAt)} · 1분 평균` : '';
    // id 칸에 글자를 넣는 도우미
    const fill = (id, text) => {
        byId(id).textContent = text;
    };
    // 현재 인원은 5초 갱신 값을 우선 쓰는 함수가 따로 그린다
    renderSummaryCount(summary, nowTotal());
    fill('sum-density', hasStat ? formatDecimal(summary.density, 2) : '-');
    byId('sum-level').replaceChildren(hasStat ? levelChip(summary.level, levels) : NO_DATA);
    fill('sum-cameras', `${summary.runningCameraCount} / ${summary.cameraCount}대`);
    fill('sum-events', `${formatNumber(summary.todayEventCount)}건`);
    fill(
        'sum-max',
        summary.todayMaxCount === null ? '-' : `${formatNumber(summary.todayMaxCount)}명`,
    );

    fill('flow-in', hasStat ? `+${formatNumber(summary.inflow)}` : '-');
    fill('flow-out', hasStat ? `−${formatNumber(summary.outflow)}` : '-');
    fill('flow-dir', DIRECTIONS[summary.mainDirection] ?? '-');
}

/** 혼잡 기준: 단계 이름·색·경계 밀도를 읽기 전용으로 */
export function renderLevelRules(levels) {
    // 밀집도 기준이 0보다 큰 단계만 모아 '0.7 / 1.5 명/㎡' 같은 한 줄 요약을 만든다
    const thresholds = levels
        .filter((level) => Number(level.minDensity) > 0)
        .map((level) => level.minDensity);
    byId('level-rule').textContent = thresholds.length > 0 ? `${thresholds.join(' / ')} 명/㎡` : '';
    byId('level-rules').replaceChildren(
        ...levels.map(({ name, color, minDensity }, index) => {
            const item = document.createElement('li');
            const dot = document.createElement('i');
            dot.className = 'dot';
            dot.style.background = color;
            // 다음 단계의 기준을 '미만' 상한으로 쓴다. 가장 높은 단계는 상한이 없다
            const next = levels[index + 1];
            const text =
                Number(minDensity) > 0
                    ? `${name} ${Number(minDensity)} 명/㎡ 이상`
                    : `${name} ${next ? Number(next.minDensity) : ''} 명/㎡ 미만`;
            item.append(dot, text);
            return item;
        }),
    );
}

/** 지도 범례: 단계 색 + 통계 없음(회색) */
export function renderMapLegend(levels) {
    const legend = byId('map-legend');
    // 항목 하나(색 점 + 이름)
    const make = (name, color) => {
        const item = document.createElement('span');
        const dot = document.createElement('i');
        dot.className = 'dot';
        dot.style.background = color;
        item.append(dot, name);
        return item;
    };
    legend.replaceChildren(
        ...levels.map(({ name, color }) => make(name, color)),
        make('통계 없음', OTHER_COLOR),
    );
}
