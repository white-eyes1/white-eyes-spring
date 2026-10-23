// 구역 관리 화면(SCR-A-004). 시·구·동 선택(FNC-C-008), 다각형 미리보기(FNC-A-013), 구역 등록(FNC-A-014),
// 구역 수정(FNC-A-015), 구역 삭제(FNC-A-017), 이웃 설정 창(SCR-A-005, FNC-A-016).
import { ApiError, del, get, patch, post, put } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { createZoneMap } from '../../map/zone-draw.js';

const ERROR_MESSAGES = {
    INVALID_POLYGON: '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
    CAMERA_RUNNING: '가동 중이거나 가동을 요청한 캠이 있어 삭제할 수 없습니다. 캠을 먼저 정지해 주세요.',
    DONG_CHANGE_NOT_ALLOWED: '다른 동으로 바뀌어 저장할 수 없습니다. 같은 동 안에서만 모양을 바꿀 수 있습니다.',
};

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

const editForm = document.getElementById('zone-edit');
const editArea = document.getElementById('edit-area');
const editDong = document.getElementById('edit-dong');
const editError = document.getElementById('edit-error');
const editName = document.getElementById('edit-name');
const editNameError = document.getElementById('edit-name-error');
const editSubmit = document.getElementById('edit-submit');

const deleteModal = document.getElementById('delete-modal');
const deleteForm = document.getElementById('delete-form');
const deleteError = document.getElementById('delete-error');
const deleteConfirm = document.getElementById('delete-confirm');
// 삭제 확인 창이 열려 있는 구역
let deleteZoneTarget = null;

const DIRECTIONS = ['N', 'E', 'S', 'W'];
const neighborModal = document.getElementById('neighbor-modal');
const neighborForm = document.getElementById('neighbor-form');
const neighborError = document.getElementById('neighbor-error');
const neighborSave = document.getElementById('neighbor-save');
const neighborSelects = Object.fromEntries(DIRECTIONS.map((direction) => [direction, document.getElementById(`neighbor-${direction}`)]));
// 이웃 설정 창이 열려 있는 구역
let neighborZone = null;

// 선택한 지역 id별 지역 정보(경계 포함)와 지금 보이는 구역 목록
const regions = new Map();
let selectedDong = null;
let zones = [];
let selectedZoneId = null;
// 미리보기를 마친 다각형: { boundary, dongId }
let draft = null;
// 수정 중인 구역: { zone, boundary(꼭짓점을 고쳤으면 새 다각형, 아니면 null), shapeOk(고친 모양을 저장할 수 있는지) }
let edit = null;

const zoneMap = createZoneMap(document.getElementById('zone-map'), {
    onZoneClick: selectZone,
    onDrawn: previewDrawn,
});

citySelect.addEventListener('change', () => onRegionChange(citySelect, districtSelect, [dongSelect]));
districtSelect.addEventListener('change', () => onRegionChange(districtSelect, dongSelect, []));
dongSelect.addEventListener('change', onDongChange);
drawButton.addEventListener('click', startDraw);
redrawButton.addEventListener('click', startDraw);
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
document.getElementById('edit-cancel').addEventListener('click', () => cancelEdit(true));

loadRegions(null, citySelect);

// ---- 시·구·동 선택 ----

async function loadRegions(parentId, select) {
    const items = await get('/api/v1/regions', parentId === null ? undefined : { parentId });
    items.forEach((region) => {
        regions.set(region.id, region);
        select.append(new Option(region.name, region.id));
    });
    select.disabled = false;
}

// select를 고르면 next를 새로 채우고, 그 아래 단계(rest)는 비운다
async function onRegionChange(select, next, rest) {
    resetSelect(next);
    rest.forEach(resetSelect);
    clearZones();
    if (select.value) {
        await loadRegions(Number(select.value), next);
    }
}

function resetSelect(select) {
    const placeholder = select.options[0];
    select.replaceChildren(placeholder);
    select.value = '';
    select.disabled = true;
}

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

async function loadZones() {
    zones = await get('/api/v1/zones', { dongId: selectedDong.id });
    selectedZoneId = null;
    zoneMap.showZones(zones);
    renderList();
}

// ---- 구역 목록 ----

function renderList() {
    listTitle.textContent = selectedDong ? `${selectedDong.name} 구역 ${zones.length}개` : '동을 선택해 주세요';
    if (zones.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'zone-empty';
        empty.textContent = selectedDong ? '등록된 구역이 없습니다. 새 구역을 그려 주세요.' : '';
        listElement.replaceChildren(empty);
        return;
    }
    listElement.replaceChildren(...zones.map(zoneRow));
}

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
    actions.append(
        neighborButton(zone),
        editButton(zone),
        deleteButton(zone),
    );
    row.append(info, actions);
    return row;
}

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

function startDraw() {
    cancelDraft();
    cancelEdit(true);
    drawGuide.hidden = false;
    zoneMap.startDraw();
}

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
async function previewDrawn(boundary) {
    drawGuide.hidden = true;
    draft = null;
    draftForm.hidden = false;
    draftArea.textContent = '-';
    draftDong.textContent = '-';
    showDraftError('');
    setNameError('');
    updateSubmit();
    try {
        const preview = await post('/api/v1/manage/zones/preview', { boundary }, { toast: false });
        draft = { boundary, dongId: preview.dongId };
        draftArea.textContent = `${formatNumber(Math.round(preview.areaM2))}㎡`;
        draftDong.textContent = preview.dongName;
    } catch (error) {
        showError(error, showDraftError);
    }
    updateSubmit();
}

async function register() {
    if (!draft) {
        return;
    }
    const name = draftName.value.trim();
    if (!name) {
        setNameError('구역 이름을 입력해 주세요.');
        return;
    }
    draftSubmit.disabled = true;
    try {
        const created = await post('/api/v1/manage/zones', { name, boundary: draft.boundary }, { toast: false });
        await afterRegistered(created);
    } catch (error) {
        showError(error, (message) => (error.code === 'DUPLICATE_ZONE_NAME' ? setNameError(message) : showDraftError(message)), setNameError);
        updateSubmit();
    }
}

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

function showDraftError(message) {
    draftError.textContent = message;
    draftError.hidden = !message;
}

function setNameError(message) {
    draftNameError.textContent = message;
    draftNameError.hidden = !message;
    draftName.classList.toggle('err', Boolean(message));
}

function updateSubmit() {
    draftSubmit.disabled = draft === null || draftName.value.trim() === '';
}

// ---- 구역 수정(FNC-A-015) ----

// 목록의 '수정'을 누르면 그 구역의 꼭짓점을 지도에서 고칠 수 있게 하고, 고칠 때마다 미리보기로 면적·소속 동을 다시 보인다
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
let editPreviewRequest = 0;
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
        if (!edit || request !== editPreviewRequest) {
            return;
        }
        editArea.textContent = `${formatNumber(Math.round(zone.areaM2))}㎡ → ${formatNumber(Math.round(preview.areaM2))}㎡`;
        editDong.textContent = preview.dongName;
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
        const updated = await patch(`/api/v1/manage/zones/${zone.id}`, body, { toast: false });
        zoneMap.stopEdit();
        edit = null;
        editForm.hidden = true;
        showToast(`'${updated.name}' 구역을 수정했습니다.`);
        await loadZones();
        selectZone(updated.id);
    } catch (error) {
        showError(error, (message) => (error.code === 'DUPLICATE_ZONE_NAME' ? setEditNameError(message) : showEditError(message)), setEditNameError);
        updateEditSubmit();
    }
}

// 수정을 끝낸다. restore가 true면 고치던 모양을 버리고 원래 모양으로 다시 그린다
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

function showEditError(message) {
    editError.textContent = message;
    editError.hidden = !message;
}

function setEditNameError(message) {
    editNameError.textContent = message;
    editNameError.hidden = !message;
    editName.classList.toggle('err', Boolean(message));
}

// 이름을 바꿨거나 모양을 고쳤고, 고친 모양을 저장할 수 있을 때만 저장 버튼을 켠다
function updateEditSubmit() {
    if (!edit) {
        editSubmit.disabled = true;
        return;
    }
    const nameChanged = editName.value.trim() !== edit.zone.name && editName.value.trim() !== '';
    editSubmit.disabled = !edit.shapeOk || !(nameChanged || edit.boundary !== null);
}

// ---- 구역 삭제(FNC-A-017) ----

function openDeleteModal(zone) {
    deleteZoneTarget = zone;
    document.getElementById('delete-message').textContent = `'${zone.name}' 구역을 삭제할까요?`;
    showDeleteError('');
    deleteConfirm.disabled = false;
    deleteModal.hidden = false;
}

function closeDeleteModal() {
    deleteModal.hidden = true;
    deleteZoneTarget = null;
}

async function confirmDelete(event) {
    event.preventDefault();
    if (!deleteZoneTarget) {
        return;
    }
    const zone = deleteZoneTarget;
    deleteConfirm.disabled = true;
    try {
        const result = await del(`/api/v1/manage/zones/${zone.id}`, { toast: false });
        // 지운 구역을 수정하던 중이었다면 수정도 끝낸다
        if (edit && edit.zone.id === zone.id) {
            cancelEdit(false);
        }
        closeDeleteModal();
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

function showDeleteError(message) {
    deleteError.textContent = message;
    deleteError.hidden = !message;
}

deleteForm.addEventListener('submit', confirmDelete);
deleteModal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeDeleteModal));
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

async function openNeighborModal(zone) {
    neighborZone = zone;
    document.getElementById('neighbor-zone-name').textContent = zone.name;
    document.getElementById('neighbor-center').textContent = zone.name;
    showNeighborError('');
    neighborModal.hidden = false;
    neighborSave.disabled = true;
    DIRECTIONS.forEach((direction) => neighborSelects[direction].replaceChildren(new Option('불러오는 중…', '')));
    try {
        // 후보: 삭제되지 않은 모든 구역(다른 동 포함)을 이 구역 중심점에서 가까운 순으로
        const [all, current] = await Promise.all([get('/api/v1/zones'), get(`/api/v1/manage/zones/${zone.id}/neighbors`)]);
        const candidates = all
            .filter((other) => other.id !== zone.id)
            .map((other) => ({ ...other, distance: distanceMeters(zone, other) }))
            .sort((first, second) => first.distance - second.distance);
        DIRECTIONS.forEach((direction) => {
            const select = neighborSelects[direction];
            select.replaceChildren(
                new Option('없음(관측 범위 밖)', ''),
                ...candidates.map((other) => new Option(`${other.name} (${formatDistance(other.distance)})`, other.id)),
            );
            select.value = current[direction] ? String(current[direction].id) : '';
        });
        neighborSave.disabled = false;
    } catch {
        // 조회 실패는 api.js가 토스트로 알렸다. 저장은 막아 두고 창은 닫을 수 있게 둔다
        DIRECTIONS.forEach((direction) => neighborSelects[direction].replaceChildren(new Option('불러오지 못했습니다', '')));
    }
}

function closeNeighborModal() {
    neighborModal.hidden = true;
    neighborZone = null;
    zoneMap.select(selectedZoneId);
}

async function saveNeighbors(event) {
    event.preventDefault();
    if (!neighborZone) {
        return;
    }
    const body = Object.fromEntries(DIRECTIONS.map((direction) => [direction, neighborSelects[direction].value ? Number(neighborSelects[direction].value) : null]));
    neighborSave.disabled = true;
    showNeighborError('');
    try {
        await put(`/api/v1/manage/zones/${neighborZone.id}/neighbors`, body, { toast: false });
        closeNeighborModal();
        showToast('이웃을 저장했습니다. 상대 구역의 반대 방향도 함께 바뀌었습니다.');
    } catch (error) {
        if (error instanceof ApiError && error.status !== 401) {
            showNeighborError(error.status === 404 ? '구역을 찾을 수 없습니다. 목록을 새로 불러온 뒤 다시 시도해 주세요.' : error.message);
        }
        neighborSave.disabled = false;
    }
}

function showNeighborError(message) {
    neighborError.textContent = message;
    neighborError.hidden = !message;
}

// 두 구역 중심점 사이 거리(m). 가까운 순 정렬과 목록 표시에만 쓰므로 근사식(등장방형)으로 충분하다
function distanceMeters(from, to) {
    const metersPerDegree = 111_320;
    const dy = (Number(to.centerLat) - Number(from.centerLat)) * metersPerDegree;
    const dx = (Number(to.centerLng) - Number(from.centerLng)) * metersPerDegree * Math.cos((Number(from.centerLat) * Math.PI) / 180);
    return Math.hypot(dx, dy);
}

function formatDistance(meters) {
    return meters >= 1000 ? `${(meters / 1000).toFixed(1)}km` : `${Math.round(meters)}m`;
}

DIRECTIONS.forEach((direction) => {
    // 고른 구역을 지도에서 강조한다(지금 보는 동에 없는 구역이면 그대로)
    neighborSelects[direction].addEventListener('change', () => {
        const id = Number(neighborSelects[direction].value);
        zoneMap.select(id || neighborZone?.id || null);
    });
});
neighborForm.addEventListener('submit', saveNeighbors);
neighborModal.querySelectorAll('[data-close]').forEach((button) => button.addEventListener('click', closeNeighborModal));
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
