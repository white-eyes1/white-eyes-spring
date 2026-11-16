// 캠 등록·수정 창(SCR-A-007, FNC-A-018·019): 캠 관리 화면과 대시보드(캠 없는 구역의 핀)가 같은 창을 쓴다.
// 창 틀은 templates/fragments/layout.html 의 adminCameraModal. 서버 값은 textContent·value로만 넣는다(code-conventions 5-3).
import { ApiError, get, patch, post } from '../common/api.js';
import { showToast } from '../common/toast.js';

// 입력 형식 안내 문구. sourceType(DUMMY 시연 영상 / LIVE 실시간 RTSP)에 따라 URI 칸 아래에 보인다
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
    // 창 안의 요소를 한 번에 찾아 둔다(이후 함수들이 클로저로 공유)
    const modal = document.getElementById('camera-modal');
    const form = document.getElementById('camera-form');
    const formError = document.getElementById('camera-form-error');
    const zoneSelect = document.getElementById('camera-zone');
    const uriInput = document.getElementById('camera-uri');
    const uriHint = document.getElementById('camera-uri-hint');
    const saveButton = document.getElementById('camera-save');

    // 수정 창이 열려 있는 캠(등록 창이면 null)
    let editing = null;

    // 창 닫기: 닫기 버튼(data-close), 창 바깥(배경) 클릭, Esc 키
    modal
        .querySelectorAll('[data-close]')
        .forEach((button) => button.addEventListener('click', closeModal));
    // 내용 영역이 아니라 배경(modal 자신)을 눌렀을 때만 닫는다
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
    // 저장 버튼은 form submit으로 처리한다(Enter로도 저장됨)
    form.addEventListener('submit', submitForm);
    // 영상 종류(DUMMY/LIVE) 라디오를 바꾸면 안내 문구와 placeholder를 바꾼다
    form.querySelectorAll('input[name="sourceType"]').forEach((radio) =>
        radio.addEventListener('change', updateUriHint),
    );
    // 처음 열기 전에도 기본 선택값에 맞는 안내를 채워 둔다
    updateUriHint();

    // camera가 있으면 수정 창(구역은 읽기 전용, 값을 채워 연다), 없으면 등록 창. preferredZoneId가 있으면 등록 창의 구역을 그 구역으로 고른다
    // 등록·수정 창을 여는 공통 함수. camera 유무로 모드를 가른다
    async function open(camera, preferredZoneId) {
        // 저장할 때 등록/수정을 가르고, 바뀐 필드만 비교하는 기준이 된다
        editing = camera;
        // 이전에 열었던 입력값·오류를 모두 비운다
        form.reset();
        clearErrors();
        document.getElementById('camera-modal-title').textContent = camera ? '캠 수정' : '캠 등록';
        document.getElementById('camera-zone-hint').textContent = camera
            ? '구역은 바꿀 수 없습니다'
            : '캠이 없는 구역만 고를 수 있습니다(구역당 1대)';
        // 수정 모드: 기존 값을 입력칸에 채운다
        if (camera) {
            form.elements.name.value = camera.name;
            form.elements.sourceType.value = camera.sourceType;
            uriInput.value = camera.sourceUri;
        }
        // 영상 종류에 맞는 안내 문구로 갱신(값을 채운 뒤에 불러야 한다)
        updateUriHint();
        modal.hidden = false;
        // 수정 모드: 구역은 바꿀 수 없으므로 현재 구역 하나만 보이고 선택은 막는다
        if (camera) {
            zoneSelect.replaceChildren(option(camera.zoneId, camera.zoneName));
            zoneSelect.disabled = true;
            saveButton.disabled = false;
            form.elements.name.focus();
            return;
        }
        // 등록 모드: 구역 목록을 서버에서 받는 동안 선택·저장을 막는다
        zoneSelect.disabled = false;
        zoneSelect.replaceChildren(option('', '구역을 불러오는 중…'));
        saveButton.disabled = true;
        try {
            // 실패하면 api.js가 오류 토스트를 띄운다
            const zones = await get('/api/v1/zones');
            // 구역당 캠은 1대이므로 캠이 없는 구역만 선택지로 쓴다
            const free = zones.filter((zone) => !zone.hasCamera);
            zoneSelect.replaceChildren(
                ...(free.length === 0
                    ? [option('', '캠이 없는 구역이 없습니다')]
                    : [option('', '구역 선택'), ...free.map((zone) => option(zone.id, zone.name))]),
            );
            // 대시보드 핀에서 열었으면 그 구역을 미리 골라 둔다(고를 수 있는 구역일 때만)
            if (free.some((zone) => zone.id === preferredZoneId)) {
                zoneSelect.value = String(preferredZoneId);
            }
            saveButton.disabled = false;
            // 구역이 이미 골라졌으면 이름 입력으로, 아니면 구역 선택으로 포커스를 준다
            (zoneSelect.value ? form.elements.name : zoneSelect).focus();
        } catch {
            // 저장 버튼은 계속 막아 둔다(구역 없이 등록할 수 없음)
            zoneSelect.replaceChildren(option('', '구역을 불러오지 못했습니다'));
        }
    }

    // 창을 닫고 수정 중 상태를 비운다
    function closeModal() {
        modal.hidden = true;
        editing = null;
    }

    // 선택된 영상 종류에 맞는 안내 문구와 입력 예시(placeholder)
    function updateUriHint() {
        uriHint.textContent = URI_HINT[form.elements.sourceType.value];
        uriInput.placeholder =
            form.elements.sourceType.value === 'LIVE' ? 'rtsp://…' : 'dummy/videos/….mp4';
    }

    // 이전 오류 표시를 모두 지운다: 폼 전체 오류, 입력별 오류, 빨간 테두리
    function clearErrors() {
        formError.hidden = true;
        formError.textContent = '';
        form.querySelectorAll('[data-error-for]').forEach((hint) => {
            hint.hidden = true;
            hint.textContent = '';
        });
        form.querySelectorAll('.input.err').forEach((input) => input.classList.remove('err'));
    }

    // field 이름으로 입력 아래 안내 칸(data-error-for)을 찾는다
    function showFieldError(field, message) {
        const hint = form.querySelector(`[data-error-for="${field}"]`);
        // 해당 칸이 없으면(전체 오류) 폼 위쪽 오류 칸에 보인다
        if (!hint) {
            formError.textContent = message;
            formError.hidden = false;
            return;
        }
        hint.textContent = message;
        hint.hidden = false;
        // 입력 칸에 오류 테두리(err)를 입힌다. 없는 필드면 아무 일도 하지 않는다
        form.elements[field]?.classList?.add('err');
    }

    async function submitForm(event) {
        // 기본 폼 전송(페이지 이동)을 막고 fetch로 처리한다
        event.preventDefault();
        clearErrors();
        // 앞뒤 공백은 서버에 보내기 전에 지운다
        const values = {
            name: form.elements.name.value.trim(),
            sourceType: form.elements.sourceType.value,
            sourceUri: uriInput.value.trim(),
        };
        // 서버 응답을 기다리는 동안 두 번 눌러 중복 등록되는 것을 막는다
        saveButton.disabled = true;
        try {
            // 수정 모드는 바뀐 필드만 PATCH, 등록 모드는 POST
            if (editing) {
                await saveEdit(values);
            } else {
                await post(
                    // toast:false: 입력 오류는 아래 catch에서 입력 옆에 직접 보여 주므로 토스트를 띄우지 않는다
                    '/api/v1/manage/cameras',
                    // 구역을 안 골랐으면 null을 보내 서버 검증이 오류를 돌려주게 한다
                    { zoneId: zoneSelect.value ? Number(zoneSelect.value) : null, ...values },
                    { toast: false },
                );
                // 저장되면 창을 닫고, 화면이 목록·값을 다시 읽는다(onSaved)
                closeModal();
                await onSaved();
            }
        } catch (error) {
            // ApiError가 아닌 예외(코드 버그 등)는 숨기지 않고 그대로 던진다
            if (!(error instanceof ApiError)) {
                throw error;
            }
            // 서버의 필드별 검증 오류는 해당 입력 아래에 보인다
            if (error.fieldErrors.length > 0) {
                error.fieldErrors.forEach(({ field, message }) => showFieldError(field, message));
            // 영상 주소 형식 오류는 sourceUri 칸에 보인다
            } else if (error.code === 'INVALID_INPUT') {
                showFieldError('sourceUri', error.message);
            } else if (error.code === 'CAMERA_NOT_STOPPED') {
                // 그 사이 가동됐다. 오류를 보이고 화면이 목록을 다시 받아 수정 버튼을 끈다
                showFieldError('', error.message);
                await onSaved();
            } else {
                // 구역 없음·구역에 이미 캠 있음은 구역 칸에, 그 밖은 폼 전체 오류로 보인다
                showFieldError(FORM_ERROR_TARGET[error.code] ?? '', error.message);
            }
        } finally {
            // 성공이든 실패든 저장 버튼을 다시 쓸 수 있게 한다
            saveButton.disabled = false;
        }
    }

    // 수정: 바뀐 필드만 PATCH로 보낸다. 바뀐 것이 없으면 창만 닫는다
    async function saveEdit(values) {
        // 원래 값(editing)과 다른 필드만 모은다
        const body = {};
        Object.keys(values).forEach((key) => {
            if (values[key] !== editing[key]) {
                body[key] = values[key];
            }
        });
        // 바뀐 것이 없으면 서버를 부르지 않는다
        if (Object.keys(body).length === 0) {
            closeModal();
            return;
        }
        // 수정은 필드 일부만 보내는 PATCH
        const updated = await patch(`/api/v1/manage/cameras/${editing.id}`, body, { toast: false });
        closeModal();
        showToast(`'${updated.name}' 캠 정보를 수정했습니다.`);
        await onSaved();
    }

    // 화면 쪽에서 쓰는 열기 함수 두 개만 공개한다
    return {
        openRegister: (preferredZoneId = null) => open(null, preferredZoneId),
        openEdit: (camera) => open(camera, null),
    };
}

// <option> 요소 하나 만들기(textContent로 넣어 서버 값이 HTML로 해석되지 않게 함)
function option(value, label) {
    const node = document.createElement('option');
    node.value = String(value);
    node.textContent = label;
    return node;
}
