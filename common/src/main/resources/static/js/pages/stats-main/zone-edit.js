// 대시보드 지도 구역 수정(SCR-C-003, FNC-A-015, 관리자 앱만, 동 단계에서만).
// 우클릭 메뉴 `구역 수정` → 지도에서 꼭짓점 편집 → 미리보기(POST /manage/zones/preview)로 면적·소속 동 → 이름 수정 → 저장(PATCH /manage/zones/{id}).
// 서버 값은 textContent로만 넣는다(code-conventions 5-3).
import { ApiError, patch, post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';

// 구역 관리 화면(admin/pages/zones.js)과 같은 문구
const ERROR_MESSAGES = {
    INVALID_POLYGON: '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
    DONG_CHANGE_NOT_ALLOWED: '다른 동으로 바뀌어 저장할 수 없습니다. 같은 동 안에서만 모양을 바꿀 수 있습니다.',
};

/**
 * @param {{getLayer: () => object|null, dongName: () => string, onChanged: () => Promise<void>|void, onActiveChange?: (active: boolean) => void}} handlers
 *   getLayer: 지금 지도의 상태 층(startZoneEdit·stopZoneEdit을 가진다), dongName: 지금 보는 동 이름,
 *   onChanged: 저장하거나 취소한 뒤 지도·통계를 다시 읽는다(취소하면 원래 모양으로 다시 그려진다)
 *   onActiveChange: 수정을 시작·끝낼 때(수정 중에는 하단 안내 문구를 숨기는 데 쓴다)
 */
export function createZoneEdit({ getLayer, dongName, onChanged, onActiveChange }) {
    const panel = document.getElementById('zone-edit');
    const form = document.getElementById('zone-edit-form');
    const title = document.getElementById('zone-edit-title');
    const area = document.getElementById('zone-edit-area');
    const dong = document.getElementById('zone-edit-dong');
    const error = document.getElementById('zone-edit-error');
    const nameInput = document.getElementById('zone-edit-name');
    const nameError = document.getElementById('zone-edit-name-error');
    const submit = document.getElementById('zone-edit-submit');

    // 고치는 중인 구역 { zone, boundary, shapeOk }. 쉬는 중이면 null
    let edit = null;
    let previewRequest = 0;

    document.getElementById('zone-edit-cancel').addEventListener('click', () => cancel());
    nameInput.addEventListener('input', updateSubmit);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        save();
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && edit) {
            cancel();
        }
    });

    function begin(zone) {
        edit = { zone, boundary: null, shapeOk: true };
        title.textContent = `'${zone.name}' 수정`;
        nameInput.value = zone.name;
        area.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡`;
        dong.textContent = dongName();
        showError('');
        setNameError('');
        panel.hidden = false;
        onActiveChange?.(true);
        updateSubmit();
        getLayer().startZoneEdit(zone.id, onShapeEdited);
    }

    // 꼭짓점을 고칠 때마다: 미리보기로 새 면적·소속 동을 받아 보이고, 다른 동으로 넘어가면 저장을 막는다
    async function onShapeEdited(boundary) {
        if (!edit) {
            return;
        }
        const request = ++previewRequest;
        const { zone } = edit;
        edit.boundary = boundary;
        edit.shapeOk = false;
        showError('');
        updateSubmit();
        try {
            const preview = await post('/api/v1/manage/zones/preview', { boundary }, { toast: false });
            if (!edit || request !== previewRequest) {
                return;
            }
            area.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡ → ${formatNumber(Math.round(preview.areaM2))}㎡`;
            dong.textContent = preview.dongName;
            if (preview.dongId === zone.dongId) {
                edit.shapeOk = true;
            } else {
                showError(ERROR_MESSAGES.DONG_CHANGE_NOT_ALLOWED);
            }
        } catch (failure) {
            if (edit && request === previewRequest) {
                reportFailure(failure, showError, setNameError);
            }
        }
        updateSubmit();
    }

    async function save() {
        if (!edit) {
            return;
        }
        const { zone } = edit;
        const name = nameInput.value.trim();
        if (!name) {
            setNameError('구역 이름을 입력해 주세요.');
            return;
        }
        // 바뀐 것만 보낸다
        const body = {};
        if (name !== zone.name) {
            body.name = name;
        }
        if (edit.boundary) {
            body.boundary = edit.boundary;
        }
        if (Object.keys(body).length === 0) {
            showToast('바뀐 내용이 없습니다.');
            cancel();
            return;
        }
        submit.disabled = true;
        try {
            const updated = await patch(`/api/v1/manage/zones/${zone.id}`, body, { toast: false });
            finish();
            showToast(`'${updated.name}' 구역을 수정했습니다.`);
            await onChanged();
        } catch (failure) {
            reportFailure(
                failure,
                (message) => (failure.code === 'DUPLICATE_ZONE_NAME' ? setNameError(message) : showError(message)),
                setNameError,
            );
            updateSubmit();
        }
    }

    // 수정을 끝낸다. 고치던 모양은 버리고 원래 모양으로 다시 그린다
    async function cancel() {
        if (!edit) {
            return;
        }
        finish();
        await onChanged();
    }

    function finish() {
        previewRequest++;
        getLayer()?.stopZoneEdit();
        edit = null;
        panel.hidden = true;
        onActiveChange?.(false);
    }

    // 서버 오류 코드는 화면 문구로, 그 밖의 오류는 토스트(api.js는 toast:false라 직접 띄운다)
    function reportFailure(failure, show, showName) {
        if (!(failure instanceof ApiError)) {
            showToast('요청을 보내지 못했습니다. 연결을 확인하고 다시 시도해 주세요.');
            return;
        }
        if (failure.status === 401) {
            return;
        }
        const fieldMessage = failure.fieldErrors.find((item) => item.field === 'name')?.message;
        if (fieldMessage) {
            showName(fieldMessage);
        } else if (ERROR_MESSAGES[failure.code]) {
            show(ERROR_MESSAGES[failure.code]);
        } else if (failure.status === 400) {
            show(failure.message);
        } else {
            showToast(failure.message);
        }
    }

    function showError(message) {
        error.textContent = message;
        error.hidden = !message;
    }

    function setNameError(message) {
        nameError.textContent = message;
        nameError.hidden = !message;
        nameInput.classList.toggle('err', Boolean(message));
    }

    function updateSubmit() {
        submit.disabled = edit === null || !edit.shapeOk || nameInput.value.trim() === '';
    }

    return {
        begin,
        /** 고치는 중이면 true. 이 동안은 지도의 구역·지역 클릭, 우클릭 메뉴가 동작하지 않고 경계를 다시 그리지 않는다 */
        isActive: () => edit !== null,
        /** 지역이 바뀌어 지도가 새로 만들어질 때: 하던 일을 버린다(지도가 새로 그려지므로 원래 모양은 저절로 돌아온다) */
        reset() {
            previewRequest++;
            edit = null;
            panel.hidden = true;
            onActiveChange?.(false);
        },
    };
}
