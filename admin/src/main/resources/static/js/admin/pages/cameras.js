// 캠 관리 화면 SCR-A-006과 캠 등록·수정 창 SCR-A-007(FNC-A-018·019·020·021·022).
// 1단계: 준비 중인 캠이 있으면 목록을 몇 초마다 다시 받아 진행률을 갱신한다(SSE는 2단계).
// 실시간 보기 창(SCR-A-008)은 RUNNING인 캠의 '실시간 보기'에서 연다. 삭제(3단계)는 만들지 않는다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).

import { get, patch, post, ApiError } from '../../common/api.js';
import { showToast } from '../../common/toast.js';
import { initLiveView, openLiveView, updateLiveView } from '../../live/live-view.js';

const POLL_INTERVAL_MS = 3000;

const STATUS = {
    STOPPED: { label: '정지', chip: 'off' },
    PREPARING: { label: '준비 중', chip: 'prep' },
    RUNNING: { label: '가동 중', chip: 'run' },
    ERROR: { label: '오류', chip: 'err' },
};
const SOURCE_TYPE = { DUMMY: '더미', LIVE: '라이브' };
const URI_HINT = {
    DUMMY: 'dummy/videos/ 로 시작하고 .mp4 로 끝나는 상대경로 (예: dummy/videos/a.mp4)',
    LIVE: 'rtsp:// 로 시작하는 주소 (예: rtsp://10.0.0.21/stream)',
};
// 등록 창에서 서버 메시지를 입력 아래에 보이는 오류 코드와 위치
const FORM_ERROR_TARGET = { ZONE_NOT_FOUND: 'zoneId', ZONE_HAS_CAMERA: 'zoneId' };
const NOT_STOPPED_HINT = '정지 상태에서만 고칠 수 있습니다. 먼저 정지해 주세요.';

const rowsEl = document.getElementById('camera-rows');
const emptyEl = document.getElementById('camera-empty');
const summaryEl = document.getElementById('camera-summary');
const modal = document.getElementById('camera-modal');
const form = document.getElementById('camera-form');
const formError = document.getElementById('camera-form-error');
const zoneSelect = document.getElementById('camera-zone');
const uriInput = document.getElementById('camera-uri');
const uriHint = document.getElementById('camera-uri-hint');
const saveButton = document.getElementById('camera-save');

let pollTimer = null;
let loading = false;
// 수정 창이 열려 있는 캠(등록 창이면 null)
let editing = null;

// ---- 목록 ----

async function loadCameras() {
    if (loading) {
        return;
    }
    loading = true;
    try {
        const cameras = await get('/api/v1/manage/cameras', undefined, { toast: false });
        render(cameras);
        updateLiveView(cameras);
        schedulePoll(cameras.some((camera) => camera.status === 'PREPARING'));
    } catch (error) {
        // 폴링 중 한 번 실패해도 표는 그대로 두고, 첫 조회 실패만 알린다
        if (!rowsEl.hasChildNodes()) {
            summaryEl.textContent = '목록을 불러오지 못했습니다.';
        }
        showToast(error.message);
        schedulePoll(rowsEl.querySelector('[data-status="PREPARING"]') !== null);
    } finally {
        loading = false;
    }
}

function schedulePoll(preparing) {
    window.clearTimeout(pollTimer);
    pollTimer = preparing ? window.setTimeout(loadCameras, POLL_INTERVAL_MS) : null;
}

function render(cameras) {
    rowsEl.replaceChildren(...cameras.map(buildRow));
    emptyEl.hidden = cameras.length > 0;
    const count = (status) => cameras.filter((camera) => camera.status === status).length;
    summaryEl.textContent =
        `총 ${cameras.length}대 · 가동 ${count('RUNNING')} · 준비 ${count('PREPARING')} · 오류 ${count('ERROR')}`;
}

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
        cell(errorCell(camera.errorMessage)),
        cell(actions(camera)),
    );
    return row;
}

function statusChip(status) {
    const info = STATUS[status] ?? { label: status, chip: 'off' };
    return el('span', `chip ${info.chip}`, info.label);
}

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

function errorCell(message) {
    return message ? el('span', 'cam-error', message) : '';
}

// STOPPED → 가동 / PREPARING·RUNNING → 정지 / ERROR → 다시 시작 + 정지
function actions(camera) {
    const wrap = el('div', 'row-actions');
    wrap.append(liveButton(camera), editButton(camera));
    if (camera.status === 'STOPPED') {
        wrap.append(actionButton('가동', 'primary', camera, 'start'));
    }
    if (camera.status === 'ERROR') {
        wrap.append(actionButton('다시 시작', 'primary', camera, 'start'));
    }
    if (camera.status !== 'STOPPED') {
        wrap.append(actionButton('정지', 'secondary', camera, 'stop'));
    }
    return wrap;
}

// RUNNING일 때만 켜진다(FNC-A-041이 가동 중이 아니면 영상을 열어 주지 않는다)
function liveButton(camera) {
    const button = el('button', 'btn secondary sm', '실시간 보기');
    button.type = 'button';
    button.disabled = camera.status !== 'RUNNING';
    button.addEventListener('click', () => openLiveView(camera));
    return button;
}

// 정지 상태(STOPPED)이고 가동 요청이 없을 때만 켜진다(FNC-A-019가 그 밖의 상태는 409로 막는다)
function editButton(camera) {
    const button = el('button', 'btn secondary sm', '수정');
    button.type = 'button';
    const editable = camera.status === 'STOPPED' && !camera.runRequested;
    button.disabled = !editable;
    if (!editable) {
        button.title = NOT_STOPPED_HINT;
    }
    button.addEventListener('click', () => openModal(camera));
    return button;
}

function actionButton(label, variant, camera, action) {
    const button = el('button', `btn ${variant} sm`, label);
    button.type = 'button';
    button.addEventListener('click', () => runAction(button, camera, action));
    return button;
}

async function runAction(button, camera, action) {
    const buttons = button.closest('.row-actions').querySelectorAll('button');
    buttons.forEach((each) => (each.disabled = true));
    try {
        const result = await post(`/api/v1/manage/cameras/${camera.id}/${action}`);
        if (action === 'stop' && result?.warning) {
            showToast(result.warning);
        }
    } catch {
        // 409·422·502 등은 api.js가 서버 메시지를 토스트로 띄웠다
    }
    // 성공이든 실패든 서버 상태가 기준이므로 목록을 다시 받는다
    await loadCameras();
}

// ---- 캠 등록·수정 창 ----

// camera가 있으면 수정 창(구역은 읽기 전용, 값을 채워 연다), 없으면 등록 창
async function openModal(camera = null) {
    editing = camera;
    form.reset();
    clearErrors();
    document.getElementById('camera-modal-title').textContent = camera ? '캠 수정' : '캠 등록';
    document.getElementById('camera-zone-hint').textContent = camera
        ? '구역은 바꿀 수 없습니다'
        : '캠이 없는 구역만 고를 수 있습니다(구역당 1대)';
    if (camera) {
        form.elements.name.value = camera.name;
        form.elements.sourceType.value = camera.sourceType;
        uriInput.value = camera.sourceUri;
    }
    updateUriHint();
    modal.hidden = false;
    if (camera) {
        zoneSelect.replaceChildren(option(camera.zoneId, camera.zoneName));
        zoneSelect.disabled = true;
        saveButton.disabled = false;
        form.elements.name.focus();
        return;
    }
    zoneSelect.disabled = false;
    zoneSelect.replaceChildren(option('', '구역을 불러오는 중…'));
    saveButton.disabled = true;
    try {
        const zones = await get('/api/v1/zones');
        const free = zones.filter((zone) => !zone.hasCamera);
        zoneSelect.replaceChildren(
            ...(free.length === 0
                ? [option('', '캠이 없는 구역이 없습니다')]
                : [option('', '구역 선택'), ...free.map((zone) => option(zone.id, zone.name))]),
        );
        saveButton.disabled = false;
        zoneSelect.focus();
    } catch {
        zoneSelect.replaceChildren(option('', '구역을 불러오지 못했습니다'));
    }
}

function closeModal() {
    modal.hidden = true;
    editing = null;
}

function updateUriHint() {
    uriHint.textContent = URI_HINT[form.elements.sourceType.value];
    uriInput.placeholder = form.elements.sourceType.value === 'LIVE' ? 'rtsp://…' : 'dummy/videos/….mp4';
}

function clearErrors() {
    formError.hidden = true;
    formError.textContent = '';
    form.querySelectorAll('[data-error-for]').forEach((hint) => {
        hint.hidden = true;
        hint.textContent = '';
    });
    form.querySelectorAll('.input.err').forEach((input) => input.classList.remove('err'));
}

function showFieldError(field, message) {
    const hint = form.querySelector(`[data-error-for="${field}"]`);
    if (!hint) {
        formError.textContent = message;
        formError.hidden = false;
        return;
    }
    hint.textContent = message;
    hint.hidden = false;
    form.elements[field]?.classList?.add('err');
}

async function submitForm(event) {
    event.preventDefault();
    clearErrors();
    const values = {
        name: form.elements.name.value.trim(),
        sourceType: form.elements.sourceType.value,
        sourceUri: uriInput.value.trim(),
    };
    saveButton.disabled = true;
    try {
        if (editing) {
            await saveEdit(values);
        } else {
            await post('/api/v1/manage/cameras', { zoneId: zoneSelect.value ? Number(zoneSelect.value) : null, ...values }, { toast: false });
            closeModal();
            await loadCameras();
        }
    } catch (error) {
        if (!(error instanceof ApiError)) {
            throw error;
        }
        if (error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else if (error.code === 'INVALID_INPUT') {
            showFieldError('sourceUri', error.message);
        } else if (error.code === 'CAMERA_NOT_STOPPED') {
            // 그 사이 가동됐다. 오류를 보이고 목록을 다시 받아 수정 버튼을 끈다
            showFieldError('', error.message);
            await loadCameras();
        } else {
            showFieldError(FORM_ERROR_TARGET[error.code] ?? '', error.message);
        }
    } finally {
        saveButton.disabled = false;
    }
}

// 수정: 바뀐 필드만 PATCH로 보낸다. 바뀐 것이 없으면 창만 닫는다
async function saveEdit(values) {
    const body = {};
    Object.keys(values).forEach((key) => {
        if (values[key] !== editing[key]) {
            body[key] = values[key];
        }
    });
    if (Object.keys(body).length === 0) {
        closeModal();
        return;
    }
    const updated = await patch(`/api/v1/manage/cameras/${editing.id}`, body, { toast: false });
    closeModal();
    showToast(`'${updated.name}' 캠 정보를 수정했습니다.`);
    await loadCameras();
}

// ---- 도우미 ----

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

function cell(content) {
    const node = el('div');
    node.setAttribute('role', 'cell');
    node.append(content);
    return node;
}

function textWithSmall(main, sub) {
    const fragment = document.createDocumentFragment();
    fragment.append(main ?? '');
    if (sub) {
        fragment.append(el('small', undefined, sub));
    }
    return fragment;
}

function option(value, label) {
    const node = document.createElement('option');
    node.value = value;
    node.textContent = label;
    return node;
}

initLiveView();
document.getElementById('camera-add').addEventListener('click', () => openModal());
modal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeModal));
modal.addEventListener('mousedown', (event) => {
    if (event.target === modal) {
        closeModal();
    }
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !modal.hidden) {
        closeModal();
    }
});
form.addEventListener('submit', submitForm);
form.querySelectorAll('input[name="sourceType"]').forEach((radio) => radio.addEventListener('change', updateUriHint));

updateUriHint();
loadCameras();
