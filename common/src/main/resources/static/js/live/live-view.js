// 실시간 보기 창(SCR-A-008, FNC-A-041·042). 대시보드 지도 핀·캠 카드와 캠 관리의 '실시간 보기'에서 연다.
// 영상은 nginx가 권한(FNC-A-041)을 확인한 뒤 분석 서버의 MJPEG 스트림을 그대로 전달한다(/admin/live/{cameraId}).
// 창 안의 '현재 통계'는 통계 SSE(FNC-C-022)로 갱신한 캠 정보를 받아 바꾼다. 서버 값은 textContent로만 넣는다.
// 영상 아래 그래프 2행 2열(결정 0016)은 live-graphs.js가 그리고, 창을 열 때와 캠 정보가 새로 읽힐 때(새 1분 통계) 다시 읽는다.
import { ApiError, get, url } from '../common/api.js';
import { formatDecimal, formatNumber } from '../common/format.js';
import { showToast } from '../common/toast.js';
import { clearLiveGraphs, loadLiveGraphs } from './live-graphs.js';

const PLAYBACK_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
const LEVEL_LABELS = { 1: '정상', 2: '주의', 3: '위험' };

let modal = null;
let current = null; // 지금 열려 있는 캠 정보

/** 창을 한 번 준비한다. 창 마크업(layout.html adminLiveModal)이 없는 화면에서는 아무것도 하지 않는다. */
export function initLiveView() {
    modal = document.getElementById('live-modal');
    if (!modal) {
        return;
    }
    const img = byId('live-img');
    modal.querySelectorAll('[data-live-close]').forEach((button) => button.addEventListener('click', closeLiveView));
    modal.addEventListener('mousedown', (event) => {
        if (event.target === modal) {
            closeLiveView();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !modal.hidden) {
            closeLiveView();
        }
    });
    img.addEventListener('load', () => showNotice(''));
    img.addEventListener('error', () => {
        if (img.getAttribute('src')) {
            showNotice('영상을 불러올 수 없습니다', true);
        }
    });
    byId('live-retry').addEventListener('click', connect);
    byId('live-playback').addEventListener('click', openPlayback);
}

/** 캠 정보(GET /api/v1/manage/cameras 항목)로 창을 열고 영상을 연결한다. */
export function openLiveView(camera) {
    if (!modal) {
        return;
    }
    current = camera;
    byId('live-cam').textContent = `CAM ${camera.id}`;
    byId('live-name').textContent = camera.name;
    byId('live-place').textContent = `· ${camera.regionPath} · ${camera.zoneName} · ${formatNumber(Math.round(camera.areaM2))}㎡`;
    renderStat(camera);
    modal.hidden = false;
    connect();
    // 이전 캠의 그래프가 응답 전까지 남지 않게 비운 뒤 읽는다
    clearLiveGraphs();
    loadLiveGraphs(camera);
}

/** 캠 정보가 새로 읽혔을 때 열려 있는 창의 현재 통계만 바꾼다(영상은 다시 연결하지 않는다). */
export function updateLiveView(cameras) {
    if (!modal || modal.hidden || !current) {
        return;
    }
    const latest = cameras.find((camera) => camera.id === current.id);
    if (latest) {
        current = latest;
        renderStat(latest);
        loadLiveGraphs(latest);
    }
}

export function closeLiveView() {
    if (!modal || modal.hidden) {
        return;
    }
    modal.hidden = true;
    current = null;
    clearLiveGraphs();
    // 이미지 요청을 끊어 분석 서버·nginx의 스트림 연결을 닫는다
    const img = byId('live-img');
    img.removeAttribute('src');
}

function connect() {
    if (!current) {
        return;
    }
    const img = byId('live-img');
    showNotice('영상을 불러오는 중…');
    img.hidden = false;
    // 다시 시도할 때도 새 연결이 되도록 주소 뒤에 값을 붙인다(nginx는 경로만 본다)
    img.src = `${url(`/live/${current.id}`)}?t=${Date.now()}`;
}

function showNotice(message, withRetry = false) {
    byId('live-notice').hidden = message === '';
    byId('live-notice-text').textContent = message;
    byId('live-retry').hidden = !withRetry;
}

function renderStat(camera) {
    const stat = camera.stat;
    byId('live-count').textContent = stat ? `${formatNumber(Math.round(stat.countAvg))}명` : '-';
    byId('live-density').textContent = stat ? formatDecimal(stat.density, 2) : '-';
    const chip = byId('live-level');
    chip.className = 'chip';
    if (stat && LEVEL_LABELS[stat.level]) {
        chip.classList.add(`l${stat.level}`);
        chip.textContent = LEVEL_LABELS[stat.level];
        chip.hidden = false;
    } else {
        chip.hidden = true;
    }
    byId('live-level-none').hidden = !chip.hidden;
    byId('live-count-badge').textContent = stat ? `${formatNumber(Math.round(stat.countAvg))}명` : '';
}

// 가장 최근 녹화를 찾아 다시보기 화면으로 간다(FNC-A-043·047). 없으면 토스트
async function openPlayback() {
    if (!current) {
        return;
    }
    try {
        const found = await get('/api/v1/playback', { zoneId: current.zoneId, latest: true }, { toast: false });
        window.location.href = `${url('/playback')}?zoneId=${current.zoneId}&at=${encodeURIComponent(found.startedAt)}`;
    } catch (error) {
        if (error instanceof ApiError && error.status !== 401) {
            showToast(PLAYBACK_MESSAGES[error.code] ?? error.message);
        }
    }
}

function byId(id) {
    return document.getElementById(id);
}
