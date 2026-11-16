// 대시보드 지도 구역 등록(SCR-C-003, FNC-A-013·014, 관리자 앱만, 동 단계에서만).
// 빈 곳 우클릭 메뉴 `구역 등록` → 확인 창 → 다각형 그리기 → 면적·소속 동 미리보기(POST /manage/zones/preview) → 이름 입력 → 등록(POST /manage/zones).
// 서버 값은 textContent로만 넣는다(code-conventions 5-3).
import { ApiError, post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { createZoneDrawTool } from '../../map/zone-draw-tool.js';

// 구역 관리 화면(admin/pages/zones.js)과 같은 문구
// 서버 오류 코드 -> 화면 문구
const ERROR_MESSAGES = {
    INVALID_POLYGON:
        '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
};

/**
 * @param {{onRegistered: (zone: object) => Promise<void>|void}} handlers 등록에 성공하면 불린다(대시보드가 값을 다시 읽는다)
 */
// onRegistered는 등록 성공 뒤 대시보드가 값을 다시 읽게 하는 함수
export function createZoneRegister({ onRegistered }) {
    // 확인 창, 등록 패널, 안내 문구, 입력칸 요소
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

    // tool: 지도에 붙인 그리기 도구(attach 후에 생기므로 처음엔 null)
    let tool = null;
    // 그리는 중이거나 미리보기를 보고 있으면 true. 이 동안은 지도의 구역·핀 클릭을 막는다
    let active = false;
    // draft: 미리보기까지 끝나 등록할 수 있는 모양 { boundary }
    let draft = null;
    // 미리보기 요청 번호. 다시 그리기·취소를 누르면 올려서, 그 전에 보낸 응답이 늦게 와도 값을 덮어쓰지 않게 한다
    let previewRequest = 0;
    // 구역 수정 중이면 true(안내 문구를 숨긴다)
    let suppressed = false;
    // 동 단계이고 지도가 이 도구를 쓸 수 있으면 true(안내 문구를 보인다)
    let available = false;

    // 확인 창 닫기: 닫기 버튼, 배경 클릭
    confirmModal
        .querySelectorAll('[data-close]')
        .forEach((button) => button.addEventListener('click', closeConfirm));
    confirmModal.addEventListener('mousedown', (event) => {
        if (event.target === confirmModal) {
            closeConfirm();
        }
    });
    // 확인 창의 '그리기 시작' 버튼
    document.getElementById('zone-reg-ok').addEventListener('click', () => {
        closeConfirm();
        startDraw();
    });
    // 취소와 다시 그리기 버튼
    document.getElementById('zone-reg-cancel').addEventListener('click', cancel);
    document.getElementById('zone-reg-guide-cancel').addEventListener('click', cancel);
    document.getElementById('zone-reg-redraw').addEventListener('click', startDraw);
    // 이름이 비면 등록 버튼을 끈다
    nameInput.addEventListener('input', updateSubmit);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        register();
    });
    // Esc: 확인 창이 열려 있으면 닫고, 그리는 중이면 취소한다
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
    // 확인 창을 연다(이미 그리는 중이거나 이미 열려 있으면 무시)
    function begin() {
        if (!active && confirmModal.hidden) {
            confirmModal.hidden = false;
            document.getElementById('zone-reg-ok').focus();
        }
    }

    function closeConfirm() {
        confirmModal.hidden = true;
    }

    // 그리기를 (다시) 시작한다: 이전 초안을 버리고 안내 상태로 돌아간다
    function startDraw() {
        // 이전에 보낸 미리보기 요청의 응답은 무시되도록 번호를 올린다
        previewRequest++;
        tool.cancel();
        draft = null;
        active = true;
        panel.hidden = false;
        refreshHint();
        guide.hidden = false;
        form.hidden = true;
        nameInput.value = '';
        showError('');
        setNameError('');
        // 지도에서 다각형 그리기를 켠다
        tool.startDraw();
    }

    // 그리는 중이던 것을 모두 취소하고 패널을 닫는다
    function cancel() {
        previewRequest++;
        tool?.cancel();
        active = false;
        draft = null;
        panel.hidden = true;
        refreshHint();
    }

    // 안내 문구는 동 단계에서 쉬는 중일 때만 보인다(그리는 중, 구역 수정 중에는 숨김)
    function refreshHint() {
        hint.hidden = !available || active || suppressed;
    }

    // 그리기를 마치면 미리보기 API로 면적·소속 동을 받아 카드에 보인다
    // 그리기가 끝나면 호출된다(도구가 건넨 경계). 면적·소속 동을 서버에서 계산해 온다
    async function preview(boundary) {
        guide.hidden = true;
        form.hidden = false;
        draft = null;
        area.textContent = '-';
        dong.textContent = '-';
        showError('');
        setNameError('');
        updateSubmit();
        // 이 요청의 번호. 응답이 올 때 최신인지 확인한다
        const request = ++previewRequest;
        try {
            const result = await post(
                '/api/v1/manage/zones/preview',
                { boundary },
                { toast: false },
            );
            if (request !== previewRequest) {
                return; // 그 사이 다시 그리기·취소를 눌렀다
            }
            // 등록 가능한 모양으로 확정
            draft = { boundary };
            area.textContent = `${formatNumber(Math.round(result.areaM2))}㎡`;
            dong.textContent = result.dongName;
        } catch (failure) {
            if (request !== previewRequest) {
                return;
            }
            reportFailure(failure, showError, setNameError);
        }
        updateSubmit();
    }

    // 등록(POST): 이름을 검사하고 서버에 만든다
    async function register() {
        if (!draft) {
            return;
        }
        const name = nameInput.value.trim();
        if (!name) {
            setNameError('구역 이름을 입력해 주세요.');
            return;
        }
        // 등록하는 동안 중복 클릭 방지
        submit.disabled = true;
        try {
            const created = await post(
                '/api/v1/manage/zones',
                { name, boundary: draft.boundary },
                { toast: false },
            );
            // 성공하면 패널을 닫고 토스트를 보인 뒤 대시보드가 값을 다시 읽는다
            cancel();
            showToast(`'${created.name}' 구역을 등록했습니다.`);
            await onRegistered(created);
        } catch (failure) {
            // 이름 중복은 이름 칸에, 그 밖은 위 오류 줄에 보인다
            reportFailure(
                failure,
                (message) =>
                    failure.code === 'DUPLICATE_ZONE_NAME'
                        ? setNameError(message)
                        : showError(message),
                setNameError,
            );
            updateSubmit();
        }
    }

    // 서버 오류 코드는 화면 문구로, 그 밖의 오류는 토스트(api.js는 toast:false라 직접 띄운다)
    // 서버 오류를 화면 문구로 바꿔 보인다. show: 일반 오류 줄, showName: 이름 칸
    function reportFailure(failure, show, showName) {
        // 서버 응답이 아닌 오류(네트워크 등)
        if (!(failure instanceof ApiError)) {
            showToast('요청을 보내지 못했습니다. 연결을 확인하고 다시 시도해 주세요.');
            return;
        }
        // 401은 api.js가 이미 로그인 화면으로 보냈다
        if (failure.status === 401) {
            return;
        }
        // 이름 필드 오류가 있으면 이름 칸에 보인다
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

    // 오류 줄 표시/숨김
    function showError(message) {
        error.textContent = message;
        error.hidden = !message;
    }

    // 이름 칸 오류 표시와 빨간 테두리
    function setNameError(message) {
        nameError.textContent = message;
        nameError.hidden = !message;
        nameInput.classList.toggle('err', Boolean(message));
    }

    // 등록 버튼은 미리보기가 끝나 모양이 정해졌고 이름이 있을 때만 켠다
    function updateSubmit() {
        submit.disabled = draft === null || nameInput.value.trim() === '';
    }

    // 화면이 쓰는 함수들만 공개한다
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
            refreshHint();
        },
        /** 구역을 수정하는 동안 true로 둔다(안내 문구를 숨긴다) */
        setSuppressed(on) {
            suppressed = on;
            refreshHint();
        },
        /** 그리는 중이거나 미리보기를 보고 있으면 true */
        isActive() {
            return active;
        },
        cancel,
    };
}
