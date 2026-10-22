// 구역 관리 화면(SCR-A-004). 시·구·동 선택(FNC-C-008), 다각형 미리보기(FNC-A-013), 구역 등록(FNC-A-014).
// 이웃·수정·삭제 버튼은 2·3단계에서 붙인다(자리만 비활성으로 둔다).
import { ApiError, get, post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { showToast } from '../../common/toast.js';
import { createZoneMap } from '../../map/zone-draw.js';

const ERROR_MESSAGES = {
    INVALID_POLYGON: '다각형이 올바르지 않습니다. 선이 겹치지 않게 세 점 이상으로 다시 그려 주세요.',
    OUT_OF_SERVICE_AREA: '서비스 지역 밖입니다. 서비스 지역 안에서 다시 그려 주세요.',
    DUPLICATE_ZONE_NAME: '이미 사용 중인 구역 이름입니다.',
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

// 선택한 지역 id별 지역 정보(경계 포함)와 지금 보이는 구역 목록
const regions = new Map();
let selectedDong = null;
let zones = [];
let selectedZoneId = null;
// 미리보기를 마친 다각형: { boundary, dongId }
let draft = null;

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
        disabledButton('이웃', 'secondary', '2단계에서 만듭니다'),
        disabledButton('수정', 'secondary', '3단계에서 만듭니다'),
        disabledButton('삭제', 'danger', '3단계에서 만듭니다'),
    );
    row.append(info, actions);
    return row;
}

function disabledButton(text, kind, title) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `btn ${kind} sm`;
    button.textContent = text;
    button.title = title;
    button.disabled = true;
    return button;
}

function selectZone(zoneId) {
    selectedZoneId = zoneId;
    zoneMap.select(zoneId);
    renderList();
}

// ---- 새 구역 그리기 ----

function startDraw() {
    cancelDraft();
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
        showError(error, (message) => (error.code === 'DUPLICATE_ZONE_NAME' ? setNameError(message) : showDraftError(message)));
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
function showError(error, show) {
    if (!(error instanceof ApiError) || error.status === 401) {
        return;
    }
    const fieldMessage = error.fieldErrors.find((item) => item.field === 'name')?.message;
    if (fieldMessage) {
        setNameError(fieldMessage);
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
