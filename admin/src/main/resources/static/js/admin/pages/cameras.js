// 캠 관리 화면 SCR-A-006과 캠 등록 창 SCR-A-007(FNC-A-018·020·021·022·023).
// 1단계: 준비 중인 캠이 있으면 목록을 몇 초마다 다시 받아 진행률을 갱신한다(SSE는 2단계).
// 실시간 보기 창(SCR-A-008)은 RUNNING인 캠의 '실시간 보기'에서 연다. 수정(3단계)은 만들지 않는다. 서버 값은 textContent로만 넣는다(code-conventions 5-3).

import { get, post, del, ApiError } from '../../common/api.js';
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
const deleteModal = document.getElementById('camera-delete-modal');
const deleteConfirm = document.getElementById('camera-delete-confirm');

let pollTimer = null;
let loading = false;
let deleteTarget = null;
let deleting = false;

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

const RUN_PENDING_HINT = '가동 요청 처리 중입니다. 잠시 뒤 다시 시도하거나 정지해 주세요';

// STOPPED이면서 가동 요청 상태(가동 도중)
function isRunPending(camera) {
    return camera.status === 'STOPPED' && camera.runRequested;
}

// STOPPED → 가동 / PREPARING·RUNNING → 정지 / ERROR → 다시 시작 + 정지
// STOPPED여도 가동 요청 상태면 정지도 보인다(가동 도중 멈춰 이 상태가 남았을 때 빠져나갈 길)
function actions(camera) {
    const wrap = el('div', 'row-actions');
    wrap.append(liveButton(camera));
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
function liveButton(camera) {
    const button = el('button', 'btn secondary sm', '실시간 보기');
    button.type = 'button';
    button.disabled = camera.status !== 'RUNNING';
    button.addEventListener('click', () => openLiveView(camera));
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

// ---- 캠 등록 창 ----

async function openModal() {
    form.reset();
    clearErrors();
    updateUriHint();
    modal.hidden = false;
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
    const body = {
        zoneId: zoneSelect.value ? Number(zoneSelect.value) : null,
        name: form.elements.name.value.trim(),
        sourceType: form.elements.sourceType.value,
        sourceUri: uriInput.value.trim(),
    };
    saveButton.disabled = true;
    try {
        await post('/api/v1/manage/cameras', body, { toast: false });
        closeModal();
        await loadCameras();
    } catch (error) {
        if (!(error instanceof ApiError)) {
            throw error;
        }
        if (error.fieldErrors.length > 0) {
            error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
        } else if (error.code === 'INVALID_INPUT') {
            showFieldError('sourceUri', error.message);
        } else {
            showFieldError(FORM_ERROR_TARGET[error.code] ?? '', error.message);
        }
    } finally {
        saveButton.disabled = false;
    }
}

// ---- 캠 삭제 확인 창 ----

function openDeleteModal(camera) {
    deleteTarget = camera;
    deleteConfirm.disabled = false;
    deleteModal.hidden = false;
    deleteConfirm.focus();
}

function closeDeleteModal() {
    if (deleting) {
        return; // 요청이 끝나기 전에는 닫지 않는다(끝난 뒤 새로 연 창을 닫는 일을 막는다)
    }
    deleteModal.hidden = true;
    deleteTarget = null;
}

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
document.getElementById('camera-add').addEventListener('click', openModal);
modal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeModal));
modal.addEventListener('mousedown', (event) => {
    if (event.target === modal) {
        closeModal();
    }
});
deleteModal.querySelectorAll('[data-delete-close]').forEach((button) => button.addEventListener('click', closeDeleteModal));
deleteModal.addEventListener('mousedown', (event) => {
    if (event.target === deleteModal) {
        closeDeleteModal();
    }
});
deleteConfirm.addEventListener('click', confirmDelete);
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !modal.hidden) {
        closeModal();
    }
    if (event.key === 'Escape' && !deleteModal.hidden) {
        closeDeleteModal();
    }
});
form.addEventListener('submit', submitForm);
form.querySelectorAll('input[name="sourceType"]').forEach((radio) => radio.addEventListener('change', updateUriHint));

updateUriHint();
loadCameras();
