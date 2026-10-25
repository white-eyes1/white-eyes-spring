// 이벤트 상세 창 SCR-A-011의 발생 시점 영상(FNC-A-040).
// 창을 열면 이벤트 발생 시각의 녹화를 찾아(FNC-A-043) 창 안에서 재생하고 탐지 박스를 그린다(FNC-A-046).
// 영상 아래에는 발생 앞뒤 30분의 1분 평균 인원 띠와 이벤트 구간을 보이고, 푸터의 링크로 다시보기 화면을 연다(FNC-A-047).
// 캠 이상 이벤트와 보관 기간(RECORDING_RETENTION_DAYS)을 지난 이벤트는 영상 영역이 없다. 서버 값은 textContent로만 넣는다.

import { ApiError, get, url } from '../common/api.js';
import { buildChart, formatClock, formatHm, fraction, normalizeSeries, toKstIso, toPath, windowFor } from './playback/model.js';
import { containRect, findFrame, parseFrames, toPixels } from './playback/boxes.js';

const NOTICE_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
const NOTICE_LOADING = '불러오는 중…';
const NOTICE_FAILED = '영상을 불러올 수 없습니다.';
const BOX_COLOR = '#5ee0b5';
const CHART_WIDTH = 600;
const CHART_HEIGHT = 34;
// 인원 띠는 발생 시각 앞뒤 30분 = 1시간 범위(FNC-A-040)
const CHART_RANGE = '1h';

const section = document.getElementById('detail-video');
const video = document.getElementById('detail-video-player');
const boxCanvas = document.getElementById('detail-video-boxes');
const notice = document.getElementById('detail-video-notice');
const boxNote = document.getElementById('detail-video-box-note');
const feedLabel = document.getElementById('detail-video-label');
const chartLine = document.getElementById('detail-video-line');
const chartRange = document.getElementById('detail-video-range');
const chartMark = document.getElementById('detail-video-mark');
const chartEmpty = document.getElementById('detail-video-chart-empty');
const axis = document.getElementById('detail-video-axis');
const playbackLink = document.getElementById('detail-open-playback');

// 열 때마다 올라가는 번호. 창을 닫았거나 다른 이벤트를 연 뒤에 도착한 응답을 버리는 데 쓴다
let openSeq = 0;
// 지금 녹화의 좌표 파일 프레임(t 순서). 없거나 못 받았으면 null
let boxFrames = null;
let boxLoopArmed = false;

/** 상세 응답(GET /api/v1/events/{id})으로 영상 영역과 '다시보기 화면에서 크게 보기' 링크를 채운다. */
export function openEventVideo(detail) {
    closeEventVideo();
    const seq = openSeq;
    showLink(detail);
    // 캠 이상이거나 보관 기간을 지났으면 영상 영역 자체를 두지 않는다(서버가 playbackAvailable로 알려 준다)
    section.hidden = !detail.playbackAvailable;
    if (!detail.playbackAvailable) {
        return;
    }
    const startedAt = Date.parse(detail.startedAt);
    showNotice(NOTICE_LOADING);
    loadVideo(detail.zoneId, startedAt, seq);
    loadChart(detail, startedAt, seq);
}

/** 창을 닫을 때: 재생을 멈추고 영상·박스·띠를 비운다. */
export function closeEventVideo() {
    openSeq++;
    video.pause();
    video.hidden = true;
    video.removeAttribute('src');
    video.load();
    boxFrames = null;
    drawBoxes();
    boxNote.hidden = true;
    feedLabel.hidden = true;
    resetChart();
}

// 캠 이상은 버튼이 없고, 보관 기간을 지난 이벤트는 버튼이 비활성이다
function showLink(detail) {
    playbackLink.hidden = detail.type === 'CAM_FAULT';
    if (detail.playbackAvailable) {
        const query = new URLSearchParams({ zoneId: String(detail.zoneId), at: detail.startedAt });
        playbackLink.href = url(`/playback?${query.toString()}`);
        playbackLink.removeAttribute('aria-disabled');
    } else {
        playbackLink.removeAttribute('href');
        playbackLink.setAttribute('aria-disabled', 'true');
    }
}

function showNotice(message) {
    notice.textContent = message;
    notice.hidden = false;
}

// ---- 영상(FNC-A-043)·박스(FNC-A-046) ----

async function loadVideo(zoneId, startedAt, seq) {
    try {
        const found = await get('/api/v1/playback', { zoneId, at: toKstIso(startedAt) }, { toast: false });
        if (seq !== openSeq) {
            return;
        }
        notice.hidden = true;
        video.hidden = false;
        feedLabel.hidden = false;
        feedLabel.textContent = `녹화 · ${formatClock(startedAt)}`;
        video.addEventListener(
            'loadedmetadata',
            () => {
                video.currentTime = found.seekSec;
                video.play().catch(() => {
                    // 자동 재생이 막혀도 영상 아래 컨트롤로 시작할 수 있다
                });
            },
            { once: true },
        );
        video.src = found.videoUrl;
        loadBoxes(found.boxesUrl, seq);
    } catch (error) {
        if (seq === openSeq) {
            showNotice(
                error instanceof ApiError && NOTICE_MESSAGES[error.code] ? NOTICE_MESSAGES[error.code] : NOTICE_FAILED,
            );
        }
    }
}

// boxesUrl은 서버가 컨텍스트 경로(/admin)까지 붙여 준 주소라 그대로 요청한다(공통 get()은 경로를 한 번 더 붙인다).
// 좌표 파일을 못 받으면 영상만 재생하고 '박스 정보 없음'을 보인다
async function loadBoxes(boxesUrl, seq) {
    let frames = null;
    try {
        const response = await fetch(boxesUrl, { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error(`좌표 파일 요청 실패 ${response.status}`);
        }
        frames = parseFrames(await response.json());
    } catch {
        frames = null;
    }
    if (seq !== openSeq) {
        return;
    }
    boxFrames = frames;
    boxNote.hidden = frames !== null;
    drawBoxes();
    armBoxLoop();
}

// 재생 위치에서 t ≤ 위치인 마지막 프레임의 박스를 그린다. 영상은 칸 안에 비율을 지켜 그려지므로 영상 크기 기준 영역에 맞춘다
function drawBoxes() {
    const context = boxCanvas.getContext('2d');
    const ratio = window.devicePixelRatio || 1;
    const width = boxCanvas.clientWidth;
    const height = boxCanvas.clientHeight;
    if (boxCanvas.width !== Math.round(width * ratio) || boxCanvas.height !== Math.round(height * ratio)) {
        boxCanvas.width = Math.round(width * ratio);
        boxCanvas.height = Math.round(height * ratio);
    }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    if (boxFrames === null || video.hidden) {
        return;
    }
    const area = containRect(width, height, video.videoWidth, video.videoHeight);
    const frame = findFrame(boxFrames, video.currentTime);
    if (!area || !frame) {
        return;
    }
    context.strokeStyle = BOX_COLOR;
    context.lineWidth = 1.5;
    toPixels(frame.boxes, area).forEach((box) => context.strokeRect(box.x, box.y, box.w, box.h));
}

// 영상 프레임이 화면에 올라갈 때마다 다시 그린다(requestVideoFrameCallback, 없으면 requestAnimationFrame).
// 그릴 것이 없으면(좌표 파일 없음·창 닫힘) 반복을 멈춘다
function armBoxLoop() {
    if (boxLoopArmed || boxFrames === null) {
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

// 일시 정지·구간 이동·창 크기 변경에도 위치가 맞도록 다시 그린다
['seeked', 'loadedmetadata', 'pause', 'play'].forEach((name) => video.addEventListener(name, drawBoxes));
new ResizeObserver(drawBoxes).observe(document.getElementById('detail-video-feed'));
video.addEventListener('error', () => {
    // 창을 닫으며 src를 비울 때도 오류 이벤트가 오므로 열려 있는 영상일 때만 안내한다
    if (!video.hidden) {
        video.hidden = true;
        showNotice(NOTICE_FAILED);
    }
});

// ---- 발생 앞뒤 30분 인원 띠(GET /api/v1/stats/series) ----

async function loadChart(detail, startedAt, seq) {
    const { from, to } = windowFor(CHART_RANGE, startedAt);
    axis.replaceChildren(axisLabel(formatHm(from)), axisLabel(`발생 ${formatHm(startedAt)}`), axisLabel(formatHm(to)));
    placeEvent(detail, startedAt, from, to);
    let items = [];
    try {
        const result = await get(
            '/api/v1/stats/series',
            { targetType: 'ZONE', targetId: detail.zoneId, interval: '1m', from: toKstIso(from), to: toKstIso(to) },
            { toast: false },
        );
        items = normalizeSeries(result?.items ?? []);
    } catch {
        items = [];
    }
    if (seq !== openSeq) {
        return;
    }
    const shape = buildChart(items, 'count', from, to, CHART_WIDTH, CHART_HEIGHT);
    chartLine.setAttribute('d', shape.lines[0].segments.map(toPath).join(' '));
    chartEmpty.hidden = items.length > 0;
}

// 이벤트 구간(붉은 영역)과 발생 시각 표시선. 진행 중이면 띠 끝까지, 띠 범위를 넘으면 자른다
function placeEvent(detail, startedAt, from, to) {
    const endedAt = detail.endedAt ? Date.parse(detail.endedAt) : to;
    const start = fraction(startedAt, from, to);
    chartRange.style.left = `${start * 100}%`;
    chartRange.style.width = `${(fraction(endedAt, from, to) - start) * 100}%`;
    chartMark.style.left = `${start * 100}%`;
}

function resetChart() {
    chartLine.setAttribute('d', '');
    chartRange.style.width = '0';
    chartEmpty.hidden = true;
    axis.replaceChildren();
}

function axisLabel(text) {
    const label = document.createElement('span');
    label.textContent = text;
    return label;
}
