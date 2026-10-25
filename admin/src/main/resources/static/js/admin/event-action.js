// 이벤트 처리 상태 변경 FNC-A-038의 화면 쪽: 목록의 [확인]이 부르는 요청과, [조치하기]로 여는 조치 완료 창(SCR-A-011).
// 서버 값은 textContent로만 넣는다(code-conventions 5-3). 오류 알림(409 '다른 관리자가 먼저 처리했습니다' 등)은
// 공용 API 모듈이 토스트로 띄운다.

import { ApiError, patch } from '../common/api.js';

const NOTE_MAX_LENGTH = 200;
const OTHER_CODE = 'OTHER';

const modal = document.getElementById('action-modal');
const form = document.getElementById('action-form');
const subtitle = document.getElementById('action-sub');
const noteInput = document.getElementById('action-note');
const noteCount = document.getElementById('action-note-count');
const saveButton = document.getElementById('action-save');

let current = null;
let opener = null;
let saving = false;

export const CHANGE_SAVED = 'saved';
export const CHANGE_CONFLICT = 'conflict';
export const CHANGE_FAILED = 'failed';
const STATUS_CONFLICT = 409;

/**
 * 처리 상태를 바꾼다. 결과는 저장됨(saved), 다른 관리자가 먼저 처리함(conflict, 409), 그 밖의 실패(failed).
 * 오류 알림은 공용 API 모듈이 토스트로 띄우므로 여기서 다시 알리지 않는다.
 */
export async function requestStatusChange(eventId, body) {
    try {
        await patch(`/api/v1/events/${eventId}/status`, body);
        return CHANGE_SAVED;
    } catch (error) {
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
    current = { ...target, onDone };
    opener = target.opener ?? null;
    form.reset();
    subtitle.textContent = target.summary;
    updateState();
    modal.hidden = false;
    form.focus();
}

export function isActionDialogOpen() {
    return !modal.hidden;
}

export function closeActionDialog() {
    modal.hidden = true;
    current = null;
    if (opener?.isConnected) {
        opener.focus();
    }
    opener = null;
}

function selectedCode() {
    return form.querySelector('input[name="actionCode"]:checked')?.value ?? null;
}

// 저장 조건: 항목 선택 필수, 기타면 메모 필수(screens.md SCR-A-011). 필수값이 비면 저장 버튼을 막는다
function updateState() {
    const code = selectedCode();
    const note = noteInput.value.trim();
    noteCount.textContent = `${noteInput.value.length} / ${NOTE_MAX_LENGTH}`;
    saveButton.disabled = saving || code === null || (code === OTHER_CODE && note === '');
}

async function save() {
    if (saveButton.disabled || current === null) {
        return;
    }
    const { eventId, expectedStatus, onDone } = current;
    const note = noteInput.value.trim();
    saving = true;
    updateState();
    const body = {
        expectedStatus,
        status: 'RESOLVED',
        actionCode: selectedCode(),
        actionNote: note === '' ? null : note,
    };
    let result;
    try {
        result = await requestStatusChange(eventId, body);
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

form.addEventListener('submit', (event) => {
    event.preventDefault();
    save();
});
form.addEventListener('change', updateState);
noteInput.addEventListener('input', updateState);
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
