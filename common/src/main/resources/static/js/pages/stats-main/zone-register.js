// 대시보드 지도 구역 등록(SCR-C-003, FNC-A-013·014, 관리자 앱만, 동 단계에서만).
// 빈 곳 우클릭 메뉴 `구역 등록` → 확인 창 → 다각형 그리기 → 면적·소속 동 미리보기(POST /manage/zones/preview) → 이름 입력 → 등록(POST /manage/zones).
// 서버 값은 textContent로만 넣는다(code-conventions 5-3).
import { ApiError, post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { createZoneDrawTool } from '../../map/zone-draw-tool.js';

// 구역 관리 화면(admin/pages/zones.js)과 같은 문구
const ERROR_MESSAGES = {
    INVALID_POLYGON: '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
};

/**
 * @param {{onRegistered: (zone: object) => Promise<void>|void}} handlers 등록에 성공하면 불린다(대시보드가 값을 다시 읽는다)
 */
export function createZoneRegister({ onRegistered }) {
    const confirmModal = document.getElementById('zone-reg-confirm');
    const panel = document.getElementById('zone-reg');
    const guide = document.getElementById('zone-reg-guide');
    const form = document.getElementById('zone-reg-form');
    const area = document.getElementById('zone-reg-area');
    const dong = document.getElementById('zone-reg-dong');
    const error = document.getElementById('zone-reg-error');
    const nameInput = document.getElementById('zone-reg-name');
    const nameError = document.getElementById('zone-reg-name-error');
    const submit = document.getElementById('zone-reg-submit');
    const hint = document.getElementById('zone-reg-hint');

    let tool = null;
    // 그리는 중이거나 미리보기를 보고 있으면 true. 이 동안은 지도의 구역·핀 클릭을 막는다
    let active = false;
    let draft = null;
    // 동 단계이고 지도가 이 도구를 쓸 수 있으면 true(안내 문구를 보인다)
    let available = false;

    confirmModal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeConfirm));
    confirmModal.addEventListener('mousedown', (event) => {
        if (event.target === confirmModal) {
            closeConfirm();
        }
    });
    document.getElementById('zone-reg-ok').addEventListener('click', () => {
        closeConfirm();
        startDraw();
    });
    document.getElementById('zone-reg-cancel').addEventListener('click', cancel);
    document.getElementById('zone-reg-guide-cancel').addEventListener('click', cancel);
    document.getElementById('zone-reg-redraw').addEventListener('click', startDraw);
    nameInput.addEventListener('input', updateSubmit);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        register();
    });
    document.addEventListener('keydown', (event) => {
        if (event.key !== 'Escape') {
            return;
        }
        if (!confirmModal.hidden) {
            closeConfirm();
        } else if (active) {
            cancel();
        }
    });

    // 메뉴의 `구역 등록`을 누르면: 확인 창을 연다
    function begin() {
        if (!active && confirmModal.hidden) {
            confirmModal.hidden = false;
            document.getElementById('zone-reg-ok').focus();
        }
    }

    function closeConfirm() {
        confirmModal.hidden = true;
    }

    function startDraw() {
        tool.cancel();
        draft = null;
        active = true;
        panel.hidden = false;
        hint.hidden = true;
        guide.hidden = false;
        form.hidden = true;
        nameInput.value = '';
        showError('');
        setNameError('');
        tool.startDraw();
    }

    function cancel() {
        tool?.cancel();
        active = false;
        draft = null;
        panel.hidden = true;
        hint.hidden = !available;
    }

    // 그리기를 마치면 미리보기 API로 면적·소속 동을 받아 카드에 보인다
    async function preview(boundary) {
        guide.hidden = true;
        form.hidden = false;
        draft = null;
        area.textContent = '-';
        dong.textContent = '-';
        showError('');
        setNameError('');
        updateSubmit();
        try {
            const result = await post('/api/v1/manage/zones/preview', { boundary }, { toast: false });
            draft = { boundary };
            area.textContent = `${formatNumber(Math.round(result.areaM2))}㎡`;
            dong.textContent = result.dongName;
        } catch (failure) {
            reportFailure(failure, showError, setNameError);
        }
        updateSubmit();
    }

    async function register() {
        if (!draft) {
            return;
        }
        const name = nameInput.value.trim();
        if (!name) {
            setNameError('구역 이름을 입력해 주세요.');
            return;
        }
        submit.disabled = true;
        try {
            const created = await post('/api/v1/manage/zones', { name, boundary: draft.boundary }, { toast: false });
            cancel();
            showToast(`'${created.name}' 구역을 등록했습니다.`);
            await onRegistered(created);
        } catch (failure) {
            reportFailure(
                failure,
                (message) => (failure.code === 'DUPLICATE_ZONE_NAME' ? setNameError(message) : showError(message)),
                setNameError,
            );
            updateSubmit();
        }
    }

    // 서버 오류 코드는 화면 문구로, 그 밖의 오류는 토스트(api.js는 toast:false라 직접 띄운다)
    function reportFailure(failure, show, showName) {
        if (!(failure instanceof ApiError) || failure.status === 401) {
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
        submit.disabled = draft === null || nameInput.value.trim() === '';
    }

    return {
        /** 지도가 새로 만들어질 때마다 부른다(지역이 바뀌면 지도를 새로 만든다). 하던 일은 취소한다. */
        attach(map) {
            cancel();
            closeConfirm();
            tool = createZoneDrawTool(map, { onDrawn: preview });
        },
        /** 메뉴 `구역 등록`: 확인 창을 연다 */
        begin,
        /** 동 단계일 때만 켠다. 켜져 있고 쉬는 중이면 '빈 곳을 우클릭하면 구역을 등록할 수 있습니다' 안내를 보인다 */
        setAvailable(on) {
            available = on;
            hint.hidden = !on || active;
        },
        /** 그리는 중이거나 미리보기를 보고 있으면 true */
        isActive() {
            return active;
        },
        cancel,
    };
}
