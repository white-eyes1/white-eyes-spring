// 캠 등록·수정 창(SCR-A-007, FNC-A-018·019): 캠 관리 화면과 대시보드(캠 없는 구역의 핀)가 같은 창을 쓴다.
// 창 틀은 templates/fragments/layout.html 의 adminCameraModal. 서버 값은 textContent·value로만 넣는다(code-conventions 5-3).
import { ApiError, get, patch, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

const URI_HINT = {
    DUMMY: 'dummy/videos/ 로 시작하고 .mp4 로 끝나는 상대경로 (예: dummy/videos/a.mp4)',
    LIVE: 'rtsp:// 로 시작하는 주소 (예: rtsp://10.0.0.21/stream)',
};
// 등록 창에서 서버 메시지를 입력 아래에 보이는 오류 코드와 위치
const FORM_ERROR_TARGET = { ZONE_NOT_FOUND: 'zoneId', ZONE_HAS_CAMERA: 'zoneId' };

/**
 * 창 이벤트를 연결한다. 화면마다 한 번만 부른다.
 * @param {{onSaved: () => Promise<void>|void}} handlers 등록·수정이 끝난 뒤(그 화면이 값을 다시 읽는다). 수정 중 그 사이 가동돼 거절됐을 때도 불린다
 * @returns {{openRegister: (preferredZoneId?: number|null) => Promise<void>, openEdit: (camera: object) => Promise<void>}}
 */
export function initCameraForm({ onSaved }) {
    const modal = document.getElementById('camera-modal');
    const form = document.getElementById('camera-form');
    const formError = document.getElementById('camera-form-error');
    const zoneSelect = document.getElementById('camera-zone');
    const uriInput = document.getElementById('camera-uri');
    const uriHint = document.getElementById('camera-uri-hint');
    const saveButton = document.getElementById('camera-save');

    // 수정 창이 열려 있는 캠(등록 창이면 null)
    let editing = null;

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

    // camera가 있으면 수정 창(구역은 읽기 전용, 값을 채워 연다), 없으면 등록 창. preferredZoneId가 있으면 등록 창의 구역을 그 구역으로 고른다
    async function open(camera, preferredZoneId) {
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
            if (free.some((zone) => zone.id === preferredZoneId)) {
                zoneSelect.value = String(preferredZoneId);
            }
            saveButton.disabled = false;
            (zoneSelect.value ? form.elements.name : zoneSelect).focus();
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
                await onSaved();
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
                // 그 사이 가동됐다. 오류를 보이고 화면이 목록을 다시 받아 수정 버튼을 끈다
                showFieldError('', error.message);
                await onSaved();
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
        await onSaved();
    }

    return {
        openRegister: (preferredZoneId = null) => open(null, preferredZoneId),
        openEdit: (camera) => open(camera, null),
    };
}

function option(value, label) {
    const node = document.createElement('option');
    node.value = String(value);
    node.textContent = label;
    return node;
}
