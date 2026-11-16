// 다시보기 화면(SCR-A-009, FNC-A-047). 구역·시각으로 녹화를 찾아(FNC-A-043) 재생하고,
// 같은 구역의 1분 통계 시간축과 재생 위치를 맞춘다. 영상이 끝나면 같은 캠의 다음 녹화를 이어 튼다(FNC-A-045). 탐지 박스(FNC-A-046)는 3단계다.
// 공용 API 모듈, 숫자 표시 함수, 토스트, 그리고 시각·통계·이벤트 계산 모듈(playback/model.js)과 탐지 박스 계산 모듈(playback/boxes.js)을 가져온다. 화면(DOM) 조작은 이 파일이, 순수 계산은 두 모듈이 맡는다.
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
    eventsStartedIn,
    normalizeEvents,
    normalizeRanges,
    normalizeSeries,
    previousEvent,
    recordedInMinute,
    recordingCovers,
    timeAt,
    toKstIso,
    toPath,
    windowFor,
} from '../playback/model.js';
import { containRect, findFrame, parseFrames, toPixels } from '../playback/boxes.js';

// 녹화 조회 오류 코드별 영상 영역 안내 문구.
const NOTICE_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
// 혼잡 단계 번호를 화면 이름으로 바꾸는 사전. CHART_WIDTH·HEIGHT는 SVG 그래프의 가상 좌표 크기다(실제 크기는 CSS가 늘린다).
const LEVEL_LABELS = { 1: '정상', 2: '주의', 3: '위험' };
const CHART_WIDTH = 1000;
const CHART_HEIGHT = 220;

// 주소 쿼리에서 대상 구역 id(zoneId)와 열 시각(at)을 읽는다. 이벤트 상세·그래프 등에서 링크로 들어온다.
const params = new URLSearchParams(location.search);
const zoneId = Number(params.get('zoneId'));
const requestedAt = params.get('at') ? Date.parse(params.get('at')) : null;
// 주소의 at이 시각이 아니면(NaN) 없는 것으로 보고 가장 최근 녹화를 연다
const initialAt = Number.isFinite(requestedAt) ? requestedAt : null;

// 화면 요소를 id로 한 번만 찾아 둔다(영상, 안내, 재생 버튼, 이벤트 이동 버튼, 시계, 시간축 그래프, 재생 위치 막대, 탐지 박스 캔버스와 스위치).
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
const boxCanvas = document.getElementById('pb-boxes');
const boxNoteEl = document.getElementById('pb-box-note');
const boxSwitch = document.getElementById('pb-box-switch');

// 아래는 화면의 상태 변수들이다. recording은 지금 재생 중인 녹화, manualMs는 영상이 없을 때의 위치, range는 시간축 범위, metric은 그래프 항목(인원·밀도·유동).
// 재생 중인 녹화: {id, videoUrl, startedAt(ms), endedAt(ms|null)}. 영상이 없는 시각으로 옮기면 null
let recording = null;
// 영상이 없을 때 재생 위치로 쓰는 시각(ms)
let manualMs = initialAt;
let range = '1h';
let metric = 'count';
// timeWindow: 지금 시간축이 보이는 시각 범위 {from, to}. series: 1분 통계. events: 범위 안 이벤트.
let timeWindow = null;
let series = [];
let events = [];
// 시간축 범위의 녹화 구간. 못 읽었으면 null(띠를 숨긴다)
// 시간축 범위 안에서 녹화가 있는 구간들. 못 읽으면 null.
let recordingRanges = null;
// shownMinute: 통계 패널에 마지막으로 그린 분과 이벤트 키(같으면 다시 그리지 않아 재생 중 불필요한 갱신을 줄인다).
let shownMinute = null;
// 시간축 경계선(주의·위험)에 쓰는 값: 구역 면적(㎡)과 단계가 시작되는 밀집도(명/㎡)
let zoneArea = null;
let levelStarts = {};
// timelineRequest: 시간축 요청 번호(범위를 빠르게 바꿔도 마지막 요청 결과만 반영).
let timelineRequest = 0;
// 녹화 찾기 요청 번호. 늦게 온 응답이 나중에 한 이동을 덮지 않게 한다
let recordingRequest = 0;
// 탐지 박스(FNC-A-046): 지금 녹화의 좌표 파일 프레임(t 순서). 못 받았거나 없으면 null. 박스 표시 스위치(기본 켬)
let boxFrames = null;
let boxesFor = null;
let showBoxes = true;
const BOX_COLOR = '#5ee0b5';
// 이어 재생(FNC-A-045): 끝나기 직전에 미리 받아 둔 다음 녹화 { recordingId, promise }. 같은 녹화에서는 한 번만 받는다
let upcoming = null;
// 시간축을 다시 읽는 중이면 true(재생이 시간창 밖으로 나가 다시 맞추는 동안 중복 요청을 막는다)
let recentering = false;
// 영상이 끝나기 이 초 수 전부터 다음 녹화를 미리 조회한다.
const PREFETCH_BEFORE_END_SEC = 3;

// 재생·일시정지 아이콘의 SVG 경로.
const PLAY_PATH = 'M8 5v14l11-7z';
const PAUSE_PATH = 'M6 5h4v14H6zM14 5h4v14h-4z';

// 화면 시작점.
init();

// 시작 순서: 구역 id가 올바른지 확인 -> 구역 정보·단계 기준 로드(기다리지 않음) -> 녹화 찾기 -> 시간축 로드.
async function init() {
    // 구역 id가 없거나 잘못되면 더 진행하지 않고 안내만 보인다.
    if (!Number.isInteger(zoneId) || zoneId <= 0) {
        showNotice('구역이 지정되지 않았습니다. 그래프나 이벤트에서 다시보기를 열어 주세요.');
        document.getElementById('pb-subtitle').textContent = '-';
        return;
    }
    // at 값이 있었지만 시각으로 읽을 수 없는 경우를 알려 주고, 가장 최근 녹화로 대신 연다.
    if (requestedAt !== null && initialAt === null) {
        showToast('주소의 시각을 읽을 수 없어 가장 최근 녹화를 엽니다.');
    }
    loadZoneInfo();
    loadLevelStarts();
    // at이 있으면 그 시각의 녹화를, 없으면 가장 최근 녹화를 찾는다.
    await loadRecording(initialAt === null ? { latest: true } : { at: initialAt });
    // 시간축은 처음 요청한 시각(없으면 찾은 녹화의 시작)을 가운데로 연다
    await loadTimeline(initialAt ?? playheadMs() ?? Date.now());
}

// 구역 이름·면적은 머리글에만 쓰므로 실패해도 화면은 계속 쓴다
// 구역 이름·면적을 제목에 보인다. 면적은 인원 경계선 계산(밀집도 x 면적)에도 쓴다.
async function loadZoneInfo() {
    try {
        // 삭제된 구역도 이름을 받아 '(삭제됨)'을 붙인다(FNC-A-017 업무 규칙, #183). 없는 구역이면 404
        const zone = await get(`/api/v1/zones/${zoneId}`, undefined, { toast: false });
        zoneArea = Number(zone.areaM2);
        document.getElementById('pb-title').textContent =
            `다시보기 · ${zone.name}${zone.deleted ? ' (삭제됨)' : ''}`;
        document.getElementById('pb-subtitle').textContent =
            `${formatNumber(Math.round(zone.areaM2))}㎡`;
    // 실패하면 구역 id로 대신 보이고 화면은 계속 쓴다.
    } catch {
        document.getElementById('pb-title').textContent = `다시보기 · 구역 ${zoneId}`;
        document.getElementById('pb-subtitle').textContent = '찾을 수 없는 구역';
    }
}

// 주의·위험 경계선용. 못 읽으면 경계선만 그리지 않는다
// 혼잡 단계별 시작 밀집도(명/m2)를 받아 둔다. 주의·위험 경계선을 그리는 데 쓴다. 이미 시간축이 그려졌다면 경계선을 반영해 다시 그린다.
async function loadLevelStarts() {
    try {
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        levelStarts = Object.fromEntries(
            levels.map((level) => [level.level, Number(level.minDensity)]),
        );
        if (timeWindow) {
            renderTimeline();
        }
    } catch {
        levelStarts = {};
    }
}

// 항목별 경계 값: 인원 = 밀집도 × 구역 면적, 밀집도 = 밀집도 그대로, 유입·유출에는 경계가 없다
// 그래프 항목에 맞는 경계 값(주의·위험)을 만든다. 인원은 밀집도에 면적을 곱해 변환하고, 밀도는 그대로이며, 유동은 경계가 없다. 면적이나 단계 기준을 못 받았으면 빈 목록이다.
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
// 녹화를 찾아 재생한다. at(시각) 또는 latest(가장 최근) 중 하나로 조회한다.
async function loadRecording({ at, latest }) {
    // 요청 번호. 사용자가 그 사이 다른 곳으로 옮겨 더 새 요청이 나갔으면 이 응답은 버린다.
    const request = ++recordingRequest;
    showNotice('불러오는 중…');
    try {
        // 최근 녹화는 latest 플래그, 아니면 구역과 시각(한국 시간 ISO)으로 조회한다.
        const query = latest ? { zoneId, latest: true } : { zoneId, at: toKstIso(at) };
        const found = await get('/api/v1/playback', query, { toast: false });
        if (request !== recordingRequest) {
            return null;
        }
        // 응답에서 필요한 값만 보관한다. 종료 시각이 없으면 아직 녹화 중이다.
        recording = {
            id: found.recordingId,
            videoUrl: found.videoUrl,
            startedAt: Date.parse(found.startedAt),
            endedAt: found.endedAt ? Date.parse(found.endedAt) : null,
        };
        // 영상이 있으므로 수동 위치는 필요 없고, 이전 녹화에서 미리 받아 둔 다음 녹화 정보는 버린다.
        manualMs = null;
        upcoming = null;
        // 탐지 박스 좌표 파일 주소는 따로 붙여 둔다.
        recording.boxesUrl = found.boxesUrl;
        // 영상을 재생하고, 좌표를 받고, 주소의 at을 현재 위치로 맞춘다.
        playRecording(found.videoUrl, found.seekSec);
        loadBoxes(recording);
        syncAddress(recording.startedAt + found.seekSec * 1000);
        return true;
    // 영상이 없는 경우다. 박스와 영상을 숨기고 시각만 수동 위치로 보관해 시간축에서는 그 시각을 가리키게 한다. 오류 코드별 문구를 안내로 보인다.
    } catch (error) {
        if (request !== recordingRequest) {
            return null;
        }
        recording = null;
        clearBoxes();
        manualMs = at ?? manualMs;
        video.pause();
        video.hidden = true;
        toggleButton.disabled = true;
        showNotice(
            error instanceof ApiError && NOTICE_MESSAGES[error.code]
                ? NOTICE_MESSAGES[error.code]
                : '영상을 불러올 수 없습니다.',
        );
        updatePlayhead();
        return false;
    }
}

// 영상 주소를 로드하고 seekSec 위치부터 재생한다. 같은 영상이 이미 로드돼 있으면 새로 받지 않고 위치만 옮긴다.
function playRecording(videoUrl, seekSec) {
    hideNotice();
    video.hidden = false;
    toggleButton.disabled = false;
    feedLabel.hidden = false;
    // 영상 위 라벨: 녹화 시작~종료 시각.
    feedLabel.textContent = `녹화 · ${formatHm(recording.startedAt)}${recording.endedAt ? ` ~ ${formatHm(recording.endedAt)}` : ''}`;
    // 메타데이터가 있어야 재생 위치를 옮길 수 있으므로 로드 후 실행할 동작으로 묶어 둔다.
    const start = () => {
        video.currentTime = seekSec;
        // 자동 재생이 막혀도 오류가 아니므로 삼킨다.
        video.play().catch(() => {
            // 자동 재생이 막혀도 재생 버튼으로 시작할 수 있다
        });
    };
    // readyState가 1 이상이면 메타데이터를 이미 받았다는 뜻이다.
    if (video.dataset.src === videoUrl && video.readyState >= 1) {
        start();
    } else {
        video.dataset.src = videoUrl;
        video.addEventListener('loadedmetadata', start, { once: true });
        video.src = videoUrl;
    }
    // 새 영상을 열어도 선택해 둔 재생 속도를 유지한다.
    // 새로 열면 재생 속도를 유지한다
    video.playbackRate = currentRate();
    updatePlayhead();
}

// 영상 영역의 안내 문구를 보인다. 재생 중인 녹화가 없으면 영상 라벨도 숨긴다.
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
// 시간축 클릭·이벤트 이동 등으로 시각을 옮긴다. 현재 녹화가 그 시각을 포함하면 영상 위치만 바꾸고, 아니면 그 시각의 녹화를 다시 찾는다. 찾아도 없으면(found가 false) 알린다.
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

// ---- 탐지 박스(FNC-A-046) ----

// 녹화가 바뀌면 좌표 파일을 받아 프레임을 t 순서로 보관한다. 못 받으면(404 등) 영상만 재생하고 '박스 정보 없음'을 보인다
// 녹화가 바뀔 때 탐지 박스 좌표 파일을 받는다(FNC-A-046). 요청 중 녹화가 또 바뀌면 결과를 버린다(boxesFor로 비교).
async function loadBoxes(target) {
    clearBoxes();
    boxesFor = target.id;
    try {
        // boxesUrl은 서버가 컨텍스트 경로(/admin)까지 붙여 준 주소다. 영상 주소처럼 absolute로 그대로 쓴다
        const frames = parseFrames(
            await get(target.boxesUrl, undefined, { absolute: true, toast: false }),
        );
        if (boxesFor !== target.id) {
            return; // 그 사이 다른 녹화로 바뀌었다
        }
        boxFrames = frames;
    } catch {
        if (boxesFor !== target.id) {
            return;
        }
        boxFrames = null;
    }
    setBoxNote(boxFrames === null);
    drawBoxes();
    armBoxLoop();
}

// 박스 정보와 안내를 비우고 캔버스를 지운다.
function clearBoxes() {
    boxesFor = null;
    boxFrames = null;
    setBoxNote(false);
    drawBoxes();
}

// 좌표 파일이 없다는 안내('박스 정보 없음')는 박스 표시를 켜 둔 때만 보인다.
function setBoxNote(missing) {
    boxNoteEl.hidden = !(missing && showBoxes);
}

// 재생 위치(video.currentTime)에서 t ≤ 위치인 마지막 프레임의 박스를 캔버스에 그린다. 영상은 칸 안에 비율을 지켜 그려지므로
// 영상 크기 기준 영역(containRect)에 맞춘다. 구간 이동 직후(seeked)에도 같은 계산이라 위치가 맞는다
// 현재 재생 위치의 박스를 캔버스에 그린다. 고해상도 화면에서 선이 흐려지지 않도록 캔버스 내부 해상도를 CSS 크기에 화면 배율을 곱해 맞춘다.
function drawBoxes() {
    const context = boxCanvas.getContext('2d');
    const ratio = window.devicePixelRatio || 1;
    const width = boxCanvas.clientWidth;
    const height = boxCanvas.clientHeight;
    // 크기가 바뀐 때만 width/height를 쓴다(쓰면 캔버스가 초기화되므로).
    if (
        boxCanvas.width !== Math.round(width * ratio) ||
        boxCanvas.height !== Math.round(height * ratio)
    ) {
        boxCanvas.width = Math.round(width * ratio);
        boxCanvas.height = Math.round(height * ratio);
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    // 스위치가 꺼졌거나 좌표·녹화·영상이 없으면 캔버스를 숨기고 끝낸다.
    boxCanvas.hidden = !(showBoxes && boxFrames !== null && recording !== null && !video.hidden);
    if (boxCanvas.hidden) {
        return;
    }
    // 영상이 칸 안에서 실제 차지하는 영역. 박스는 이 영역 기준으로 그려야 어긋나지 않는다.
    const area = containRect(width, height, video.videoWidth, video.videoHeight);
    const frame = findFrame(boxFrames, video.currentTime);
    if (!area || !frame) {
        return;
    }
    context.strokeStyle = BOX_COLOR;
    context.lineWidth = 1.5;
    toPixels(frame.boxes, area).forEach((box) => context.strokeRect(box.x, box.y, box.w, box.h));
}

// 그릴 시점: 영상 프레임이 화면에 올라갈 때마다(requestVideoFrameCallback, 없으면 requestAnimationFrame).
// 그릴 것이 없을 때(좌표 파일 없음·스위치 끔·녹화 없음)는 반복을 멈추고, 좌표 파일을 받거나 스위치를 켤 때 다시 건다
// 반복(영상 프레임마다 다시 그림)이 이미 걸려 있는지.
let boxLoopArmed = false;

// 반복을 돌릴 필요가 있는가: 스위치 켬, 좌표 있음, 녹화 있음.
function wantsBoxLoop() {
    return showBoxes && boxFrames !== null && recording !== null;
}

// 영상 프레임마다 drawBoxes를 부르도록 한 번만 걸어 둔다. 필요가 없어지면 다음 반복을 걸지 않아 멈춘다.
function armBoxLoop() {
    if (boxLoopArmed || !wantsBoxLoop()) {
        return;
    }
    boxLoopArmed = true;
    const tick = () => {
        boxLoopArmed = false;
        drawBoxes();
        armBoxLoop();
    };
    if ('requestVideoFrameCallback' in video) {
        video.requestVideoFrameCallback(tick);
    } else {
        window.requestAnimationFrame(tick);
    }
}

// 박스 표시 스위치: 켜고 끌 때 접근성 속성(aria-checked)과 안내, 캔버스, 반복을 맞춘다.
boxSwitch.addEventListener('click', () => {
    showBoxes = !showBoxes;
    boxSwitch.setAttribute('aria-checked', String(showBoxes));
    setBoxNote(boxFrames === null && recording !== null);
    drawBoxes();
    armBoxLoop();
});
// 일시 정지·구간 이동·창 크기 변경에도 위치가 맞도록 다시 그린다
// 일시 정지·구간 이동처럼 프레임 콜백이 없는 때에도 박스를 현재 위치에 맞춘다. 칸 크기가 바뀌어도 다시 그린다.
['seeked', 'loadedmetadata', 'pause', 'play'].forEach((name) =>
    video.addEventListener(name, drawBoxes),
);
new ResizeObserver(drawBoxes).observe(document.getElementById('pb-feed'));

// ---- 이어 재생(FNC-A-045) ----

// 끝나기 직전에 같은 캠의 다음 녹화를 미리 물어 둬서, 영상이 끝나면 바로 다음 영상으로 넘어가게 한다
// FNC-A-045. 영상 끝이 가까워지면 다음 녹화를 미리 조회해 두어 끝나자마자 이음매 없이 넘어가게 한다. 종료 시각이 없는 녹화(녹화 중)나 이미 받아 둔 경우는 건너뛴다.
function prefetchNext() {
    if (!recording || recording.endedAt === null || upcoming?.recordingId === recording.id) {
        return;
    }
    // duration이 NaN이거나 아직 멀었으면(비교가 거짓이 되므로) 조회하지 않는다. NaN도 걸러지도록 부정 형태로 썼다.
    if (!(video.duration - video.currentTime <= PREFETCH_BEFORE_END_SEC)) {
        return;
    }
    upcoming = {
        recordingId: recording.id,
        promise: get(`/api/v1/recordings/${recording.id}/next`, undefined, { toast: false }),
    };
    // 미리 받은 요청이 실패해도 여기서는 미처리 오류 경고만 막고, 실제 처리는 영상이 끝난 뒤 continuePlayback에서 한다.
    // 실패는 영상이 끝났을 때 처리한다(여기서는 미처리 거부로 남지 않게만 한다)
    upcoming.promise.catch(() => {});
}

// 영상이 끝났을 때: 다음 녹화가 있으면 0초부터 이어 틀고, 없으면 '이후 영상 없음'을 알린다
// 영상이 끝났을 때 호출된다. 미리 받아 둔 다음 녹화 응답이 있으면 쓰고 없으면 지금 조회한다. 성공하면 0초부터 이어 틀고, 404면 '이후 영상 없음'을 알린다.
async function continuePlayback() {
    if (!recording) {
        return;
    }
    const finished = recording;
    // 요청 번호를 올려 이전의 늦은 응답을 무효화한다(사용자가 그 사이 다른 곳으로 이동할 수 있다).
    const request = ++recordingRequest;
    const pending =
        upcoming?.recordingId === finished.id
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
        recording.boxesUrl = found.boxesUrl;
        playRecording(found.videoUrl, found.seekSec);
        loadBoxes(recording);
        syncAddress(recording.startedAt);
    } catch (error) {
        if (request !== recordingRequest) {
            return;
        }
        upcoming = null;
        showNotice(
            error instanceof ApiError && error.status === 404
                ? '이후 영상 없음'
                : '다음 영상을 불러올 수 없습니다.',
        );
    }
}

// ---- 재생 위치 표시 ----

// 재생 위치가 바뀔 때마다 시계, 시간창 이탈 점검, 통계 패널, 재생 위치 막대, 이벤트 이동 버튼을 갱신한다. 위치를 알 수 없으면 숨긴다.
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
// 재생이 시간축 범위를 벗어나면 현재 위치를 가운데로 해서 시간축을 다시 읽는다. 드래그 중이거나 이미 다시 읽는 중이면 건너뛴다.
function recenterIfLeftWindow(ms) {
    if (
        !recording ||
        !timeWindow ||
        recentering ||
        dragging ||
        (ms >= timeWindow.from && ms <= timeWindow.to)
    ) {
        return;
    }
    recentering = true;
    loadTimeline(ms).finally(() => {
        recentering = false;
    });
}

// 주소의 at을 지금 찾은 시각으로 맞춰 새로고침해도 같은 자리에서 열리게 한다(재생 중 매번 바꾸지는 않는다)
// 주소의 at을 현재 위치로 바꿔 새로고침·링크 공유에도 같은 위치가 열리게 한다. replaceState라 뒤로가기 기록은 쌓이지 않는다.
function syncAddress(ms) {
    const next = new URL(location.href);
    next.searchParams.set('at', toKstIso(ms));
    history.replaceState(null, '', next);
}

// 시간축 위 재생 위치 막대를 비율(%)로 놓고 말풍선(시각·인원·단계)을 만든다. 범위 밖이면 숨긴다. preview=true는 드래그 중 미리 보기로, 통계 패널도 함께 갱신한다.
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

// 재생 위치가 속한 1분의 통계(인원·밀도·범위·단계·유입·유출)와 이벤트 안내를 패널에 보인다. 같은 분·이벤트면 다시 그리지 않는다. 통계가 없는 분은 '-'를 보인다.
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
    document.getElementById('pb-stat-count').textContent = item
        ? `${formatNumber(Math.round(item.countAvg))}명`
        : '-';
    document.getElementById('pb-stat-density').textContent = item
        ? formatDecimal(item.density, 2)
        : '-';
    document.getElementById('pb-stat-range').textContent = item
        ? `${formatNumber(Math.round(item.countMax))} · ${formatNumber(Math.round(item.countMin))}`
        : '-';
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
    document.getElementById('pb-stat-in').textContent = item
        ? `+${formatNumber(item.inflow)}`
        : '-';
    document.getElementById('pb-stat-out').textContent = item
        ? `−${formatNumber(item.outflow)}`
        : '-';

    // 위치가 이벤트 구간 안일 때만 이벤트 안내 상자를 보이고 상세로 가는 링크를 단다.
    const box = document.getElementById('pb-event-box');
    box.hidden = event === null;
    if (event) {
        document.getElementById('pb-event-chip').textContent =
            EVENT_TYPE_LABELS[event.type] ?? '혼잡';
        document.getElementById('pb-event-text').textContent =
            `${formatHm(event.startedAt)} ~ ${event.endedAt ? formatHm(event.endedAt) : '진행 중'} 이벤트 안`;
        // 이벤트 상세 창(SCR-A-011)은 이벤트 로그 화면 안에 있어, ?eventId= 로 그 이벤트의 상세 창을 바로 연다
        document.getElementById('pb-event-link').href = url(`/events?eventId=${event.id}`);
    }
}

// 시간축에는 혼잡·급증 이벤트만 표시한다(PM 결정). 캠 이상은 요청에서 뺀다
// 시간축 요청에 쓰는 이벤트 종류(혼잡, 급증)와 화면 이름 사전.
const PLAYBACK_EVENT_TYPES = 'CONGESTION,SURGE';
const EVENT_TYPE_LABELS = { CONGESTION: '혼잡', SURGE: '급증', CAM_FAULT: '캠 이상' };

// 이전·다음 이벤트 버튼은 시간축의 ▼와 같은 기준(범위 안에서 시작한 이벤트)만 대상으로 한다. 범위 앞에서 시작해 이어진 이벤트는
// 구간만 그려지고 ▼가 없어, 거기로 가면 시간축 밖으로 나간다(FNC-A-047)
// 이전·다음 이벤트 버튼이 이동할 대상 목록. 시간축 안에서 시작한 이벤트로 한정한다.
function buttonEvents() {
    return timeWindow ? eventsStartedIn(events, timeWindow.from, timeWindow.to) : events;
}

// 이동할 이전·다음 이벤트가 없으면 해당 버튼을 끈다.
function updateEventButtons(ms) {
    prevEventButton.disabled = previousEvent(buttonEvents(), ms) === null;
    nextEventButton.disabled = nextEvent(buttonEvents(), ms) === null;
}

// ---- 통계 시간축 ----

// 시간축 범위의 이벤트를 100건씩 끝까지 이어서 읽는다(이전엔 100건만 읽어 뒤쪽이 빠졌다, #198). overlap=true라 범위 앞에서 시작해
// 범위 안에서 이어진 이벤트도 온다. 반복이 끝없이 돌지 않게 상한(20쪽=2000건)을 둔다
// 이벤트를 한 번에 100건씩 받고 최대 20쪽까지만 따라가 무한 반복을 막는다.
const EVENT_PAGE_SIZE = 100;
const MAX_EVENT_PAGES = 20;

// 이 함수는 거부되지 않는다. 중간 쪽이 실패하면 그때까지 받은 이벤트는 돌려주고 failed=true로 알린다. 범위를 바꿔 이 요청이 낡아지면
// (timelineRequest가 바뀜) 남은 쪽을 요청하지 않고 멈춘다(stale=true)
// 시간축 범위의 이벤트를 모든 쪽에 걸쳐 모은다. overlap으로 범위 앞에서 시작해 이어진 이벤트도 받는다. 실패나 낡은 요청 여부를 예외 대신 반환 값(failed, stale)으로 알려 호출한 쪽이 받은 만큼 그릴 수 있게 한다.
async function loadAllEvents(query, request) {
    const items = [];
    for (let page = 0; page < MAX_EVENT_PAGES; page++) {
        let response;
        try {
            response = await get(
                '/api/v1/events',
                {
                    zoneId,
                    ...query,
                    types: PLAYBACK_EVENT_TYPES,
                    overlap: true,
                    page,
                    size: EVENT_PAGE_SIZE,
                },
                { toast: false },
            );
        } catch {
            return { items, failed: true, stale: request !== timelineRequest };
        }
        if (request !== timelineRequest) {
            return { items, failed: false, stale: true };
        }
        items.push(...(response?.items ?? []));
        // 마지막 쪽까지 받았으면 반복을 끝낸다.
        if (page + 1 >= (response?.totalPages ?? 1)) {
            break;
        }
    }
    return { items, failed: false, stale: false };
}

// center를 가운데로 한 시간축 범위를 정하고 통계·이벤트·녹화 구간을 동시에 읽어 그래프를 다시 그린다.
async function loadTimeline(center) {
    const request = ++timelineRequest;
    timeWindow = windowFor(range, center);
    const query = { from: toKstIso(timeWindow.from), to: toKstIso(timeWindow.to) };
    // allSettled를 써서 세 요청 중 일부가 실패해도 나머지로 화면을 그린다.
    const [seriesResult, eventsResult, rangesResult] = await Promise.allSettled([
        get(
            '/api/v1/stats/series',
            { targetType: 'ZONE', targetId: zoneId, interval: '1m', ...query },
            { toast: false },
        ),
        loadAllEvents(query, request),
        get('/api/v1/playback/ranges', { zoneId, ...query }, { toast: false }),
    ]);
    // 그 사이 범위가 또 바뀌었으면 이 결과는 버린다.
    if (request !== timelineRequest) {
        return;
    }
    // 통계를 못 받으면 빈 목록(그래프에 '데이터 없음'), 녹화 구간을 못 받으면 null(띠를 숨김).
    series =
        seriesResult.status === 'fulfilled' ? normalizeSeries(seriesResult.value?.items ?? []) : [];
    // 이벤트는 받은 만큼 그린다. 일부(또는 전부)를 못 받았으면 안내한다(그냥 '이벤트 없음'처럼 보이지 않게)
    const loaded =
        eventsResult.status === 'fulfilled' ? eventsResult.value : { items: [], failed: true };
    events = normalizeEvents(loaded.items);
    if (loaded.failed) {
        showToast('일부 이벤트를 불러오지 못했습니다.');
    }
    recordingRanges =
        rangesResult.status === 'fulfilled'
            ? normalizeRanges(rangesResult.value?.ranges ?? [])
            : null;
    // 통계가 바뀌었으니 통계 패널이 새로 그려지도록 마지막 표시 기록을 지운다.
    shownMinute = null;
    renderTimeline();
    updatePlayhead();
}

// 시간축 그래프 전체를 그린다: 영역(최소~최대 띠), 선, 경계선, 이벤트 구간·시작 표시, 단계 색 띠, 녹화 띠, 시각 눈금.
function renderTimeline() {
    const { from, to } = timeWindow;
    const shape = buildChart(series, metric, from, to, CHART_WIDTH, CHART_HEIGHT, thresholds());
    document
        .getElementById('pb-band')
        .setAttribute('d', shape.band ? `${toPath(shape.band)} Z` : '');
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
        // 진행 중인 이벤트는 범위 끝까지로 그리고, 범위 밖 이벤트는 건너뛴다. 범위 앞에서 시작한 이벤트는 구간만 그리고 시작 표시(▼)는 범위 안에서 시작한 것에만 단다.
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

    // 단계 색 띠, 녹화 띠, 시간 눈금(5등분)을 그린다.
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
// 주의·위험 경계선을 수평 선으로, 이름을 왼쪽 라벨로 그린다.
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

// 분마다 혼잡 단계 색 띠. 통계가 없는 분과 영상(녹화)이 없는 분은 비워 둔다(#296). 녹화 구간을 못 받았으면 통계만 본다
// 1분마다 한 칸씩 단계 색을 칠한다. 통계가 없거나 녹화가 없는 분은 칠하지 않는다(#296).
function renderLevelStrip(from, to) {
    const byMinute = new Map(series.map((item) => [item.at, item]));
    const cells = [];
    for (let at = minuteStart(from); at < to; at += 60 * 1000) {
        const cell = document.createElement('span');
        const item = byMinute.get(at);
        if (item && LEVEL_LABELS[item.level] && recordedInMinute(recordingRanges, at)) {
            cell.className = `l${item.level}`;
        }
        cells.push(cell);
    }
    document.getElementById('pb-level-strip').replaceChildren(...cells);
}

// 녹화가 있는 구간 띠(FNC-A-043 구간 조회). 못 읽었으면 없음/있음을 알 수 없으므로 띠를 숨긴다
// 녹화가 있는 구간을 시간축 비율로 띠에 그린다. 구간 정보를 못 받았으면 띠와 범례를 숨긴다.
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

// 그래프를 눌러 재생 위치를 옮기는 중인지.
let dragging = false;

// 포인터 x 좌표를 그래프 폭에 대한 비율로 바꿔 시각으로 환산한다.
function chartTime(event) {
    const rect = chart.getBoundingClientRect();
    return timeAt((event.clientX - rect.left) / rect.width, timeWindow.from, timeWindow.to);
}

// 그래프 드래그 조작: 누르면 시작해 미리 보기만 하고, 떼는 순간 그 시각으로 실제 이동(seekToTime)한다. 포인터 캡처로 그래프 밖으로 나가도 이벤트를 받는다. 취소되면 원래 위치로 되돌린다.
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

// 재생 중에는 다음 녹화 미리 받기와 재생 위치 표시를 갱신한다. 드래그 중에는 위치가 흔들리지 않게 건너뛴다.
video.addEventListener('timeupdate', () => {
    prefetchNext();
    if (!dragging) {
        updatePlayhead();
    }
});
// 영상이 끝나면 다음 녹화로 이어 틀기를 시도한다.
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

// 재생 상태에 따라 버튼 아이콘과 접근성 라벨을 바꾼다.
function togglePlayIcon(playing) {
    toggleIcon.setAttribute('d', playing ? PAUSE_PATH : PLAY_PATH);
    toggleButton.setAttribute('aria-label', playing ? '일시 정지' : '재생');
}

// 재생/일시 정지 토글.
toggleButton.addEventListener('click', () => {
    if (video.paused) {
        video.play().catch(() => {});
    } else {
        video.pause();
    }
});

// 선택된(on) 속도 버튼의 data-rate 값을 숫자로 읽는다.
function currentRate() {
    return Number(document.querySelector('#pb-speed button.on').dataset.rate);
}

// 속도 버튼: 누른 버튼만 on 표시를 하고 재생 속도를 바꾼다.
document.querySelectorAll('#pb-speed button').forEach((button) => {
    button.addEventListener('click', () => {
        document
            .querySelectorAll('#pb-speed button')
            .forEach((other) => other.classList.toggle('on', other === button));
        video.playbackRate = Number(button.dataset.rate);
    });
});

// 이전·다음 이벤트 버튼: 가장 가까운 이벤트 시작 시각으로 이동한다.
prevEventButton.addEventListener('click', () => {
    const target = previousEvent(buttonEvents(), playheadMs());
    if (target) {
        seekToTime(target.startedAt);
    }
});
nextEventButton.addEventListener('click', () => {
    const target = nextEvent(buttonEvents(), playheadMs());
    if (target) {
        seekToTime(target.startedAt);
    }
});

// 시간축 범위 버튼: 선택을 바꾸고 현재 재생 위치(없으면 지금)를 가운데로 시간축을 다시 읽는다.
document.querySelectorAll('#pb-range button').forEach((button) => {
    button.addEventListener('click', () => {
        document
            .querySelectorAll('#pb-range button')
            .forEach((other) => other.classList.toggle('on', other === button));
        range = button.dataset.range;
        loadTimeline(playheadMs() ?? Date.now());
    });
});
// 그래프 항목 버튼: 이미 받은 통계로 다시 그리기만 하고 서버에 다시 요청하지 않는다.
document.querySelectorAll('#pb-metric button').forEach((button) => {
    button.addEventListener('click', () => {
        document
            .querySelectorAll('#pb-metric button')
            .forEach((other) => other.classList.toggle('on', other === button));
        metric = button.dataset.metric;
        if (timeWindow) {
            renderTimeline();
            updatePlayhead();
        }
    });
});
