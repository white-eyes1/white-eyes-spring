// 구역 관리 화면(SCR-A-004). 시·구·동 선택(FNC-C-008), 다각형 미리보기(FNC-A-013), 구역 등록(FNC-A-014),
// 구역 수정(FNC-A-015), 구역 삭제(FNC-A-017), 이웃 설정 창(SCR-A-005, FNC-A-016).
// 공용 API 모듈(get/post/patch/put/del, 오류 예외), 숫자 표시, 토스트, 지도 위에 구역 다각형을 그리고 편집하는 모듈(zone-draw.js)을 가져온다.
import { ApiError, del, get, patch, post, put } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { createZoneMap } from '../../map/zone-draw.js';

// 서버 오류 코드를 사용자에게 보일 문구로 바꾸는 사전. 여기 없는 오류는 showError에서 서버 메시지를 쓴다.
const ERROR_MESSAGES = {
    INVALID_POLYGON:
        '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
    CAMERA_RUNNING:
        '가동 중이거나 가동을 요청한 캠이 있어 삭제할 수 없습니다. 캠을 먼저 정지해 주세요.',
    DONG_CHANGE_NOT_ALLOWED:
        '다른 동으로 바뀌어 저장할 수 없습니다. 같은 동 안에서만 모양을 바꿀 수 있습니다.',
};

// 시·구·동 선택 칸, 구역 목록, 새 구역 그리기 버튼과 안내, 새 구역 입력 카드(면적·소속 동·이름·오류·등록 버튼)의 요소들을 id로 한 번만 찾아 둔다.
const citySelect = document.getElementById('region-city');
const districtSelect = document.getElementById('region-district');
const dongSelect = document.getElementById('region-dong');
const listTitle = document.getElementById('zone-list-title');
const listElement = document.getElementById('zone-list');
const drawButton = document.getElementById('draw-start');
const drawGuide = document.getElementById('draw-guide');
const draftForm = document.getElementById('zone-draft');
const draftArea = document.getElementById('draft-area');
const draftDong = document.getElementById('draft-dong');
const draftError = document.getElementById('draft-error');
const draftName = document.getElementById('draft-name');
const draftNameError = document.getElementById('draft-name-error');
const draftSubmit = document.getElementById('draft-submit');
const redrawButton = document.getElementById('draft-redraw');

// 기존 구역 수정 카드의 요소들.
const editForm = document.getElementById('zone-edit');
const editArea = document.getElementById('edit-area');
const editDong = document.getElementById('edit-dong');
const editError = document.getElementById('edit-error');
const editName = document.getElementById('edit-name');
const editNameError = document.getElementById('edit-name-error');
const editSubmit = document.getElementById('edit-submit');

// 구역 삭제 확인 창의 요소들. deleteZoneTarget은 확인 창이 열려 있는 대상 구역이다.
const deleteModal = document.getElementById('delete-modal');
const deleteForm = document.getElementById('delete-form');
const deleteError = document.getElementById('delete-error');
const deleteConfirm = document.getElementById('delete-confirm');
// 삭제 확인 창이 열려 있는 구역
let deleteZoneTarget = null;

// 이웃 설정에서 쓰는 동서남북 방향 코드(N 북, E 동, S 남, W 서)와 그 방향별 선택 칸.
const DIRECTIONS = ['N', 'E', 'S', 'W'];
const neighborModal = document.getElementById('neighbor-modal');
const neighborForm = document.getElementById('neighbor-form');
const neighborError = document.getElementById('neighbor-error');
const neighborSave = document.getElementById('neighbor-save');
const neighborSelects = Object.fromEntries(
    DIRECTIONS.map((direction) => [direction, document.getElementById(`neighbor-${direction}`)]),
);
// 이웃 설정 창이 열려 있는 구역
let neighborZone = null;

// regions: 불러온 지역(시·구·동)을 id로 찾기 위한 보관소. selectedDong: 지금 고른 동. zones: 그 동의 구역들. selectedZoneId: 목록·지도에서 선택된 구역.
// 선택한 지역 id별 지역 정보(경계 포함)와 지금 보이는 구역 목록
const regions = new Map();
let selectedDong = null;
let zones = [];
let selectedZoneId = null;
// 미리보기를 마친 다각형: { boundary, dongId }
let draft = null;
// 수정 중인 구역: { zone, boundary(꼭짓점을 고쳤으면 새 다각형, 아니면 null), shapeOk(고친 모양을 저장할 수 있는지) }
let edit = null;

// 지도 모듈을 만든다. 지도에서 구역을 누르면 selectZone, 다각형을 다 그리면 previewDrawn이 불린다.
const zoneMap = createZoneMap(document.getElementById('zone-map'), {
    onZoneClick: selectZone,
    onDrawn: previewDrawn,
});

// 이벤트 연결부. 시를 고르면 구를, 구를 고르면 동을 새로 채우고 그 아래 단계는 비운다. 동을 고르면 구역을 불러온다.
citySelect.addEventListener('change', () =>
    onRegionChange(citySelect, districtSelect, [dongSelect]),
);
districtSelect.addEventListener('change', () => onRegionChange(districtSelect, dongSelect, []));
dongSelect.addEventListener('change', onDongChange);
drawButton.addEventListener('click', startDraw);
redrawButton.addEventListener('click', startDraw);
// 새 구역 입력 카드: 제출하면 등록하고, 이름을 입력하면 이름 오류를 지우고 등록 버튼 상태를 다시 계산한다. 수정 카드도 같은 방식이다.
draftForm.addEventListener('submit', (event) => {
    event.preventDefault();
    register();
});
draftName.addEventListener('input', () => {
    setNameError('');
    updateSubmit();
});
editForm.addEventListener('submit', (event) => {
    event.preventDefault();
    saveEdit();
});
editName.addEventListener('input', () => {
    setEditNameError('');
    updateEditSubmit();
});
// 수정 취소: restore=true로 고치던 모양을 버리고 원래 모양으로 되돌린다.
document.getElementById('edit-cancel').addEventListener('click', () => cancelEdit(true));

// 시작할 때 최상위 지역(시)을 불러와 첫 선택 칸을 채운다.
loadRegions(null, citySelect);

// ---- 시·구·동 선택 ----

// parentId 아래의 지역(null이면 최상위)을 받아 선택 칸에 추가하고 켠다. 응답의 경계(boundary)까지 regions에 보관해 두어 동을 고르면 지도에 바로 그린다.
async function loadRegions(parentId, select) {
    const items = await get('/api/v1/regions', parentId === null ? undefined : { parentId });
    items.forEach((region) => {
        regions.set(region.id, region);
        select.append(new Option(region.name, region.id));
    });
    select.disabled = false;
}

// select를 고르면 next를 새로 채우고, 그 아래 단계(rest)는 비운다
// 상위 칸의 값이 바뀌면 다음 칸을 비우고 새로 채우며, 그보다 아래 칸은 비운다. 이전에 보이던 구역·그리기 상태도 모두 지운다.
async function onRegionChange(select, next, rest) {
    resetSelect(next);
    rest.forEach(resetSelect);
    clearZones();
    if (select.value) {
        await loadRegions(Number(select.value), next);
    }
}

// 선택 칸을 안내 항목(첫 option)만 남기고 비운 뒤 잠근다.
function resetSelect(select) {
    const placeholder = select.options[0];
    select.replaceChildren(placeholder);
    select.value = '';
    select.disabled = true;
}

// 동을 고르면 진행 중이던 그리기·수정을 취소하고, 그 동의 경계를 지도에 보인 뒤 구역 목록을 불러온다. 동 선택을 해제하면 화면을 비운다.
async function onDongChange() {
    cancelDraft();
    cancelEdit(false);
    if (!dongSelect.value) {
        clearZones();
        return;
    }
    selectedDong = regions.get(Number(dongSelect.value));
    zoneMap.showDong(selectedDong.boundary);
    await loadZones();
}

// 동이 정해지지 않은 상태로 화면(상태, 지도, 목록)을 되돌린다.
function clearZones() {
    cancelDraft();
    cancelEdit(false);
    selectedDong = null;
    zones = [];
    selectedZoneId = null;
    zoneMap.showDong(null);
    zoneMap.showZones([]);
    renderList();
}

// 선택한 동의 구역을 서버에서 받아 지도와 목록에 반영한다.
async function loadZones() {
    zones = await get('/api/v1/zones', { dongId: selectedDong.id });
    selectedZoneId = null;
    zoneMap.showZones(zones);
    renderList();
}

// ---- 구역 목록 ----

// 동을 고른 상태에 따라 제목과 구역 목록을 그린다. 구역이 없으면 안내 문구 한 줄을 보인다.
function renderList() {
    listTitle.textContent = selectedDong
        ? `${selectedDong.name} 구역 ${zones.length}개`
        : '동을 선택해 주세요';
    if (zones.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'zone-empty';
        empty.textContent = selectedDong ? '등록된 구역이 없습니다. 새 구역을 그려 주세요.' : '';
        listElement.replaceChildren(empty);
        return;
    }
    listElement.replaceChildren(...zones.map(zoneRow));
}

// 구역 한 줄: 이름, 면적(㎡)·캠 유무, 이웃·수정·삭제 버튼. 줄을 누르면 구역이 선택된다.
function zoneRow(zone) {
    const row = document.createElement('div');
    row.className = 'zone-row' + (zone.id === selectedZoneId ? ' sel' : '');
    row.addEventListener('click', () => selectZone(zone.id));

    const info = document.createElement('div');
    info.className = 'info';
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = zone.name;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡ · 캠 ${zone.hasCamera ? '있음' : '없음'}`;
    info.append(name, meta);

    const actions = document.createElement('div');
    actions.className = 'row-actions';
    actions.append(neighborButton(zone), editButton(zone), deleteButton(zone));
    row.append(info, actions);
    return row;
}

// '이웃' 버튼. stopPropagation은 버튼을 눌러도 줄 전체의 선택 동작이 같이 일어나지 않게 하려는 것이다(수정·삭제 버튼도 같다).
function neighborButton(zone) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '이웃';
    button.addEventListener('click', (event) => {
        event.stopPropagation();
        openNeighborModal(zone);
    });
    return button;
}

function editButton(zone) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = '수정';
    button.addEventListener('click', (event) => {
        event.stopPropagation();
        startEdit(zone);
    });
    return button;
}

function deleteButton(zone) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn danger sm';
    button.textContent = '삭제';
    button.addEventListener('click', (event) => {
        event.stopPropagation();
        openDeleteModal(zone);
    });
    return button;
}

// 구역을 선택(지도 강조와 목록 선택 표시). 다른 구역의 꼭짓점을 고치는 중에는 선택을 바꾸지 않는다.
function selectZone(zoneId) {
    // 모양을 고치는 중에는 다른 구역을 고르지 않는다(꼭짓점 편집과 겹친다)
    if (edit && zoneId !== edit.zone.id) {
        return;
    }
    selectedZoneId = zoneId;
    zoneMap.select(zoneId);
    renderList();
}

// ---- 새 구역 그리기 ----

// 새 구역 그리기를 시작한다. 진행 중이던 이전 미리보기와 수정은 정리하고 지도를 그리기 모드로 바꾼다.
function startDraw() {
    cancelDraft();
    cancelEdit(true);
    drawGuide.hidden = false;
    zoneMap.startDraw();
}

// 그리던 다각형과 입력 카드를 모두 초기화한다.
function cancelDraft() {
    zoneMap.cancelDraw();
    draft = null;
    drawGuide.hidden = true;
    draftForm.hidden = true;
    draftName.value = '';
    showDraftError('');
    setNameError('');
}

// 그리기를 마치면 미리보기 API로 면적·소속 동을 받아 카드에 보인다
// 다각형을 다 그리면 서버 미리보기 API로 면적과 소속 동을 받아 카드에 보인다(FNC-A-013). 아직 등록하지 않으므로 DB에는 저장되지 않는다.
async function previewDrawn(boundary) {
    // 응답을 기다리는 동안 카드를 먼저 열고 값은 '-'로 두어, 받기 전에는 등록 버튼이 켜지지 않게 한다(draft가 null).
    drawGuide.hidden = true;
    draft = null;
    draftForm.hidden = false;
    draftArea.textContent = '-';
    draftDong.textContent = '-';
    showDraftError('');
    setNameError('');
    updateSubmit();
    try {
        // toast:false: 오류를 카드 안 문구로 보이려고 공용 토스트를 끈다.
        const preview = await post('/api/v1/manage/zones/preview', { boundary }, { toast: false });
        // 등록에 쓸 다각형과 소속 동 id를 보관한다.
        draft = { boundary, dongId: preview.dongId };
        draftArea.textContent = `${formatNumber(Math.round(preview.areaM2))}㎡`;
        draftDong.textContent = preview.dongName;
    } catch (error) {
        showError(error, showDraftError);
    }
    updateSubmit();
}

// 구역 등록(FNC-A-014). 미리보기가 끝나지 않았거나 이름이 비면 서버에 보내지 않는다.
async function register() {
    if (!draft) {
        return;
    }
    const name = draftName.value.trim();
    if (!name) {
        setNameError('구역 이름을 입력해 주세요.');
        return;
    }
    // 중복 제출을 막는다.
    draftSubmit.disabled = true;
    try {
        const created = await post(
            '/api/v1/manage/zones',
            { name, boundary: draft.boundary },
            { toast: false },
        );
        await afterRegistered(created);
    // 이름 중복 오류는 이름 칸 아래에, 그 밖의 오류는 카드 위 문구에 보인다.
    } catch (error) {
        showError(
            error,
            (message) =>
                error.code === 'DUPLICATE_ZONE_NAME'
                    ? setNameError(message)
                    : showDraftError(message),
            setNameError,
        );
        updateSubmit();
    }
}

// 등록이 끝나면 카드를 닫고 알린다. 지금 보는 동에 등록됐으면 목록을 다시 읽어 새 구역을 선택하고, 다른 동이면 목록에는 안 보이므로 그 사정을 알린다.
async function afterRegistered(created) {
    cancelDraft();
    showToast(`'${created.name}' 구역을 등록했습니다.`);
    if (selectedDong && created.dongId === selectedDong.id) {
        await loadZones();
        selectZone(created.id);
    } else {
        showToast('다른 동에 등록되었습니다. 해당 동을 선택하면 목록에 보입니다.');
    }
}

// 서버 오류 코드는 화면 문구로 바꿔 보인다. 그 밖의 오류는 토스트(api.js는 toast:false라 직접 띄운다)
// 공용 오류 처리. 서버 오류가 아니거나 401(로그인 만료, 공용 모듈이 처리)이면 아무것도 안 한다. 이름 칸 검증 오류는 이름 칸 아래에, 알려진 오류 코드는 사전의 문구를, 400은 서버 메시지를 show로 보이고, 그 외에는 토스트로 알린다.
function showError(error, show, showName) {
    if (!(error instanceof ApiError) || error.status === 401) {
        return;
    }
    const fieldMessage = error.fieldErrors.find((item) => item.field === 'name')?.message;
    if (fieldMessage) {
        showName(fieldMessage);
    } else if (ERROR_MESSAGES[error.code]) {
        show(ERROR_MESSAGES[error.code]);
    } else if (error.status === 400) {
        show(error.message);
    } else {
        showToast(error.message);
    }
}

// 새 구역 카드 위 오류 문구 표시. 빈 문자열이면 숨긴다.
function showDraftError(message) {
    draftError.textContent = message;
    draftError.hidden = !message;
}

// 새 구역 이름 칸 아래 오류와 빨간 테두리를 켜고 끈다.
function setNameError(message) {
    draftNameError.textContent = message;
    draftNameError.hidden = !message;
    draftName.classList.toggle('err', Boolean(message));
}

// 미리보기가 끝나고 이름이 있을 때만 등록 버튼을 켠다.
function updateSubmit() {
    draftSubmit.disabled = draft === null || draftName.value.trim() === '';
}

// ---- 구역 수정(FNC-A-015) ----

// 목록의 '수정'을 누르면 그 구역의 꼭짓점을 지도에서 고칠 수 있게 하고, 고칠 때마다 미리보기로 면적·소속 동을 다시 보인다
// 수정 시작(FNC-A-015). edit에 대상과 상태를 담고 지도에서 그 구역의 꼭짓점 편집을 켠다. 이름은 현재 값으로 채운다.
function startEdit(zone) {
    cancelDraft();
    cancelEdit(true);
    edit = { zone, boundary: null, shapeOk: true };
    selectZone(zone.id);
    editForm.hidden = false;
    document.getElementById('edit-title').textContent = `'${zone.name}' 수정`;
    editName.value = zone.name;
    editArea.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡`;
    editDong.textContent = selectedDong ? selectedDong.name : '-';
    showEditError('');
    setEditNameError('');
    updateEditSubmit();
    zoneMap.startEdit(zone.id, onShapeEdited);
}

// 꼭짓점을 고칠 때마다: 미리보기로 새 면적·소속 동을 받아 보이고, 다른 동으로 넘어가면 저장을 막는다
// 수정 미리보기 요청 번호. 꼭짓점을 연달아 옮길 때 늦게 도착한 이전 응답이 최신 결과를 덮어쓰지 않게 한다.
let editPreviewRequest = 0;
// 꼭짓점을 고칠 때마다 지도 모듈이 부른다. 응답이 올 때까지는 shapeOk=false로 저장을 막고, 미리보기로 받은 새 면적을 보인다.
async function onShapeEdited(boundary) {
    if (!edit) {
        return;
    }
    const request = ++editPreviewRequest;
    const { zone } = edit;
    edit.boundary = boundary;
    edit.shapeOk = false;
    showEditError('');
    updateEditSubmit();
    try {
        const preview = await post('/api/v1/manage/zones/preview', { boundary }, { toast: false });
        // 응답을 기다리는 사이 수정이 취소됐거나 더 새 요청이 있으면 버린다.
        if (!edit || request !== editPreviewRequest) {
            return;
        }
        editArea.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡ → ${formatNumber(Math.round(preview.areaM2))}㎡`;
        editDong.textContent = preview.dongName;
        // 같은 동 안에서만 모양을 바꿀 수 있다. 다른 동으로 넘어가면 저장을 막고 이유를 알린다.
        if (preview.dongId === zone.dongId) {
            edit.shapeOk = true;
        } else {
            showEditError(ERROR_MESSAGES.DONG_CHANGE_NOT_ALLOWED);
        }
    } catch (error) {
        if (edit && request === editPreviewRequest) {
            showError(error, showEditError);
        }
    }
    updateEditSubmit();
}

// 수정 저장. 이름은 필수이고, 서버에는 바뀐 항목(이름, 다각형)만 보낸다.
async function saveEdit() {
    if (!edit) {
        return;
    }
    const { zone } = edit;
    const name = editName.value.trim();
    if (!name) {
        setEditNameError('구역 이름을 입력해 주세요.');
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
    editSubmit.disabled = true;
    try {
        // 성공하면 수정 모드를 끝내고 목록을 다시 읽어 수정된 구역을 선택한다.
        const updated = await patch(`/api/v1/manage/zones/${zone.id}`, body, { toast: false });
        zoneMap.stopEdit();
        edit = null;
        editForm.hidden = true;
        showToast(`'${updated.name}' 구역을 수정했습니다.`);
        await loadZones();
        selectZone(updated.id);
    // 이름 중복은 이름 칸 아래에, 그 밖은 카드 위 문구로 보인다. 저장 버튼 상태는 다시 계산한다.
    } catch (error) {
        showError(
            error,
            (message) =>
                error.code === 'DUPLICATE_ZONE_NAME'
                    ? setEditNameError(message)
                    : showEditError(message),
            setEditNameError,
        );
        updateEditSubmit();
    }
}

// 수정을 끝낸다. restore가 true면 고치던 모양을 버리고 원래 모양으로 다시 그린다
// 수정을 끝낸다. restore가 true면 고치던 모양을 버리고 원래 구역들을 다시 그린다. 진행 중인 미리보기 응답은 요청 번호를 올려 무효로 한다.
function cancelEdit(restore) {
    if (!edit) {
        return;
    }
    editPreviewRequest++;
    zoneMap.stopEdit();
    edit = null;
    editForm.hidden = true;
    if (restore) {
        zoneMap.showZones(zones);
        zoneMap.select(selectedZoneId);
    }
}

// 수정 카드 위 오류 문구 표시.
function showEditError(message) {
    editError.textContent = message;
    editError.hidden = !message;
}

// 수정 카드 이름 칸 오류와 빨간 테두리를 켜고 끈다.
function setEditNameError(message) {
    editNameError.textContent = message;
    editNameError.hidden = !message;
    editName.classList.toggle('err', Boolean(message));
}

// 이름을 바꿨거나 모양을 고쳤고, 고친 모양을 저장할 수 있을 때만 저장 버튼을 켠다
// 저장 버튼을 켤 조건: 모양 저장 가능 상태이고, 이름이 바뀌었거나 모양이 고쳐졌을 때. 아무것도 안 바뀐 채 저장하는 일을 막는다.
function updateEditSubmit() {
    if (!edit) {
        editSubmit.disabled = true;
        return;
    }
    const nameChanged = editName.value.trim() !== edit.zone.name && editName.value.trim() !== '';
    editSubmit.disabled = !edit.shapeOk || !(nameChanged || edit.boundary !== null);
}

// ---- 구역 삭제(FNC-A-017) ----

// 삭제 확인 창을 연다(FNC-A-017). 이전 오류 문구는 지운다.
function openDeleteModal(zone) {
    deleteZoneTarget = zone;
    document.getElementById('delete-message').textContent = `'${zone.name}' 구역을 삭제할까요?`;
    showDeleteError('');
    deleteConfirm.disabled = false;
    deleteModal.hidden = false;
}

// 삭제 확인 창을 닫고 대상을 비운다.
function closeDeleteModal() {
    deleteModal.hidden = true;
    deleteZoneTarget = null;
}

// 삭제 요청. 서버가 캠이 가동 중이면 오류(CAMERA_RUNNING)로 거절하며, 그 문구는 창 안에 보인다.
async function confirmDelete(event) {
    event.preventDefault();
    if (!deleteZoneTarget) {
        return;
    }
    const zone = deleteZoneTarget;
    deleteConfirm.disabled = true;
    try {
        const result = await del(`/api/v1/manage/zones/${zone.id}`, { toast: false });
        // 삭제한 구역을 고치는 중이었다면 수정 상태도 같이 정리한다.
        // 지운 구역을 수정하던 중이었다면 수정도 끝낸다
        if (edit && edit.zone.id === zone.id) {
            cancelEdit(false);
        }
        closeDeleteModal();
        // mode가 SOFT면 가동 기록 때문에 데이터는 남기고 표시만 지운 삭제이므로 그 사실을 함께 알린다.
        showToast(
            result.mode === 'SOFT'
                ? `'${zone.name}' 구역을 삭제했습니다. 가동 기록은 남아 있습니다.`
                : `'${zone.name}' 구역을 삭제했습니다.`,
        );
        await loadZones();
    } catch (error) {
        showError(error, showDeleteError);
        deleteConfirm.disabled = false;
    }
}

// 삭제 창 안 오류 문구 표시.
function showDeleteError(message) {
    deleteError.textContent = message;
    deleteError.hidden = !message;
}

// 삭제 창 이벤트 연결: 제출, 닫기 버튼, 배경 클릭, Esc 키.
deleteForm.addEventListener('submit', confirmDelete);
deleteModal
    .querySelectorAll('[data-close]')
    .forEach((button) => button.addEventListener('click', closeDeleteModal));
deleteModal.addEventListener('mousedown', (event) => {
    if (event.target === deleteModal) {
        closeDeleteModal();
    }
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !deleteModal.hidden) {
        closeDeleteModal();
    }
});

// ---- 이웃 설정 창(SCR-A-005, FNC-A-016) ----

// 이웃 설정 창을 열고(FNC-A-016) 후보와 현재 설정을 불러온다. 로딩 중에는 저장을 막는다.
async function openNeighborModal(zone) {
    neighborZone = zone;
    document.getElementById('neighbor-zone-name').textContent = zone.name;
    document.getElementById('neighbor-center').textContent = zone.name;
    showNeighborError('');
    neighborModal.hidden = false;
    neighborSave.disabled = true;
    DIRECTIONS.forEach((direction) =>
        neighborSelects[direction].replaceChildren(new Option('불러오는 중…', '')),
    );
    try {
        // 후보: 삭제되지 않은 모든 구역(다른 동 포함)을 이 구역 중심점에서 가까운 순으로
        // 후보(전체 구역)와 이 구역의 현재 이웃 설정을 동시에 요청한다.
        const [all, current] = await Promise.all([
            get('/api/v1/zones'),
            get(`/api/v1/manage/zones/${zone.id}/neighbors`),
        ]);
        // 자기 자신은 후보에서 빼고, 중심점 거리를 계산해 가까운 순으로 정렬한다.
        const candidates = all
            .filter((other) => other.id !== zone.id)
            .map((other) => ({ ...other, distance: distanceMeters(zone, other) }))
            .sort((first, second) => first.distance - second.distance);
        // 방향마다 선택 칸을 채운다: 없음(이웃 없음) 항목과 후보 목록. 현재 설정된 이웃이 있으면 그 값을 선택해 둔다.
        DIRECTIONS.forEach((direction) => {
            const select = neighborSelects[direction];
            select.replaceChildren(
                new Option('없음(관측 범위 밖)', ''),
                ...candidates.map(
                    (other) =>
                        new Option(`${other.name} (${formatDistance(other.distance)})`, other.id),
                ),
            );
            select.value = current[direction] ? String(current[direction].id) : '';
        });
        neighborSave.disabled = false;
    } catch {
        // 조회 실패는 api.js가 토스트로 알렸다. 저장은 막아 두고 창은 닫을 수 있게 둔다
        DIRECTIONS.forEach((direction) =>
            neighborSelects[direction].replaceChildren(new Option('불러오지 못했습니다', '')),
        );
    }
}

// 이웃 설정 창을 닫고 지도 선택을 원래 구역으로 되돌린다.
function closeNeighborModal() {
    neighborModal.hidden = true;
    neighborZone = null;
    zoneMap.select(selectedZoneId);
}

// 이웃 저장. 방향별 선택값을 id 또는 null(이웃 없음)로 바꿔 PUT으로 보낸다. 서버가 상대 구역의 반대 방향도 함께 맞춘다.
async function saveNeighbors(event) {
    event.preventDefault();
    if (!neighborZone) {
        return;
    }
    const body = Object.fromEntries(
        DIRECTIONS.map((direction) => [
            direction,
            neighborSelects[direction].value ? Number(neighborSelects[direction].value) : null,
        ]),
    );
    neighborSave.disabled = true;
    showNeighborError('');
    try {
        await put(`/api/v1/manage/zones/${neighborZone.id}/neighbors`, body, { toast: false });
        closeNeighborModal();
        showToast('이웃을 저장했습니다. 상대 구역의 반대 방향도 함께 바뀌었습니다.');
    } catch (error) {
        // 401은 공용 모듈이 로그인 화면으로 처리하므로 제외. 404는 구역이 사라진 경우라 새로 불러오라는 안내를 보인다. 실패하면 다시 저장할 수 있게 버튼을 푼다.
        if (error instanceof ApiError && error.status !== 401) {
            showNeighborError(
                error.status === 404
                    ? '구역을 찾을 수 없습니다. 목록을 새로 불러온 뒤 다시 시도해 주세요.'
                    : error.message,
            );
        }
        neighborSave.disabled = false;
    }
}

// 이웃 설정 창 안 오류 문구 표시.
function showNeighborError(message) {
    neighborError.textContent = message;
    neighborError.hidden = !message;
}

// 두 구역 중심점 사이 거리(m). 가까운 순 정렬과 목록 표시에만 쓰므로 근사식(등장방형)으로 충분하다
// 위도 1도를 약 111,320m로 보고, 경도 거리는 위도의 cos 값으로 줄여 계산한 뒤 두 점의 직선 거리를 구한다.
function distanceMeters(from, to) {
    const metersPerDegree = 111_320;
    const dy = (Number(to.centerLat) - Number(from.centerLat)) * metersPerDegree;
    const dx =
        (Number(to.centerLng) - Number(from.centerLng)) *
        metersPerDegree *
        Math.cos((Number(from.centerLat) * Math.PI) / 180);
    return Math.hypot(dx, dy);
}

// 1km 이상은 km(소수 첫째 자리), 그보다 가까우면 m로 보인다.
function formatDistance(meters) {
    return meters >= 1000 ? `${(meters / 1000).toFixed(1)}km` : `${Math.round(meters)}m`;
}

// 이웃 선택 칸에서 구역을 고르면 지도에서 그 구역을 강조한다. 비우면 설정 중인 구역을 강조한다.
DIRECTIONS.forEach((direction) => {
    // 고른 구역을 지도에서 강조한다(지금 보는 동에 없는 구역이면 그대로)
    neighborSelects[direction].addEventListener('change', () => {
        const id = Number(neighborSelects[direction].value);
        zoneMap.select(id || neighborZone?.id || null);
    });
});
// 이웃 창 이벤트 연결: 저장, 닫기 버튼, 배경 클릭, Esc 키.
neighborForm.addEventListener('submit', saveNeighbors);
neighborModal
    .querySelectorAll('[data-close]')
    .forEach((button) => button.addEventListener('click', closeNeighborModal));
neighborModal.addEventListener('mousedown', (event) => {
    if (event.target === neighborModal) {
        closeNeighborModal();
    }
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !neighborModal.hidden) {
        closeNeighborModal();
    }
});
