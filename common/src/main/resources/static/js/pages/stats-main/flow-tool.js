// 통계 메인 지도 도구 '이동 흐름'(SCR-C-003, FNC-C-010). 켜면 GET /api/v1/map/flows로 기간의 구역 간 이동을 화살표로 그린다.
// 동 단계에서만 쓸 수 있고, 기본 기간은 최근 1시간이다. 제목에 '추정'을 붙인다(알려진 한계 1·2·17).
// 켜 둔 채 새 1분 통계가 오면(최근 n 기간이 움직이므로) 다시 읽는다.
import { get } from '../../common/api.js';
import { createFlowLayer } from '../../map/flow-layer.js';

const FLOWS_API = '/api/v1/map/flows';
const HOUR_MS = 60 * 60 * 1000;
// 기간 선택지: 지금으로부터 몇 시간 전까지
const PERIOD_HOURS = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 };
const DONG_ONLY = '동을 고르면 쓸 수 있습니다';
const LOAD_ERROR = '이동 흐름을 불러오지 못했습니다.';

const byId = (id) => document.getElementById(id);

export function createFlowTool() {
    const toggle = byId('flow-toggle');
    const period = byId('flow-period');
    const status = byId('flow-status');
    let layer = null;
    let regionId = null;
    let enabled = false;
    let on = false;
    let zones = [];
    let latestRequestId = 0;

    toggle.addEventListener('click', () => {
        on = !on;
        render();
        if (on) {
            load();
        } else {
            layer?.clear();
        }
    });
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
        layer = createFlowLayer(map);
        regionId = id;
        enabled = isDong;
        if (!enabled) {
            on = false;
        }
        render();
    }

    /** 구역 현황(중심점)을 새로 읽었을 때 부른다. 켜져 있으면 다시 읽어 그린다 */
    function refresh(zoneItems) {
        zones = zoneItems;
        if (on) {
            load();
        }
    }

    function render() {
        toggle.disabled = !enabled;
        toggle.title = enabled ? '' : DONG_ONLY;
        toggle.classList.toggle('on', on);
        toggle.setAttribute('aria-pressed', String(on));
        period.hidden = !on;
        status.hidden = true;
    }

    async function load() {
        if (regionId === null || !layer) {
            return;
        }
        const requestId = ++latestRequestId;
        const to = new Date();
        const from = new Date(to.getTime() - PERIOD_HOURS[period.value] * HOUR_MS);
        try {
            const flows = await get(
                FLOWS_API,
                { regionId: String(regionId), from: toLocalText(from), to: toLocalText(to) },
                { toast: false },
            );
            if (requestId !== latestRequestId || !on) {
                return;
            }
            layer.show(flows, zones);
            status.textContent = flows.length === 0 ? '이 기간에 이동이 없습니다.' : '';
            status.hidden = flows.length > 0;
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
function toLocalText(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return (
        `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
        `T${pad(date.getHours())}:${pad(date.getMinutes())}`
    );
}
