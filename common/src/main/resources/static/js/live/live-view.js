// 실시간 보기 창(SCR-A-008, FNC-A-041·042). 대시보드 지도 핀·캠 카드와 캠 관리의 '실시간 보기'에서 연다.
// 영상은 nginx가 권한(FNC-A-041)을 확인한 뒤 분석 서버의 MJPEG 스트림을 그대로 전달한다(/admin/live/{cameraId}).
// 창 안의 '현재 통계'는 통계 SSE(FNC-C-022)로 갱신한 캠 정보를 받아 바꾼다. 서버 값은 textContent로만 넣는다.
// 영상 아래 그래프 2행 2열(결정 0016)은 live-graphs.js가 그리고, 창을 열 때와 캠 정보가 새로 읽힐 때(새 1분 통계) 다시 읽는다.
import { ApiError, get, url } from '../common/api.js';
import { formatDecimal, formatNumber } from '../common/format.js';
import { bindNowCount } from '../common/now-count.js';
import { showToast } from '../common/toast.js';
import { clearLiveGraphs, loadLiveGraphs } from './live-graphs.js';

// 녹화 다시보기 조회 실패 코드 -> 화면에 보일 짧은 문구
const PLAYBACK_MESSAGES = {
    RECORDING_NOT_FOUND: '영상 없음',
    RECORDING_EXPIRED: '보관 기간 지남',
};
// 혼잡 단계 번호 -> 이름
const LEVEL_LABELS = { 1: '정상', 2: '주의', 3: '위험' };

let modal = null;
let current = null; // 지금 열려 있는 캠 정보

/** 창을 한 번 준비한다. 창 마크업(layout.html adminLiveModal)이 없는 화면에서는 아무것도 하지 않는다. */
export function initLiveView() {
    // 창 요소. 창 마크업이 없는 화면에서는 null로 남는다
    modal = document.getElementById('live-modal');
    // 창 마크업이 없는 화면에서는 이후 함수들도 아무것도 하지 않는다
    if (!modal) {
        return;
    }
    // 영상은 img 태그로 MJPEG 스트림을 직접 받는다
    const img = byId('live-img');
    // 닫기 버튼, 창 바깥 클릭, Esc
    modal
        .querySelectorAll('[data-live-close]')
        .forEach((button) => button.addEventListener('click', closeLiveView));
    // 내용 영역이 아니라 배경을 눌렀을 때만 닫는다
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
    // 영상이 오기 시작하면 안내 문구를 지운다
    img.addEventListener('load', () => showNotice(''));
    // src가 없을 때(창을 닫으며 비운 경우)의 error는 오류가 아니므로 무시한다
    img.addEventListener('error', () => {
        if (img.getAttribute('src')) {
            showNotice('영상을 불러올 수 없습니다', true);
        }
    });
    // '다시 시도'는 영상 연결을 새로 한다. '다시보기'는 최근 녹화로 이동한다
    byId('live-retry').addEventListener('click', connect);
    byId('live-playback').addEventListener('click', openPlayback);
}

/** 캠 정보(GET /api/v1/manage/cameras 항목)로 창을 열고 영상을 연결한다. */
export function openLiveView(camera) {
    if (!modal) {
        return;
    }
    // 어떤 캠이 열려 있는지 기억해 둔다(통계 갱신·다시 시도에 필요)
    current = camera;
    // 서버 값은 textContent로 넣는다
    byId('live-cam').textContent = `CAM ${camera.id}`;
    byId('live-name').textContent = camera.name;
    byId('live-place').textContent =
        `· ${camera.regionPath} · ${camera.zoneName} · ${formatNumber(Math.round(camera.areaM2))}㎡`;
    // 현재 통계 칸을 채운다
    renderStat(camera);
    modal.hidden = false;
    // 영상 연결
    connect();
    // 이전 캠의 그래프가 응답 전까지 남지 않게 비운 뒤 읽는다
    clearLiveGraphs();
    loadLiveGraphs(camera);
}

/** 캠 정보가 새로 읽혔을 때 열려 있는 창의 현재 통계만 바꾼다(영상은 다시 연결하지 않는다). */
export function updateLiveView(cameras) {
    // 창이 닫혀 있거나 열린 캠이 없으면 할 일이 없다
    if (!modal || modal.hidden || !current) {
        return;
    }
    // 새로 읽은 캠 목록에서 지금 열린 캠을 찾는다
    const latest = cameras.find((camera) => camera.id === current.id);
    if (latest) {
        current = latest;
        renderStat(latest);
        loadLiveGraphs(latest);
    }
}

export function closeLiveView() {
    // 창이 이미 닫혀 있으면 할 일이 없다
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
    // 열린 캠이 없으면 연결할 주소가 없다
    if (!current) {
        return;
    }
    const img = byId('live-img');
    showNotice('영상을 불러오는 중…');
    // 이미지 요소를 다시 보이게 한다(오류 때 숨겨졌을 수 있음)
    img.hidden = false;
    // 다시 시도할 때도 새 연결이 되도록 주소 뒤에 값을 붙인다(nginx는 경로만 본다)
    img.src = `${url(`/live/${current.id}`)}?t=${Date.now()}`;
}

function showNotice(message, withRetry = false) {
    // message가 비면 안내 상자를 숨긴다. withRetry면 '다시 시도' 버튼을 같이 보인다
    byId('live-notice').hidden = message === '';
    byId('live-notice-text').textContent = message;
    byId('live-retry').hidden = !withRetry;
}

function renderStat(camera) {
    // stat: 최신 1분 통계(없으면 null)
    const stat = camera.stat;
    // 현재 인원은 5초마다(통계 SSE event: now) 이 칸만 바꾼다. 서버 값은 currentCount, 없으면 1분 평균(FNC-A-054)
    const shown = stat ? `${formatNumber(stat.currentCount ?? Math.round(stat.countAvg))}명` : '';
    bindNowCount(byId('live-count'), camera.zoneId, stat ? shown : '-', '명');
    // 밀집도는 소수 2자리
    byId('live-density').textContent = stat ? formatDecimal(stat.density, 2) : '-';
    // 혼잡 단계 칩. 이전 단계의 색 클래스가 남지 않게 초기화한 뒤 새 클래스를 단다
    const chip = byId('live-level');
    chip.className = 'chip';
    // 알 수 없는 단계(null 등)면 칩을 숨긴다
    if (stat && LEVEL_LABELS[stat.level]) {
        chip.classList.add(`l${stat.level}`);
        chip.textContent = LEVEL_LABELS[stat.level];
        chip.hidden = false;
    } else {
        chip.hidden = true;
    }
    // 칩이 숨겨지면 '단계 없음' 문구를 대신 보인다
    byId('live-level-none').hidden = !chip.hidden;
    // 영상 위 인원 배지도 같은 방식으로 5초마다 갱신한다
    bindNowCount(byId('live-count-badge'), camera.zoneId, shown, '명');
}

// 가장 최근 녹화를 찾아 다시보기 화면으로 간다(FNC-A-043·047). 없으면 토스트
async function openPlayback() {
    // 열린 캠이 없으면 할 일이 없다
    if (!current) {
        return;
    }
    try {
        // latest: true -> 이 구역의 가장 최근 녹화 하나를 요청한다
        const found = await get(
            '/api/v1/playback',
            { zoneId: current.zoneId, latest: true },
            { toast: false },
        );
        // 찾았으면 그 녹화 시작 시각으로 다시보기 화면에 이동한다
        window.location.href = `${url('/playback')}?zoneId=${current.zoneId}&at=${encodeURIComponent(found.startedAt)}`;
    } catch (error) {
        // 401은 api.js가 이미 로그인 화면으로 보냈으므로 문구를 띄우지 않는다
        if (error instanceof ApiError && error.status !== 401) {
            // 서버가 준 코드(영상 없음·보관 기간 지남)에 맞는 짧은 문구를, 없으면 서버 메시지를 토스트로 보인다
            showToast(PLAYBACK_MESSAGES[error.code] ?? error.message);
        }
    }
}

// document.getElementById 줄임
function byId(id) {
    return document.getElementById(id);
}
