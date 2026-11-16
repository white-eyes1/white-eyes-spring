// 통계 메인 지도 도구 '이동 흐름'(SCR-C-003, FNC-C-010). 켜면 GET /api/v1/map/flows로 기간의 구역 간 이동을 화살표로 그린다.
// 동 단계에서만 쓸 수 있고, 기본 기간은 최근 1시간이다. 제목에 '추정'을 붙인다(알려진 한계 1·2·17).
// 켜 둔 채 새 1분 통계가 오면(최근 n 기간이 움직이므로) 다시 읽는다.
import { get } from '../../common/api.js';
import { createFlowLayer } from '../../map/flow-layer.js';

// 이동 흐름 API
const FLOWS_API = '/api/v1/map/flows';
// 1시간(밀리초)
const HOUR_MS = 60 * 60 * 1000;
// 기간 선택지: 지금으로부터 몇 시간 전까지
const PERIOD_HOURS = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
// 동을 고르지 않았을 때 버튼에 붙는 안내
const DONG_ONLY = '동을 고르면 쓸 수 있습니다';
const LOAD_ERROR = '이동 흐름을 불러오지 못했습니다.';

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

// 지도 도구 하나를 만든다. 화면은 setRegion·refresh만 부른다
export function createFlowTool() {
    // 켜기 버튼, 기간 선택, 상태 문구
    const toggle = byId('flow-toggle');
    const period = byId('flow-period');
    const status = byId('flow-status');
    // layer: 화살표 층, regionId: 현재 지역, enabled: 쓸 수 있는지(동일 때만), on: 켜짐, zones: 중심점을 아는 구역 목록
    let layer = null;
    let regionId = null;
    let enabled = false;
    let on = false;
    let zones = [];
    let latestRequestId = 0;

    // 켜고 끄기: 켜면 읽어서 그리고, 끄면 화살표를 지운다
    toggle.addEventListener('click', () => {
        on = !on;
        render();
        if (on) {
            load();
        } else {
            layer?.clear();
        }
    });
    // 기간을 바꾸면 켜져 있을 때만 다시 읽는다
    period.addEventListener('change', () => {
        if (on) {
            load();
        }
    });
    render();

    /**
     * 지도를 새로 만들었을 때(지역이 바뀔 때) 부른다. 동이 아니면 끈다.
     * @param map 새 지도
     * @param {number} id 통계 대상 지역
     * @param {boolean} isDong 대상이 읍·면·동인지
     */
    function setRegion(map, id, isDong) {
        // 지도가 새로 만들어지면 화살표 층도 새로 만든다
        layer = createFlowLayer(map);
        regionId = id;
        // 동 단계일 때만 쓸 수 있다
        enabled = isDong;
        // 동이 아니면 강제로 끈다
        if (!enabled) {
            on = false;
        }
        render();
    }

    /** 구역 현황(중심점)을 새로 읽었을 때 부른다. 켜져 있으면 다시 읽어 그린다 */
    function refresh(zoneItems) {
        // 구역 목록을 기억해 두고, 켜져 있으면 다시 읽어 화살표를 새로 그린다
        zones = zoneItems;
        if (on) {
            load();
        }
    }

    function render() {
        // 동이 아니면 버튼을 막고 이유를 보인다
        toggle.disabled = !enabled;
        toggle.title = enabled ? '' : DONG_ONLY;
        toggle.classList.toggle('on', on);
        toggle.setAttribute('aria-pressed', String(on));
        // 기간 선택은 켜졌을 때만 보인다
        period.hidden = !on;
        status.hidden = true;
    }

    async function load() {
        // 층이나 지역이 없으면 읽지 않는다
        if (regionId === null || !layer) {
            return;
        }
        // 늦게 도착한 응답을 버리는 번호
        const requestId = ++latestRequestId;
        const to = new Date();
        // 선택한 기간만큼 지금부터 뒤로
        const from = new Date(to.getTime() - PERIOD_HOURS[period.value] * HOUR_MS);
        try {
            const flows = await get(
                FLOWS_API,
                { regionId: String(regionId), from: toLocalText(from), to: toLocalText(to) },
                { toast: false },
            );
            // 그 사이 지역이 바뀌었거나 껐으면 버린다
            if (requestId !== latestRequestId || !on) {
                return;
            }
            // 지도에 화살표를 그린다
            layer.show(flows, zones);
            // 이동이 없으면 안내 문구
            status.textContent = flows.length === 0 ? '이 기간에 이동이 없습니다.' : '';
            status.hidden = flows.length > 0;
        // 실패하면 화살표를 지우고 오류 문구를 보인다
        } catch {
            if (requestId === latestRequestId) {
                layer.clear();
                status.textContent = LOAD_ERROR;
                status.hidden = false;
            }
        }
    }

    return { setRegion, refresh };
}

// 서버가 KST로 읽는 'yyyy-MM-ddTHH:mm'(브라우저 시각, 통계 그래프 화면과 같은 방식)
// 서버가 받는 시각 형식('yyyy-MM-ddTHH:mm')으로 만든다
function toLocalText(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}
