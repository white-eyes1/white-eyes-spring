// 이벤트 처리 상태 변경 FNC-A-038의 화면 쪽: 목록의 [확인]이 부르는 요청과, [조치하기]로 여는 조치 완료 창(SCR-A-011).
// 서버 값은 textContent로만 넣는다(code-conventions 5-3). 오류 알림(409 '다른 관리자가 먼저 처리했습니다' 등)은
// 공용 API 모듈이 토스트로 띄운다.

// 공용 API 모듈. ApiError는 서버 오류 응답 예외, patch는 PATCH 요청 함수다.
import { ApiError, patch } from '../common/api.js';

// 조치 메모 최대 글자 수(카운터 표시용)와, 메모가 반드시 필요한 '기타' 조치 코드.
const NOTE_MAX_LENGTH = 200;
const OTHER_CODE = 'OTHER';

// 조치 완료 창(SCR-A-011)의 요소를 id로 한 번만 찾아 둔다.
const modal = document.getElementById('action-modal');
const form = document.getElementById('action-form');
const subtitle = document.getElementById('action-sub');
const noteInput = document.getElementById('action-note');
const noteCount = document.getElementById('action-note-count');
const saveButton = document.getElementById('action-save');

// current: 지금 조치 대상(이벤트 id·기대 상태·완료 콜백). opener: 창을 연 버튼(닫을 때 초점 복귀용). saving: 저장 요청 중 여부.
let current = null;
let opener = null;
let saving = false;

// requestStatusChange의 결과 값(저장됨·충돌·실패). 호출한 쪽이 이 상수로 결과를 구분한다.
export const CHANGE_SAVED = 'saved';
export const CHANGE_CONFLICT = 'conflict';
export const CHANGE_FAILED = 'failed';
// 409는 낙관적 잠금 충돌(다른 관리자가 먼저 상태를 바꿈)을 뜻하는 HTTP 상태 코드다.
const STATUS_CONFLICT = 409;

/**
 * 처리 상태를 바꾼다. 결과는 저장됨(saved), 다른 관리자가 먼저 처리함(conflict, 409), 그 밖의 실패(failed).
 * 오류 알림은 공용 API 모듈이 토스트로 띄우므로 여기서 다시 알리지 않는다.
 */
export async function requestStatusChange(eventId, body) {
    // expectedStatus 등이 담긴 body를 보낸다. 서버가 현재 상태와 다르면 409로 거절한다.
    try {
        await patch(`/api/v1/events/${eventId}/status`, body);
        return CHANGE_SAVED;
    } catch (error) {
        // 서버가 응답한 오류는 결과 코드로 바꿔 돌려주고, 예상 밖 예외는 그대로 던져 호출한 쪽에서 드러나게 한다.
        if (error instanceof ApiError) {
            return error.status === STATUS_CONFLICT ? CHANGE_CONFLICT : CHANGE_FAILED;
        }
        throw error;
    }
}

/**
 * 조치 완료 창을 연다. 저장에 성공하거나 409로 거절되면 창을 닫고 onDone()을 부른다(목록을 다시 읽는 일).
 * @param {{eventId: number, expectedStatus: string, summary: string, opener?: HTMLElement}} target
 */
export function openActionDialog(target, onDone) {
    // 대상 정보에 완료 콜백을 합쳐 보관한다.
    current = { ...target, onDone };
    // 창을 닫을 때 초점을 돌려줄 버튼을 기억한다(없을 수 있음).
    opener = target.opener ?? null;
    // 이전 선택과 메모를 지우고 요약 문구·저장 버튼 상태를 다시 계산한 뒤 창을 연다.
    form.reset();
    subtitle.textContent = target.summary;
    updateState();
    modal.hidden = false;
    form.focus();
}

// 이 창이 열려 있는지 알려 준다(아래 Esc 키 처리에서도 쓴다).
export function isActionDialogOpen() {
    return !modal.hidden;
}

// 창을 닫고 상태를 비운다. 열었던 버튼이 아직 화면에 있으면 거기로 초점을 돌린다(목록이 다시 그려져 사라졌을 수 있어 isConnected로 확인).
export function closeActionDialog() {
    modal.hidden = true;
    current = null;
    if (opener?.isConnected) {
        opener.focus();
    }
    opener = null;
}

// 선택된 조치 항목 라디오의 값. 아무것도 선택 안 했으면 null.
function selectedCode() {
    return form.querySelector('input[name="actionCode"]:checked')?.value ?? null;
}

// 저장 조건: 항목 선택 필수, 기타면 메모 필수(screens.md SCR-A-011). 필수값이 비면 저장 버튼을 막는다
function updateState() {
    const code = selectedCode();
    const note = noteInput.value.trim();
    // 메모 입력 글자 수를 '현재 / 최대' 형태로 보인다.
    noteCount.textContent = `${noteInput.value.length} / ${NOTE_MAX_LENGTH}`;
    // 저장 중이거나 항목 미선택이거나, '기타'인데 메모가 비었으면 저장 버튼을 막는다.
    saveButton.disabled = saving || code === null || (code === OTHER_CODE && note === '');
}

// 조치 완료(상태 RESOLVED) 저장. 버튼이 막힌 상태에서 Enter 제출로 들어오는 경우도 여기서 한 번 더 걸러 낸다.
async function save() {
    if (saveButton.disabled || current === null) {
        return;
    }
    // await 동안 current가 바뀌거나 비워질 수 있으므로 필요한 값을 먼저 꺼내 둔다.
    const { eventId, expectedStatus, onDone } = current;
    const note = noteInput.value.trim();
    // 중복 제출 방지를 위해 저장 중으로 표시하고 버튼 상태를 갱신한다.
    saving = true;
    updateState();
    // expectedStatus는 사용자가 목록에서 본 상태. 서버가 그 사이 바뀌었는지 비교(낙관적 잠금)하는 데 쓴다. 메모가 비면 null로 보낸다.
    const body = {
        expectedStatus,
        status: 'RESOLVED',
        actionCode: selectedCode(),
        actionNote: note === '' ? null : note,
    };
    let result;
    try {
        result = await requestStatusChange(eventId, body);
    // 성공·실패와 관계없이 저장 중 표시는 반드시 푼다.
    } finally {
        saving = false;
    }
    // 저장됐거나 다른 관리자가 먼저 처리했으면(409) 창을 닫고 새로 읽는다. 입력 오류(400) 등은 창을 열어 둔다
    if (result !== CHANGE_FAILED) {
        closeActionDialog();
        onDone();
    } else {
        updateState();
    }
}

// 이벤트 연결부: 제출은 페이지 이동 없이 save로, 항목·메모 변경은 저장 버튼 상태 갱신으로 연결한다.
form.addEventListener('submit', (event) => {
    event.preventDefault();
    save();
});
form.addEventListener('change', updateState);
noteInput.addEventListener('input', updateState);
// 닫기 버튼, 창 바깥(배경) 클릭, Esc 키로 창을 닫는다.
modal.querySelectorAll('[data-action-close]').forEach((button) => {
    button.addEventListener('click', closeActionDialog);
});
modal.addEventListener('mousedown', (event) => {
    if (event.target === modal) {
        closeActionDialog();
    }
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && isActionDialogOpen()) {
        closeActionDialog();
    }
});
