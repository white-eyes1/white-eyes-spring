// 캠 관리 화면 SCR-A-006과 캠 등록·수정 창 SCR-A-007(FNC-A-018·019·020·021·022·023).
// 1단계: 준비 중인 캠이 있으면 목록을 몇 초마다 다시 받아 진행률을 갱신한다(SSE는 2단계).
// 실시간 보기 창(SCR-A-008)은 RUNNING인 캠의 '실시간 보기'에서 연다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).

// 공용 API 모듈(get/post/del), 캠 등록·수정 폼(대시보드와 공용), 토스트, 실시간 보기 창 모듈을 가져온다.
import { get, post, del } from '../../common/api.js';
import { initCameraForm } from '../../camera/camera-form.js';
import { showToast } from '../../common/toast.js';
import { initLiveView, openLiveView, updateLiveView } from '../../live/live-view.js';

// POLL_INTERVAL_MS: 준비 중인 캠이 있을 때 목록을 다시 받는 간격. STREAM_DEBOUNCE_MS: 알림 스트림 소식이 연달아 올 때 한 번으로 모으는 대기 시간.
const POLL_INTERVAL_MS = 3000;
const STREAM_DEBOUNCE_MS = 300;

// 캠 상태 코드별 화면 이름과 칩 색(CSS 클래스).
const STATUS = {
    STOPPED: { label: '정지', chip: 'off' },
    PREPARING: { label: '준비 중', chip: 'prep' },
    RUNNING: { label: '가동 중', chip: 'run' },
    ERROR: { label: '오류', chip: 'err' },
};
const SOURCE_TYPE = { DUMMY: '더미', LIVE: '라이브' };
const NOT_STOPPED_HINT = '정지 상태에서만 고칠 수 있습니다. 먼저 정지해 주세요.';

// 화면 요소(목록 행 영역, 빈 목록 안내, 요약 줄, 삭제 확인 창)를 한 번만 찾아 둔다.
const rowsEl = document.getElementById('camera-rows');
const emptyEl = document.getElementById('camera-empty');
const summaryEl = document.getElementById('camera-summary');
const deleteModal = document.getElementById('camera-delete-modal');
const deleteConfirm = document.getElementById('camera-delete-confirm');

// pollTimer: 다음 폴링 예약, loading: 목록 조회 중 여부(겹침 방지), streamTimer: 스트림 소식 모으기 예약, deleteTarget·deleting: 삭제 확인 창의 대상과 진행 여부.
let pollTimer = null;
let loading = false;
let streamTimer = null;
let deleteTarget = null;
let deleting = false;

// ---- 목록 ----

// 알림 스트림의 캠 상태 소식(FNC-A-048)이 오면 목록을 다시 읽는다. 연달아 오는 진행률은 한 번에 모은다
// 다른 모듈이 알림 스트림에서 캠 상태 소식을 받으면 이 이름의 이벤트를 window로 보낸다. 마지막 소식 후 300ms 뒤 한 번만 읽는다.
window.addEventListener('we:camera-status', () => {
    window.clearTimeout(streamTimer);
    streamTimer = window.setTimeout(loadCameras, STREAM_DEBOUNCE_MS);
});

// 캠 관리 목록을 불러와 표·실시간 보기 창을 갱신한다. 이미 조회 중이면 겹쳐 부르지 않는다.
async function loadCameras() {
    if (loading) {
        return;
    }
    loading = true;
    try {
        const cameras = await get('/api/v1/manage/cameras', undefined, { toast: false });
        render(cameras);
        updateLiveView(cameras);
        // 준비 중(PREPARING) 캠이 하나라도 있으면 몇 초 뒤 다시 읽어 진행률을 갱신하고, 없으면 폴링을 멈춘다.
        schedulePoll(cameras.some((camera) => camera.status === 'PREPARING'));
    } catch (error) {
        // 오류가 나도 폴링이 끊기지 않게 현재 표에 준비 중 행이 남아 있으면 계속 다시 시도한다.
        // 폴링 중 한 번 실패해도 표는 그대로 두고, 첫 조회 실패만 알린다
        if (!rowsEl.hasChildNodes()) {
            summaryEl.textContent = '목록을 불러오지 못했습니다.';
        }
        showToast(error.message);
        schedulePoll(rowsEl.querySelector('[data-status="PREPARING"]') !== null);
    // 성공·실패와 무관하게 조회 중 표시를 푼다.
    } finally {
        loading = false;
    }
}

// 예약된 폴링을 먼저 취소하고, 준비 중이면 새로 예약한다(중복 예약 방지).
function schedulePoll(preparing) {
    window.clearTimeout(pollTimer);
    pollTimer = preparing ? window.setTimeout(loadCameras, POLL_INTERVAL_MS) : null;
}

// 목록을 표로 그리고 빈 목록 안내와 상태별 개수 요약을 갱신한다.
function render(cameras) {
    rowsEl.replaceChildren(...cameras.map(buildRow));
    emptyEl.hidden = cameras.length > 0;
    // 상태별 캠 수를 세는 보조 함수.
    const count = (status) => cameras.filter((camera) => camera.status === status).length;
    summaryEl.textContent = `총 ${cameras.length}대 · 가동 ${count('RUNNING')} · 준비 ${count('PREPARING')} · 오류 ${count('ERROR')}`;
}

// 캠 한 대를 표의 한 행으로 만든다. data-id·data-status 속성은 오류 시 준비 중 행을 찾는 데 쓴다.
function buildRow(camera) {
    const row = el('div', 'tr');
    row.setAttribute('role', 'row');
    row.dataset.id = camera.id;
    row.dataset.status = camera.status;

    row.append(
        cell(textWithSmall(camera.zoneName, camera.regionPath)),
        cell(textWithSmall(camera.name, camera.sourceUri)),
        cell(SOURCE_TYPE[camera.sourceType] ?? camera.sourceType),
        cell(statusChip(camera.status)),
        cell(progressCell(camera)),
        cell(errorCell(camera)),
        cell(actions(camera)),
    );
    return row;
}

// 상태 칩. 모르는 상태는 회색 칩에 원문을 보인다.
function statusChip(status) {
    const info = STATUS[status] ?? { label: status, chip: 'off' };
    return el('span', `chip ${info.chip}`, info.label);
}

// 준비 중일 때만 진행률 막대를 보인다. 0~100 범위로 잘라 막대가 넘치지 않게 하고, 보조 기술을 위해 progressbar 역할과 값을 단다.
function progressCell(camera) {
    if (camera.status !== 'PREPARING') {
        return el('span', 'muted', '-');
    }
    const percent = Math.max(0, Math.min(100, camera.progress ?? 0));
    const wrap = el('div', 'cam-progress');
    const bar = el('div', 'progress');
    bar.setAttribute('role', 'progressbar');
    bar.setAttribute('aria-valuenow', String(percent));
    bar.setAttribute('aria-valuemin', '0');
    bar.setAttribute('aria-valuemax', '100');
    const fill = document.createElement('i');
    fill.style.width = `${percent}%`;
    bar.append(fill);
    wrap.append(bar, el('span', 'muted', `${percent}%`));
    return wrap;
}

// 가동 요청은 갔지만 아직 정지 상태인 캠의 수정·삭제 버튼이 꺼진 까닭을 알리는 문구.
const RUN_PENDING_HINT = '가동 요청 처리 중입니다. 잠시 뒤 다시 시도하거나 정지해 주세요';

// 오류 메시지가 먼저, 없고 가동 요청 처리 중이면 그 안내를 보인다(삭제 버튼이 꺼진 까닭)
// 오류 메시지가 있으면 그것을, 없고 가동 요청 처리 중이면 안내를 보인다.
function errorCell(camera) {
    if (camera.errorMessage) {
        return el('span', 'cam-error', camera.errorMessage);
    }
    return isRunPending(camera) ? el('span', 'muted cam-pending', RUN_PENDING_HINT) : '';
}

// STOPPED이면서 가동 요청 상태(가동 도중)
// 정지 상태이지만 가동 요청이 남아 있는, 가동 도중 상태인가.
function isRunPending(camera) {
    return camera.status === 'STOPPED' && camera.runRequested;
}

// STOPPED → 가동 / PREPARING·RUNNING → 정지 / ERROR → 다시 시작 + 정지
// STOPPED여도 가동 요청 상태면 정지도 보인다(가동 도중 멈춰 이 상태가 남았을 때 빠져나갈 길)
// 캠 상태에 따라 동작 버튼 구성이 달라진다. 실시간 보기와 수정은 항상 자리를 두고 활성 여부는 각 함수가 정하며, 가동·다시 시작·정지·삭제는 아래 조건으로 붙인다.
function actions(camera) {
    const wrap = el('div', 'row-actions');
    wrap.append(liveButton(camera), editButton(camera));
    if (camera.status === 'STOPPED') {
        wrap.append(actionButton('가동', 'primary', camera, 'start'));
    }
    if (camera.status === 'ERROR') {
        wrap.append(actionButton('다시 시작', 'primary', camera, 'start'));
    }
    if (camera.status !== 'STOPPED' || camera.runRequested) {
        wrap.append(actionButton('정지', 'secondary', camera, 'stop'));
    }
    wrap.append(deleteButton(camera));
    return wrap;
}

// STOPPED이고 가동 요청 상태가 아닐 때만 켜진다(FNC-A-023은 아니면 409). 누르면 확인 창을 연다
// 삭제 버튼. 정지 상태이고 가동 요청도 없을 때만 켜지며 서버(FNC-A-023)도 같은 기준으로 409를 낸다.
function deleteButton(camera) {
    const button = el('button', 'btn danger sm', '삭제');
    button.type = 'button';
    button.disabled = camera.status !== 'STOPPED' || camera.runRequested;
    if (isRunPending(camera)) {
        button.title = RUN_PENDING_HINT;
    }
    button.addEventListener('click', () => openDeleteModal(camera));
    return button;
}

// RUNNING일 때만 켜진다(FNC-A-041이 가동 중이 아니면 영상을 열어 주지 않는다)
// 실시간 보기 버튼. 가동 중일 때만 켜진다.
function liveButton(camera) {
    const button = el('button', 'btn secondary sm', '실시간 보기');
    button.type = 'button';
    button.disabled = camera.status !== 'RUNNING';
    button.addEventListener('click', () => openLiveView(camera));
    return button;
}

// 정지 상태(STOPPED)이고 가동 요청이 없을 때만 켜진다(FNC-A-019가 그 밖의 상태는 409로 막는다)
// 수정 버튼. 정지 상태이고 가동 요청이 없을 때만 켜지며, 꺼진 까닭은 마우스를 올리면 title로 알린다.
function editButton(camera) {
    const button = el('button', 'btn secondary sm', '수정');
    button.type = 'button';
    const editable = camera.status === 'STOPPED' && !camera.runRequested;
    button.disabled = !editable;
    if (!editable) {
        // 가동 요청 처리 중이면 삭제 버튼과 같은 안내를 쓴다(FNC-A-019·023 같은 기준)
        button.title = isRunPending(camera) ? RUN_PENDING_HINT : NOT_STOPPED_HINT;
    }
    // cameraForm은 파일 아래쪽에서 만들어진다. 클릭 시점에는 이미 값이 있으므로 문제없다.
    button.addEventListener('click', () => cameraForm.openEdit(camera));
    return button;
}

// 가동·정지 같은 상태 변경 버튼을 만든다. action은 API 경로의 끝(start 또는 stop).
function actionButton(label, variant, camera, action) {
    const button = el('button', `btn ${variant} sm`, label);
    button.type = 'button';
    button.addEventListener('click', () => runAction(button, camera, action));
    return button;
}

// 상태 변경 요청을 보낸다. 같은 행의 버튼을 모두 잠가 중복 요청을 막는다.
async function runAction(button, camera, action) {
    const buttons = button.closest('.row-actions').querySelectorAll('button');
    buttons.forEach((each) => (each.disabled = true));
    try {
        const result = await post(`/api/v1/manage/cameras/${camera.id}/${action}`);
        // 정지에 성공했지만 서버가 경고를 같이 준 경우 그 경고를 알린다.
        if (action === 'stop' && result?.warning) {
            showToast(result.warning);
        }
    } catch {
        // 409·422·502 등은 api.js가 서버 메시지를 토스트로 띄웠다
    }
    // 서버가 어느 상태인지가 기준이므로 결과와 무관하게 목록을 다시 읽어 버튼 잠금도 푼다.
    // 성공이든 실패든 서버 상태가 기준이므로 목록을 다시 받는다
    await loadCameras();
}

// ---- 캠 삭제 확인 창 ----

// 삭제 확인 창을 열고 대상을 기억한다.
function openDeleteModal(camera) {
    deleteTarget = camera;
    deleteConfirm.disabled = false;
    deleteModal.hidden = false;
    deleteConfirm.focus();
}

// 삭제 확인 창을 닫는다. 요청 중에는 닫지 않는다.
function closeDeleteModal() {
    if (deleting) {
        return; // 요청이 끝나기 전에는 닫지 않는다(끝난 뒤 새로 연 창을 닫는 일을 막는다)
    }
    deleteModal.hidden = true;
    deleteTarget = null;
}

// 삭제 확인. 대상을 지역 변수로 먼저 꺼내 두는 것은 요청 중 deleteTarget이 바뀌어도 안전하게 하려는 것이다.
async function confirmDelete() {
    if (!deleteTarget || deleting) {
        return;
    }
    const camera = deleteTarget;
    deleting = true;
    deleteConfirm.disabled = true;
    try {
        await del(`/api/v1/manage/cameras/${camera.id}`);
        showToast('캠을 삭제했습니다.');
    } catch {
        // 409(정지 상태가 아님)·404 등은 api.js가 서버 메시지를 토스트로 띄웠다
    }
    deleting = false;
    closeDeleteModal();
    // 성공이든 실패든 서버 상태가 기준이므로 목록을 다시 받는다
    await loadCameras();
}

// ---- 도우미 ----

// 요소를 만드는 도우미. 글자는 textContent로 넣어 HTML로 해석되지 않게 한다.
function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined) {
        node.textContent = text;
    }
    return node;
}

// 표 칸(div role=cell)을 만들어 내용을 넣는다.
function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

// 큰 글자 아래에 작은 보조 글자를 붙인 조각을 만든다. 보조 글자가 없으면 큰 글자만.
function textWithSmall(main, sub) {
    const fragment = document.createDocumentFragment();
    fragment.append(main ?? '');
    if (sub) {
        fragment.append(el('small', undefined, sub));
    }
    return fragment;
}

// 이벤트 연결부와 시작점: 실시간 보기 창을 준비하고, 캠 폼을 만들어 저장 후 목록을 다시 읽게 하며, 삭제 창 닫기·확인·Esc를 연결한 뒤 처음 목록을 불러온다.
initLiveView();
// 캠 등록·수정 창은 대시보드와 같은 창을 쓴다(camera/camera-form.js). 저장이 끝나면 목록을 다시 읽는다
const cameraForm = initCameraForm({ onSaved: loadCameras });
document.getElementById('camera-add').addEventListener('click', () => cameraForm.openRegister());
deleteModal
    .querySelectorAll('[data-delete-close]')
    .forEach((button) => button.addEventListener('click', closeDeleteModal));
deleteModal.addEventListener('mousedown', (event) => {
    if (event.target === deleteModal) {
        closeDeleteModal();
    }
});
deleteConfirm.addEventListener('click', confirmDelete);
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !deleteModal.hidden) {
        closeDeleteModal();
    }
});

loadCameras();
