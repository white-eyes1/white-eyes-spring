// 이벤트 상세 창 SCR-A-011의 발생 시점 영상(FNC-A-040).
// 창을 열면 이벤트 발생 시각의 녹화를 찾아(FNC-A-043) 창 안에서 재생하고 탐지 박스를 그린다(FNC-A-046).
// 영상 아래에는 발생 앞뒤 30분의 1분 평균 인원 띠와 이벤트 구간을 보이고, 푸터의 링크로 다시보기 화면을 연다(FNC-A-047).
// 캠 이상 이벤트와 보관 기간(RECORDING_RETENTION_DAYS)을 지난 이벤트는 영상 영역이 없다. 서버 값은 textContent로만 넣는다.

// 공용 API 모듈(get 요청, ApiError 예외, url: 컨텍스트 경로를 붙이는 함수)과, 다시보기 화면과 함께 쓰는 순수 계산 모듈(model.js)·박스 계산 모듈(boxes.js)을 가져온다.
import { ApiError, get, url } from '../common/api.js';
import {
    buildChart,
    formatClock,
    formatHm,
    fraction,
    normalizeSeries,
    toKstIso,
    toPath,
    windowFor,
} from './playback/model.js';
import { containRect, findFrame, parseFrames, toPixels } from './playback/boxes.js';

// 서버 오류 코드별 영상 영역 안내 문구. 목록에 없는 오류는 NOTICE_FAILED로 보인다.
const NOTICE_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
const NOTICE_LOADING = '불러오는 중…';
const NOTICE_FAILED = '영상을 불러올 수 없습니다.';
// 탐지 박스 선 색과, 인원 띠 SVG의 가상 좌표 크기(실제 크기는 CSS가 늘린다).
const BOX_COLOR = '#5ee0b5';
const CHART_WIDTH = 600;
const CHART_HEIGHT = 34;
// 인원 띠는 발생 시각 앞뒤 30분 = 1시간 범위(FNC-A-040)
const CHART_RANGE = '1h';

// 상세 창 안의 영상 영역 요소들(영상, 박스를 그릴 캔버스, 안내 문구, 인원 띠 등)을 id로 한 번만 찾아 둔다.
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
// 비동기 응답이 늦게 도착해도 지금 열린 이벤트의 것인지 이 번호로 가려낸다(창을 열고 닫을 때마다 값이 바뀜).
let openSeq = 0;
// 지금 녹화의 좌표 파일 프레임(t 순서). 없거나 못 받았으면 null
let boxFrames = null;
let boxLoopArmed = false;

/** 상세 응답(GET /api/v1/events/{id})으로 영상 영역과 '다시보기 화면에서 크게 보기' 링크를 채운다. */
// 상세 창을 열 때 호출된다. 이전 영상 상태를 먼저 정리한 뒤 영상과 인원 띠를 각각 비동기로 불러온다.
export function openEventVideo(detail) {
    // 먼저 닫기 처리를 하면 openSeq가 올라가므로, 이 직후 읽은 seq가 이번 열기의 번호가 된다.
    closeEventVideo();
    const seq = openSeq;
    // 영상 영역이 없어도 링크 상태(캠 이상은 숨김, 보관 만료는 비활성)는 맞춰 둔다.
    showLink(detail);
    // 캠 이상이거나 보관 기간을 지났으면 영상 영역 자체를 두지 않는다(서버가 playbackAvailable로 알려 준다)
    section.hidden = !detail.playbackAvailable;
    if (!detail.playbackAvailable) {
        return;
    }
    // 발생 시각 문자열을 밀리초 숫자로 바꿔 아래 조회·계산에 쓴다.
    const startedAt = Date.parse(detail.startedAt);
    showNotice(NOTICE_LOADING);
    // 영상과 인원 띠는 서로 기다리지 않도록 await 없이 동시에 시작한다.
    loadVideo(detail.zoneId, startedAt, seq);
    loadChart(detail, startedAt, seq);
}

/** 창을 닫을 때: 재생을 멈추고 영상·박스·띠를 비운다. */
// 창을 닫을 때: 진행 중인 응답을 무효화하고 영상·박스·띠를 비운다.
export function closeEventVideo() {
    // 번호를 올려 아직 도착하지 않은 응답이 화면을 다시 채우지 못하게 한다.
    openSeq++;
    video.pause();
    video.hidden = true;
    // src를 지우고 load()를 불러야 브라우저가 내려받던 영상을 중단한다.
    video.removeAttribute('src');
    video.load();
    // 좌표를 비우고 drawBoxes로 캔버스를 지운다.
    boxFrames = null;
    drawBoxes();
    boxNote.hidden = true;
    feedLabel.hidden = true;
    resetChart();
}

// 캠 이상은 버튼이 없고, 보관 기간을 지난 이벤트는 버튼이 비활성이다
// '다시보기 화면에서 크게 보기' 링크를 이벤트에 맞게 만든다. 구역 id와 발생 시각을 쿼리로 넘겨 다시보기 화면이 그 시점을 열게 한다.
function showLink(detail) {
    playbackLink.hidden = detail.type === 'CAM_FAULT';
    if (detail.playbackAvailable) {
        // 쿼리는 URLSearchParams로 만들어 값이 자동으로 인코딩되게 한다.
        const query = new URLSearchParams({ zoneId: String(detail.zoneId), at: detail.startedAt });
        playbackLink.href = url(`/playback?${query.toString()}`);
        playbackLink.removeAttribute('aria-disabled');
    // 보관 기간을 지났으면 링크를 걸지 않고 aria-disabled로 비활성임을 접근성 도구에도 알린다.
    } else {
        playbackLink.removeAttribute('href');
        playbackLink.setAttribute('aria-disabled', 'true');
    }
}

// 영상 대신 보일 안내 문구(불러오는 중, 영상 없음 등)를 표시한다.
function showNotice(message) {
    notice.textContent = message;
    notice.hidden = false;
}

// ---- 영상(FNC-A-043)·박스(FNC-A-046) ----

// 발생 시각의 녹화를 찾아 재생한다(FNC-A-043). seq는 이 요청을 시작한 열기 번호다.
async function loadVideo(zoneId, startedAt, seq) {
    try {
        // 구역 id와 시각(KST ISO 형식)으로 녹화를 조회한다. 응답에는 영상 주소, 재생 시작 위치(seekSec), 좌표 파일 주소가 들어 있다. 오류 알림은 이 창의 안내 문구로 보이므로 토스트는 끈다.
        const found = await get(
            '/api/v1/playback',
            { zoneId, at: toKstIso(startedAt) },
            { toast: false },
        );
        // 응답을 기다리는 사이 창이 닫혔거나 다른 이벤트가 열렸으면 결과를 버린다.
        if (seq !== openSeq) {
            return;
        }
        notice.hidden = true;
        video.hidden = false;
        feedLabel.hidden = false;
        feedLabel.textContent = `녹화 · ${formatClock(startedAt)}`;
        // 메타데이터가 로드된 뒤에야 currentTime을 옮길 수 있다. 한 번만 실행하도록 once를 쓴다. 발생 시각이 녹화 중간이므로 그 위치로 건너뛰어 재생한다.
        video.addEventListener(
            'loadedmetadata',
            () => {
                video.currentTime = found.seekSec;
                // 브라우저의 자동 재생 차단은 오류가 아니므로 삼킨다.
                video.play().catch(() => {
                    // 자동 재생이 막혀도 영상 아래 컨트롤로 시작할 수 있다
                });
            },
            { once: true },
        );
        // src를 마지막에 지정해 위의 리스너가 먼저 등록된 상태에서 로드가 시작되게 한다.
        video.src = found.videoUrl;
        loadBoxes(found.boxesUrl, seq);
    // 녹화 없음·보관 만료는 서버 오류 코드에 맞는 문구, 그 밖의 실패는 일반 문구를 보인다. 닫힌 뒤의 늦은 오류는 무시한다.
    } catch (error) {
        if (seq === openSeq) {
            showNotice(
                error instanceof ApiError && NOTICE_MESSAGES[error.code]
                    ? NOTICE_MESSAGES[error.code]
                    : NOTICE_FAILED,
            );
        }
    }
}

// boxesUrl은 서버가 컨텍스트 경로(/admin)까지 붙여 준 주소라 absolute로 그대로 요청한다.
// 좌표 파일을 못 받으면 영상만 재생하고 '박스 정보 없음'을 보인다
// 좌표 파일을 받아 프레임 목록으로 만들고 그리기를 시작한다. 파일이 없거나 형식이 틀리면 null로 두어 영상만 재생한다.
async function loadBoxes(boxesUrl, seq) {
    let frames = null;
    try {
        // absolute 옵션: 이미 컨텍스트 경로까지 붙은 주소이므로 공용 모듈이 접두사를 또 붙이지 않게 한다.
        frames = parseFrames(await get(boxesUrl, undefined, { absolute: true, toast: false }));
    } catch {
        frames = null;
    }
    if (seq !== openSeq) {
        return;
    }
    // 받은 좌표를 보관하고 '박스 정보 없음' 안내를 켜거나 끈 뒤 처음 한 번 그린다.
    boxFrames = frames;
    boxNote.hidden = frames !== null;
    drawBoxes();
    armBoxLoop();
}

// 재생 위치에서 t ≤ 위치인 마지막 프레임의 박스를 그린다. 영상은 칸 안에 비율을 지켜 그려지므로 영상 크기 기준 영역에 맞춘다
// 현재 재생 위치의 박스를 캔버스에 그린다.
function drawBoxes() {
    const context = boxCanvas.getContext('2d');
    // 고해상도 화면(devicePixelRatio)에서 선이 흐려지지 않도록 캔버스 내부 해상도를 CSS 크기에 배율을 곱해 맞춘다.
    const ratio = window.devicePixelRatio || 1;
    const width = boxCanvas.clientWidth;
    const height = boxCanvas.clientHeight;
    // 크기가 바뀐 경우에만 width/height를 다시 쓴다. 이 속성을 쓰면 캔버스가 초기화되므로 매번 쓰지 않는다.
    if (
        boxCanvas.width !== Math.round(width * ratio) ||
        boxCanvas.height !== Math.round(height * ratio)
    ) {
        boxCanvas.width = Math.round(width * ratio);
        boxCanvas.height = Math.round(height * ratio);
    }
    // 이후 좌표를 CSS 픽셀 단위로 쓰기 위한 배율 설정과 이전 그림 지우기.
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    // 좌표가 없거나 영상이 안 보이면 지운 상태로 끝낸다.
    if (boxFrames === null || video.hidden) {
        return;
    }
    // 영상은 칸 안에 비율을 유지해 그려지므로 실제 영상이 차지하는 영역을 구해 그 기준으로 박스를 맞춘다. 영상 크기를 아직 모르면 null.
    const area = containRect(width, height, video.videoWidth, video.videoHeight);
    // 현재 재생 위치(초) 이전의 마지막 프레임. 좌표는 일정 간격으로만 있어서 다음 프레임이 올 때까지 이전 박스를 유지한다.
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
// 영상 프레임마다 다시 그리는 반복을 한 번만 걸어 둔다. 재생 중 박스가 영상과 어긋나지 않게 하려는 것이다.
function armBoxLoop() {
    // 이미 반복이 걸려 있거나 그릴 좌표가 없으면 새로 걸지 않는다.
    if (boxLoopArmed || boxFrames === null) {
        return;
    }
    boxLoopArmed = true;
    // 콜백이 실행되면 표시를 풀고 그린 뒤 다시 다음 콜백을 건다(자기 자신을 이어 붙이는 반복).
    const tick = () => {
        boxLoopArmed = false;
        drawBoxes();
        armBoxLoop();
    };
    // 영상 프레임에 정확히 맞추는 API가 있으면 그것을, 없는 브라우저에서는 화면 갱신 주기(requestAnimationFrame)를 쓴다.
    if ('requestVideoFrameCallback' in video) {
        video.requestVideoFrameCallback(tick);
    } else {
        window.requestAnimationFrame(tick);
    }
}

// 일시 정지·구간 이동·창 크기 변경에도 위치가 맞도록 다시 그린다
// 일시 정지나 구간 이동처럼 프레임 콜백이 오지 않는 상황에서도 박스를 현재 위치에 맞춰 다시 그린다.
['seeked', 'loadedmetadata', 'pause', 'play'].forEach((name) =>
    video.addEventListener(name, drawBoxes),
);
// 영상 칸 크기가 바뀌면 캔버스 크기와 박스 위치를 다시 계산한다.
new ResizeObserver(drawBoxes).observe(document.getElementById('detail-video-feed'));
video.addEventListener('error', () => {
    // 창을 닫으며 src를 비울 때도 오류 이벤트가 오므로 열려 있는 영상일 때만 안내한다
    if (!video.hidden) {
        video.hidden = true;
        showNotice(NOTICE_FAILED);
    }
});

// ---- 발생 앞뒤 30분 인원 띠(GET /api/v1/stats/series) ----

// 발생 앞뒤 30분의 1분 평균 인원 띠를 그린다. 시간 범위 계산(windowFor)과 차트 모양 계산(buildChart)은 다시보기 화면과 같은 model.js를 쓴다.
async function loadChart(detail, startedAt, seq) {
    const { from, to } = windowFor(CHART_RANGE, startedAt);
    // 시간 축 라벨 세 개: 시작, 발생 시각, 끝.
    axis.replaceChildren(
        axisLabel(formatHm(from)),
        axisLabel(`발생 ${formatHm(startedAt)}`),
        axisLabel(formatHm(to)),
    );
    // 이벤트 구간과 발생 시각 선은 통계 응답을 기다리지 않고 먼저 그린다.
    placeEvent(detail, startedAt, from, to);
    // 통계 조회에 실패해도 띠 영역은 비어 있는 채로 두면 되므로 빈 목록으로 대체한다.
    let items = [];
    try {
        const result = await get(
            '/api/v1/stats/series',
            {
                targetType: 'ZONE',
                targetId: detail.zoneId,
                interval: '1m',
                from: toKstIso(from),
                to: toKstIso(to),
            },
            { toast: false },
        );
        items = normalizeSeries(result?.items ?? []);
    } catch {
        items = [];
    }
    if (seq !== openSeq) {
        return;
    }
    // 데이터를 SVG 경로 모양으로 바꾼다(count: 인원 수 기준). 끊긴 구간은 여러 조각(segments)으로 나뉘므로 공백으로 이어 붙인다.
    const shape = buildChart(items, 'count', from, to, CHART_WIDTH, CHART_HEIGHT);
    chartLine.setAttribute('d', shape.lines[0].segments.map(toPath).join(' '));
    // 데이터가 하나도 없으면 '데이터 없음' 안내를 보인다.
    chartEmpty.hidden = items.length > 0;
}

// 이벤트 구간(붉은 영역)과 발생 시각 표시선. 진행 중이면 띠 끝까지, 띠 범위를 넘으면 자른다
// 띠 안에서 이벤트가 차지하는 구간을 비율(%)로 계산해 CSS 위치로 지정한다.
function placeEvent(detail, startedAt, from, to) {
    // 종료 시각이 없으면 진행 중인 이벤트이므로 띠 끝까지로 본다.
    const endedAt = detail.endedAt ? Date.parse(detail.endedAt) : to;
    const start = fraction(startedAt, from, to);
    chartRange.style.left = `${start * 100}%`;
    chartRange.style.width = `${(fraction(endedAt, from, to) - start) * 100}%`;
    chartMark.style.left = `${start * 100}%`;
}

// 띠를 비운다(경로, 이벤트 구간, 안내, 시간 축).
function resetChart() {
    chartLine.setAttribute('d', '');
    chartRange.style.width = '0';
    chartEmpty.hidden = true;
    axis.replaceChildren();
}

// 시간 축 라벨 한 칸을 만든다. 텍스트는 textContent로 넣는다.
function axisLabel(text) {
    const label = document.createElement('span');
    label.textContent = text;
    return label;
}
