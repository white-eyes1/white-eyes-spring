// 다시보기 화면(SCR-A-009, FNC-A-047). 구역·시각으로 녹화를 찾아(FNC-A-043) 재생하고,
// 같은 구역의 1분 통계 시간축과 재생 위치를 맞춘다. 영상이 끝나면 같은 캠의 다음 녹화를 이어 튼다(FNC-A-045). 탐지 박스(FNC-A-046)는 3단계다.
import { ApiError, get, url } from '../../common/api.js';
import { formatDecimal, formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import {
    buildChart,
    eventAt,
    findMinute,
    formatClock,
    formatHm,
    fraction,
    minuteStart,
    nextEvent,
    normalizeEvents,
    normalizeRanges,
    normalizeSeries,
    previousEvent,
    recordingCovers,
    timeAt,
    toKstIso,
    toPath,
    windowFor,
} from '../playback/model.js';

const NOTICE_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
const LEVEL_LABELS = { 1: '정상', 2: '주의', 3: '위험' };
const CHART_WIDTH = 1000;
const CHART_HEIGHT = 220;

const params = new URLSearchParams(location.search);
const zoneId = Number(params.get('zoneId'));
const requestedAt = params.get('at') ? Date.parse(params.get('at')) : null;
// 주소의 at이 시각이 아니면(NaN) 없는 것으로 보고 가장 최근 녹화를 연다
const initialAt = Number.isFinite(requestedAt) ? requestedAt : null;

const video = document.getElementById('pb-video');
const notice = document.getElementById('pb-notice');
const feedLabel = document.getElementById('pb-feed-label');
const toggleButton = document.getElementById('pb-toggle');
const toggleIcon = document.getElementById('pb-toggle-icon');
const prevEventButton = document.getElementById('pb-prev-event');
const nextEventButton = document.getElementById('pb-next-event');
const clock = document.getElementById('pb-clock');
const chart = document.getElementById('pb-chart');
const playhead = document.getElementById('pb-playhead');
const playheadTip = document.getElementById('pb-playhead-tip');
const chartEmpty = document.getElementById('pb-chart-empty');

// 재생 중인 녹화: {id, videoUrl, startedAt(ms), endedAt(ms|null)}. 영상이 없는 시각으로 옮기면 null
let recording = null;
// 영상이 없을 때 재생 위치로 쓰는 시각(ms)
let manualMs = initialAt;
let range = '1h';
let metric = 'count';
let timeWindow = null;
let series = [];
let events = [];
// 시간축 범위의 녹화 구간. 못 읽었으면 null(띠를 숨긴다)
let recordingRanges = null;
let shownMinute = null;
// 시간축 경계선(주의·위험)에 쓰는 값: 구역 면적(㎡)과 단계가 시작되는 밀집도(명/㎡)
let zoneArea = null;
let levelStarts = {};
let timelineRequest = 0;
// 녹화 찾기 요청 번호. 늦게 온 응답이 나중에 한 이동을 덮지 않게 한다
let recordingRequest = 0;
// 이어 재생(FNC-A-045): 끝나기 직전에 미리 받아 둔 다음 녹화 { recordingId, promise }. 같은 녹화에서는 한 번만 받는다
let upcoming = null;
// 시간축을 다시 읽는 중이면 true(재생이 시간창 밖으로 나가 다시 맞추는 동안 중복 요청을 막는다)
let recentering = false;
const PREFETCH_BEFORE_END_SEC = 3;

const PLAY_PATH = 'M8 5v14l11-7z';
const PAUSE_PATH = 'M6 5h4v14H6zM14 5h4v14h-4z';

init();

async function init() {
    if (!Number.isInteger(zoneId) || zoneId <= 0) {
        showNotice('구역이 지정되지 않았습니다. 그래프나 이벤트에서 다시보기를 열어 주세요.');
        document.getElementById('pb-subtitle').textContent = '-';
        return;
    }
    if (requestedAt !== null && initialAt === null) {
        showToast('주소의 시각을 읽을 수 없어 가장 최근 녹화를 엽니다.');
    }
    loadZoneInfo();
    loadLevelStarts();
    await loadRecording(initialAt === null ? { latest: true } : { at: initialAt });
    // 시간축은 처음 요청한 시각(없으면 찾은 녹화의 시작)을 가운데로 연다
    await loadTimeline(initialAt ?? playheadMs() ?? Date.now());
}

// 구역 이름·면적은 머리글에만 쓰므로 실패해도 화면은 계속 쓴다
async function loadZoneInfo() {
    try {
        const zones = await get('/api/v1/zones', undefined, { toast: false });
        const zone = zones.find((item) => item.id === zoneId);
        zoneArea = zone ? Number(zone.areaM2) : null;
        document.getElementById('pb-title').textContent = zone ? `다시보기 · ${zone.name}` : `다시보기 · 구역 ${zoneId}`;
        document.getElementById('pb-subtitle').textContent = zone ? `${formatNumber(Math.round(zone.areaM2))}㎡` : '삭제되었거나 찾을 수 없는 구역';
    } catch {
        document.getElementById('pb-title').textContent = `다시보기 · 구역 ${zoneId}`;
        document.getElementById('pb-subtitle').textContent = '-';
    }
}

// 주의·위험 경계선용. 못 읽으면 경계선만 그리지 않는다
async function loadLevelStarts() {
    try {
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        levelStarts = Object.fromEntries(levels.map((level) => [level.level, Number(level.minDensity)]));
        if (timeWindow) {
            renderTimeline();
        }
    } catch {
        levelStarts = {};
    }
}

// 항목별 경계 값: 인원 = 밀집도 × 구역 면적, 밀집도 = 밀집도 그대로, 유입·유출에는 경계가 없다
function thresholds() {
    const factor = metric === 'count' ? zoneArea : metric === 'density' ? 1 : null;
    if (!factor || levelStarts[2] === undefined) {
        return [];
    }
    return [
        { key: 'warn', value: levelStarts[2] * factor },
        { key: 'danger', value: levelStarts[3] * factor },
    ];
}

// ---- 녹화 찾기·재생 (FNC-A-043) ----

// 더 최근에 시작한 요청이 있어 이 응답을 버렸으면 null, 영상이 있으면 true, 없으면 false
async function loadRecording({ at, latest }) {
    const request = ++recordingRequest;
    showNotice('불러오는 중…');
    try {
        const query = latest ? { zoneId, latest: true } : { zoneId, at: toKstIso(at) };
        const found = await get('/api/v1/playback', query, { toast: false });
        if (request !== recordingRequest) {
            return null;
        }
        recording = {
            id: found.recordingId,
            videoUrl: found.videoUrl,
            startedAt: Date.parse(found.startedAt),
            endedAt: found.endedAt ? Date.parse(found.endedAt) : null,
        };
        manualMs = null;
        upcoming = null;
        playRecording(found.videoUrl, found.seekSec);
        syncAddress(recording.startedAt + found.seekSec * 1000);
        return true;
    } catch (error) {
        if (request !== recordingRequest) {
            return null;
        }
        recording = null;
        manualMs = at ?? manualMs;
        video.pause();
        video.hidden = true;
        toggleButton.disabled = true;
        showNotice(error instanceof ApiError && NOTICE_MESSAGES[error.code] ? NOTICE_MESSAGES[error.code] : '영상을 불러올 수 없습니다.');
        updatePlayhead();
        return false;
    }
}

function playRecording(videoUrl, seekSec) {
    hideNotice();
    video.hidden = false;
    toggleButton.disabled = false;
    feedLabel.hidden = false;
    feedLabel.textContent = `녹화 · ${formatHm(recording.startedAt)}${recording.endedAt ? ` ~ ${formatHm(recording.endedAt)}` : ''}`;
    const start = () => {
        video.currentTime = seekSec;
        video.play().catch(() => {
            // 자동 재생이 막혀도 재생 버튼으로 시작할 수 있다
        });
    };
    if (video.dataset.src === videoUrl && video.readyState >= 1) {
        start();
    } else {
        video.dataset.src = videoUrl;
        video.addEventListener('loadedmetadata', start, { once: true });
        video.src = videoUrl;
    }
    // 새로 열면 재생 속도를 유지한다
    video.playbackRate = currentRate();
    updatePlayhead();
}

function showNotice(message) {
    notice.textContent = message;
    notice.hidden = false;
    if (!recording) {
        feedLabel.hidden = true;
    }
}

function hideNotice() {
    notice.hidden = true;
}

/** 지금 재생 위치(ms). 영상이 있으면 녹화 시작 + currentTime, 없으면 사용자가 옮겨 둔 시각. */
function playheadMs() {
    if (recording) {
        return recording.startedAt + video.currentTime * 1000;
    }
    return manualMs;
}

/** 시각으로 이동한다. 지금 녹화 안이면 currentTime만 바꾸고, 아니면 FNC-A-043을 다시 호출한다. */
async function seekToTime(ms) {
    if (recordingCovers(recording, ms)) {
        // 앞서 보낸 녹화 찾기의 응답이 이 이동을 덮지 않게 한다
        recordingRequest++;
        video.currentTime = (ms - recording.startedAt) / 1000;
        if (video.paused) {
            video.play().catch(() => {});
        }
        updatePlayhead();
        syncAddress(ms);
        return;
    }
    manualMs = ms;
    const found = await loadRecording({ at: ms });
    if (found === false) {
        syncAddress(ms);
        showToast(`${formatClock(ms)} 에는 재생할 영상이 없습니다.`);
    }
}

// ---- 이어 재생(FNC-A-045) ----

// 끝나기 직전에 같은 캠의 다음 녹화를 미리 물어 둬서, 영상이 끝나면 바로 다음 영상으로 넘어가게 한다
function prefetchNext() {
    if (!recording || recording.endedAt === null || upcoming?.recordingId === recording.id) {
        return;
    }
    if (!(video.duration - video.currentTime <= PREFETCH_BEFORE_END_SEC)) {
        return;
    }
    upcoming = { recordingId: recording.id, promise: get(`/api/v1/recordings/${recording.id}/next`, undefined, { toast: false }) };
    // 실패는 영상이 끝났을 때 처리한다(여기서는 미처리 거부로 남지 않게만 한다)
    upcoming.promise.catch(() => {});
}

// 영상이 끝났을 때: 다음 녹화가 있으면 0초부터 이어 틀고, 없으면 '이후 영상 없음'을 알린다
async function continuePlayback() {
    if (!recording) {
        return;
    }
    const finished = recording;
    const request = ++recordingRequest;
    const pending = upcoming?.recordingId === finished.id
        ? upcoming.promise
        : get(`/api/v1/recordings/${finished.id}/next`, undefined, { toast: false });
    try {
        const found = await pending;
        if (request !== recordingRequest) {
            return;
        }
        recording = {
            id: found.recordingId,
            videoUrl: found.videoUrl,
            startedAt: Date.parse(found.startedAt),
            endedAt: found.endedAt ? Date.parse(found.endedAt) : null,
        };
        upcoming = null;
        playRecording(found.videoUrl, found.seekSec);
        syncAddress(recording.startedAt);
    } catch (error) {
        if (request !== recordingRequest) {
            return;
        }
        upcoming = null;
        showNotice(error instanceof ApiError && error.status === 404 ? '이후 영상 없음' : '다음 영상을 불러올 수 없습니다.');
    }
}

// ---- 재생 위치 표시 ----

function updatePlayhead() {
    const ms = playheadMs();
    if (ms === null) {
        clock.textContent = '-';
        playhead.hidden = true;
        return;
    }
    clock.textContent = formatClock(ms);
    recenterIfLeftWindow(ms);
    renderStat(ms);
    placePlayhead(ms);
    updateEventButtons(ms);
}

// 재생이 시간축 창 밖으로 나가면(창 끝·자정을 넘겨 이어 재생할 때) 재생 위치를 가운데로 해서 통계 시간축·1분 통계를 다시 읽는다
function recenterIfLeftWindow(ms) {
    if (!recording || !timeWindow || recentering || dragging || (ms >= timeWindow.from && ms <= timeWindow.to)) {
        return;
    }
    recentering = true;
    loadTimeline(ms).finally(() => {
        recentering = false;
    });
}

// 주소의 at을 지금 찾은 시각으로 맞춰 새로고침해도 같은 자리에서 열리게 한다(재생 중 매번 바꾸지는 않는다)
function syncAddress(ms) {
    const next = new URL(location.href);
    next.searchParams.set('at', toKstIso(ms));
    history.replaceState(null, '', next);
}

function placePlayhead(ms, preview = false) {
    if (!timeWindow || ms < timeWindow.from || ms > timeWindow.to) {
        playhead.hidden = true;
        return;
    }
    playhead.hidden = false;
    playhead.style.left = `${fraction(ms, timeWindow.from, timeWindow.to) * 100}%`;
    const item = findMinute(series, ms);
    const parts = [formatClock(ms).slice(11)];
    if (item) {
        parts.push(`${formatNumber(Math.round(item.countAvg))}명`, LEVEL_LABELS[item.level] ?? '-');
    }
    playheadTip.textContent = parts.join(' · ');
    if (preview) {
        renderStat(ms);
    }
}

function renderStat(ms) {
    const key = minuteStart(ms);
    const event = eventAt(events, ms);
    const eventKey = event ? event.id : null;
    if (shownMinute === `${key}:${eventKey}`) {
        return;
    }
    shownMinute = `${key}:${eventKey}`;
    const item = findMinute(series, ms);
    document.getElementById('pb-stat-minute').textContent = formatHm(key);
    document.getElementById('pb-stat-empty').hidden = item !== null;
    document.getElementById('pb-stat-count').textContent = item ? `${formatNumber(Math.round(item.countAvg))}명` : '-';
    document.getElementById('pb-stat-density').textContent = item ? formatDecimal(item.density, 2) : '-';
    document.getElementById('pb-stat-range').textContent = item ? `${formatNumber(Math.round(item.countMax))} · ${formatNumber(Math.round(item.countMin))}` : '-';
    const levelCell = document.getElementById('pb-stat-level');
    levelCell.replaceChildren();
    if (item && LEVEL_LABELS[item.level]) {
        const chip = document.createElement('span');
        chip.className = `chip l${item.level}`;
        chip.textContent = LEVEL_LABELS[item.level];
        levelCell.append(chip);
    } else {
        levelCell.textContent = '-';
    }
    document.getElementById('pb-stat-in').textContent = item ? `+${formatNumber(item.inflow)}` : '-';
    document.getElementById('pb-stat-out').textContent = item ? `−${formatNumber(item.outflow)}` : '-';

    const box = document.getElementById('pb-event-box');
    box.hidden = event === null;
    if (event) {
        document.getElementById('pb-event-chip').textContent = event.type === 'SURGE' ? '급증' : '혼잡';
        document.getElementById('pb-event-text').textContent = `${formatHm(event.startedAt)} ~ ${event.endedAt ? formatHm(event.endedAt) : '진행 중'} 이벤트 안`;
        // 이벤트 상세 창(SCR-A-011)은 이벤트 로그 화면 안에 있어 일단 이벤트 로그로 연결한다
        document.getElementById('pb-event-link').href = url('/events');
    }
}

function updateEventButtons(ms) {
    prevEventButton.disabled = previousEvent(events, ms) === null;
    nextEventButton.disabled = nextEvent(events, ms) === null;
}

// ---- 통계 시간축 ----

async function loadTimeline(center) {
    const request = ++timelineRequest;
    timeWindow = windowFor(range, center);
    const query = { from: toKstIso(timeWindow.from), to: toKstIso(timeWindow.to) };
    const [seriesResult, eventsResult, rangesResult] = await Promise.allSettled([
        get('/api/v1/stats/series', { targetType: 'ZONE', targetId: zoneId, interval: '1m', ...query }, { toast: false }),
        get('/api/v1/events', { zoneId, ...query, size: 100 }, { toast: false }),
        get('/api/v1/playback/ranges', { zoneId, ...query }, { toast: false }),
    ]);
    if (request !== timelineRequest) {
        return;
    }
    series = seriesResult.status === 'fulfilled' ? normalizeSeries(seriesResult.value?.items ?? []) : [];
    events = eventsResult.status === 'fulfilled' ? normalizeEvents(eventsResult.value?.items ?? []) : [];
    recordingRanges = rangesResult.status === 'fulfilled' ? normalizeRanges(rangesResult.value?.ranges ?? []) : null;
    shownMinute = null;
    renderTimeline();
    updatePlayhead();
}

function renderTimeline() {
    const { from, to } = timeWindow;
    const shape = buildChart(series, metric, from, to, CHART_WIDTH, CHART_HEIGHT, thresholds());
    document.getElementById('pb-band').setAttribute('d', shape.band ? `${toPath(shape.band)} Z` : '');
    const linesGroup = document.getElementById('pb-lines');
    linesGroup.replaceChildren();
    shape.lines.forEach((line, index) => {
        line.segments.forEach((segment) => {
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', toPath(segment));
            path.setAttribute('class', index === 0 ? 'pb-line' : 'pb-line second');
            linesGroup.append(path);
        });
    });
    chartEmpty.hidden = series.length > 0;
    renderGuides(shape.guides);

    const layer = document.getElementById('pb-events-layer');
    layer.replaceChildren();
    events.forEach((event) => {
        const end = event.endedAt ?? to;
        if (end < from || event.startedAt > to) {
            return;
        }
        const startFraction = fraction(event.startedAt, from, to);
        const span = document.createElement('div');
        span.className = 'pb-event-range';
        span.style.left = `${startFraction * 100}%`;
        span.style.width = `${(fraction(end, from, to) - startFraction) * 100}%`;
        layer.append(span);
        if (event.startedAt >= from) {
            const mark = document.createElement('span');
            mark.className = 'pb-event-mark';
            mark.style.left = `${startFraction * 100}%`;
            mark.textContent = '▼';
            layer.append(mark);
        }
    });

    renderLevelStrip(from, to);
    renderRecordingStrip(from, to);
    const axis = document.getElementById('pb-axis');
    axis.replaceChildren(
        ...[0, 0.25, 0.5, 0.75, 1].map((position) => {
            const label = document.createElement('span');
            label.textContent = formatHm(timeAt(position, from, to));
            return label;
        }),
    );
}

// 주의·위험 경계선과 왼쪽 이름
function renderGuides(guides) {
    const group = document.getElementById('pb-guides');
    const labels = document.getElementById('pb-guide-labels');
    group.replaceChildren();
    labels.replaceChildren();
    guides.forEach((guide) => {
        const line = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        line.setAttribute('d', `M0 ${guide.y.toFixed(1)}H${CHART_WIDTH}`);
        line.setAttribute('class', `pb-guide ${guide.key}`);
        group.append(line);
        const label = document.createElement('span');
        label.className = 'pb-guide-label';
        label.style.top = `${(guide.y / CHART_HEIGHT) * 100}%`;
        label.textContent = guide.key === 'danger' ? '위험' : '주의';
        labels.append(label);
    });
}

// 분마다 혼잡 단계 색 띠. 통계가 없는 분은 비워 둔다
function renderLevelStrip(from, to) {
    const byMinute = new Map(series.map((item) => [item.at, item]));
    const cells = [];
    for (let at = minuteStart(from); at < to; at += 60 * 1000) {
        const cell = document.createElement('span');
        const item = byMinute.get(at);
        if (item && LEVEL_LABELS[item.level]) {
            cell.className = `l${item.level}`;
        }
        cells.push(cell);
    }
    document.getElementById('pb-level-strip').replaceChildren(...cells);
}

// 녹화가 있는 구간 띠(FNC-A-043 구간 조회). 못 읽었으면 없음/있음을 알 수 없으므로 띠를 숨긴다
function renderRecordingStrip(from, to) {
    const strip = document.getElementById('pb-rec-strip');
    strip.hidden = recordingRanges === null;
    document.getElementById('pb-legend-rec').hidden = recordingRanges === null;
    const spans = (recordingRanges ?? []).map((item) => {
        const start = fraction(item.from, from, to);
        const span = document.createElement('span');
        span.style.left = `${start * 100}%`;
        span.style.width = `${(fraction(item.to, from, to) - start) * 100}%`;
        return span;
    });
    strip.replaceChildren(...spans);
}

// ---- 조작 ----

let dragging = false;

function chartTime(event) {
    const rect = chart.getBoundingClientRect();
    return timeAt((event.clientX - rect.left) / rect.width, timeWindow.from, timeWindow.to);
}

chart.addEventListener('pointerdown', (event) => {
    if (!timeWindow || event.button !== 0) {
        return;
    }
    dragging = true;
    chart.setPointerCapture(event.pointerId);
    placePlayhead(chartTime(event), true);
});
chart.addEventListener('pointermove', (event) => {
    if (dragging) {
        placePlayhead(chartTime(event), true);
    }
});
chart.addEventListener('pointerup', (event) => {
    if (!dragging) {
        return;
    }
    dragging = false;
    seekToTime(chartTime(event));
});
chart.addEventListener('pointercancel', () => {
    dragging = false;
    updatePlayhead();
});

video.addEventListener('timeupdate', () => {
    prefetchNext();
    if (!dragging) {
        updatePlayhead();
    }
});
video.addEventListener('ended', continuePlayback);
video.addEventListener('play', () => {
    togglePlayIcon(true);
    // 끝 뒤에 다시 재생하면 '이후 영상 없음' 안내를 걷는다
    if (recording) {
        hideNotice();
    }
});
video.addEventListener('pause', () => togglePlayIcon(false));
video.addEventListener('error', () => {
    showNotice('영상을 불러올 수 없습니다.');
    video.hidden = true;
    toggleButton.disabled = true;
});

function togglePlayIcon(playing) {
    toggleIcon.setAttribute('d', playing ? PAUSE_PATH : PLAY_PATH);
    toggleButton.setAttribute('aria-label', playing ? '일시 정지' : '재생');
}

toggleButton.addEventListener('click', () => {
    if (video.paused) {
        video.play().catch(() => {});
    } else {
        video.pause();
    }
});

function currentRate() {
    return Number(document.querySelector('#pb-speed button.on').dataset.rate);
}

document.querySelectorAll('#pb-speed button').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('#pb-speed button').forEach((other) => other.classList.toggle('on', other === button));
        video.playbackRate = Number(button.dataset.rate);
    });
});

prevEventButton.addEventListener('click', () => {
    const target = previousEvent(events, playheadMs());
    if (target) {
        seekToTime(target.startedAt);
    }
});
nextEventButton.addEventListener('click', () => {
    const target = nextEvent(events, playheadMs());
    if (target) {
        seekToTime(target.startedAt);
    }
});

document.querySelectorAll('#pb-range button').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('#pb-range button').forEach((other) => other.classList.toggle('on', other === button));
        range = button.dataset.range;
        loadTimeline(playheadMs() ?? Date.now());
    });
});
document.querySelectorAll('#pb-metric button').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('#pb-metric button').forEach((other) => other.classList.toggle('on', other === button));
        metric = button.dataset.metric;
        if (timeWindow) {
            renderTimeline();
            updatePlayhead();
        }
    });
});
