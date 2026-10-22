// 통계 메인 왼쪽 통계 패널(SCR-C-003, FNC-C-009·016·017·025): 구역별 현재 인원 도넛·목록, 요약 6칸, 유입·유출, 혼잡 기준.
// 서버 값은 textContent로만 넣는다(code-conventions 5-3). 5분 안에 통계가 없으면 '데이터 없음'(api/public.md).
import { formatDateTime, formatDecimal, formatNumber } from '../../common/format.js';
import { levelColor } from '../../map/map-view.js';

const NO_DATA = '데이터 없음';
const TOP_ZONES = 5;
const OTHER_COLOR = '#4a5768';
const DIRECTIONS = { N: '북(N)', E: '동(E)', S: '남(S)', W: '서(W)' };

const byId = (id) => document.getElementById(id);

/** 'yyyy-MM-dd HH:mm' 중 시각만 */
function timeOf(statAt) {
    return statAt ? formatDateTime(statAt).slice(11) : '';
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
    const entries = zoneEntries(items, tab, levels);
    const total = entries.reduce((sum, entry) => sum + entry.value, 0);
    const latest = items.map((item) => item.statAt).filter(Boolean).sort().pop();
    byId('zone-time').textContent = latest ? `${timeOf(latest)} 기준` : '';

    const donut = byId('zone-donut');
    const list = byId('zone-list');
    if (items.length === 0 || (tab !== 'level' && total === 0 && !items.some((item) => item.level))) {
        donut.style.background = '';
        byId('zone-total').textContent = '-';
        list.replaceChildren(emptyText());
        return;
    }
    donut.style.background = conic(entries, total);
    byId('zone-total').textContent = formatNumber(total) + (tab === 'count' ? '명' : tab === 'event' ? '건' : '곳');
    const max = Math.max(...entries.map((entry) => entry.value), 1);
    list.replaceChildren(...entries.map((entry) => listRow(entry, max)));
}

function zoneEntries(items, tab, levels) {
    if (tab === 'level') {
        const rows = levels.map(({ level, name }) => ({
            label: name,
            value: items.filter((item) => item.level === level).length,
            color: levelColor(level),
        }));
        rows.push({ label: NO_DATA, value: items.filter((item) => !item.level).length, color: OTHER_COLOR });
        return rows.filter((row) => row.value > 0);
    }
    const valueOf = (item) =>
        tab === 'event' ? item.todayEventCount : item.countAvg === null ? 0 : Math.round(Number(item.countAvg));
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

function conic(entries, total) {
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

function emptyText() {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.textContent = NO_DATA;
    return empty;
}

/** 혼잡 단계 칩(색은 we.css .chip.l1~l3). 단계가 없으면 '데이터 없음' */
export function levelChip(level, levels) {
    const chip = document.createElement('span');
    const found = levels.find((candidate) => candidate.level === level);
    chip.className = 'chip ' + (found ? 'l' + level : 'off');
    chip.textContent = found ? found.name : NO_DATA;
    return chip;
}

/** 요약 6칸과 유입·유출. summary는 GET /dashboard/summary 응답 */
export function renderSummary(summary, levels) {
    const hasStat = summary.statAt !== null && summary.statAt !== undefined;
    byId('summary-time').textContent = hasStat ? `${timeOf(summary.statAt)} · 1분 평균` : '';
    const fill = (id, text) => {
        byId(id).textContent = text;
    };
    fill('sum-count', hasStat ? `${formatNumber(Math.round(Number(summary.countAvg)))}명` : '-');
    fill('sum-density', hasStat ? formatDecimal(summary.density, 2) : '-');
    byId('sum-level').replaceChildren(hasStat ? levelChip(summary.level, levels) : NO_DATA);
    fill('sum-cameras', `${summary.runningCameraCount} / ${summary.cameraCount}대`);
    fill('sum-events', `${formatNumber(summary.todayEventCount)}건`);
    fill('sum-max', summary.todayMaxCount === null ? '-' : `${formatNumber(summary.todayMaxCount)}명`);

    fill('flow-in', hasStat ? `+${formatNumber(summary.inflow)}` : '-');
    fill('flow-out', hasStat ? `−${formatNumber(summary.outflow)}` : '-');
    fill('flow-dir', DIRECTIONS[summary.mainDirection] ?? '-');
}

/** 혼잡 기준: 단계 이름·색·경계 밀도를 읽기 전용으로 */
export function renderLevelRules(levels) {
    const thresholds = levels.filter((level) => Number(level.minDensity) > 0).map((level) => level.minDensity);
    byId('level-rule').textContent = thresholds.length > 0 ? `${thresholds.join(' / ')} 명/㎡` : '';
    byId('level-rules').replaceChildren(
        ...levels.map(({ name, color, minDensity }, index) => {
            const item = document.createElement('li');
            const dot = document.createElement('i');
            dot.className = 'dot';
            dot.style.background = color;
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
    const make = (name, color) => {
        const item = document.createElement('span');
        const dot = document.createElement('i');
        dot.className = 'dot';
        dot.style.background = color;
        item.append(dot, name);
        return item;
    };
    legend.replaceChildren(...levels.map(({ name, color }) => make(name, color)), make('통계 없음', OTHER_COLOR));
}
