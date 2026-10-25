// 통계 메인 지도 도구 '히트맵'(SCR-C-003, FNC-C-011). 켜면 조건(기간·항목·요일)으로 GET /api/v1/map/heatmap을 읽어
// 하위 단위 경계를 시간대(0~23시) 평균값으로 칠한다. 색은 혼잡 단계 색과 다른 파랑 계열이고 min~max로 정규화한다.
// 시간대 슬라이더를 움직이거나 재생(1초마다 다음 시간)하면 다시 읽지 않고 받은 값으로 색만 바꾼다. 끄면 혼잡 단계 색으로 돌아간다.
// 기간은 날짜로 고르고(양끝 포함), 기본은 어제까지 28일이다. 최대 1년.
import { get } from '../../common/api.js';
import { formatDecimal } from '../../common/format.js';

const HEATMAP_API = '/api/v1/map/heatmap';
const DEFAULT_DAYS = 28;
const PLAY_INTERVAL_MS = 1000;
const HEAT_COLOR = '#4f8cff';
// 칠 진하기 범위(min이 연하게, max가 진하게)
const MIN_OPACITY = 0.12;
const MAX_OPACITY = 0.8;
const ITEM_UNITS = { COUNT: ['평균 인원', '명', 0], DENSITY: ['평균 밀집도', '명/㎡', 2], INFLOW: ['시간당 유입', '명', 1], OUTFLOW: ['시간당 유출', '명', 1] };
const TOO_LONG = '기간은 최대 1년까지 고를 수 있습니다.';
const REVERSED = '끝 날짜는 시작 날짜보다 앞설 수 없습니다.';
const LOAD_ERROR = '히트맵을 불러오지 못했습니다.';

const byId = (id) => document.getElementById(id);

export function createHeatmapTool() {
    const toggle = byId('heatmap-toggle');
    const box = byId('heatmap-box');
    const fromInput = byId('heatmap-from');
    const toInput = byId('heatmap-to');
    const itemSelect = byId('heatmap-item');
    const dayTypeSelect = byId('heatmap-day-type');
    const slider = byId('heatmap-hour');
    const hourLabel = byId('heatmap-hour-label');
    const playButton = byId('heatmap-play');
    const range = byId('heatmap-range');
    const status = byId('heatmap-status');
    let layer = null;
    let regionId = null;
    let on = false;
    let data = null;
    let playTimer = null;
    let latestRequestId = 0;

    setDefaultPeriod();
    slider.value = String(new Date().getHours());
    toggle.addEventListener('click', () => {
        on = !on;
        render();
        if (on) {
            load();
        } else {
            stop();
            data = null;
            layer?.paintBoundaries(null);
        }
    });
    [fromInput, toInput, itemSelect, dayTypeSelect].forEach((input) => input.addEventListener('change', load));
    slider.addEventListener('input', paint);
    playButton.addEventListener('click', () => (playTimer ? stop() : play()));
    render();

    /** 지도를 새로 만들었을 때(지역이 바뀔 때) 부른다. 켜져 있으면 새 지역으로 다시 읽는다 */
    function setRegion(statusLayer, id) {
        layer = statusLayer;
        regionId = id;
        data = null;
        if (on) {
            load();
        }
    }

    function setDefaultPeriod() {
        const yesterday = addDays(new Date(), -1);
        fromInput.value = toDateText(addDays(yesterday, -(DEFAULT_DAYS - 1)));
        toInput.value = toDateText(yesterday);
    }

    function render() {
        toggle.classList.toggle('on', on);
        toggle.setAttribute('aria-pressed', String(on));
        box.hidden = !on;
    }

    async function load() {
        if (!on || regionId === null || !layer) {
            return;
        }
        const invalid = validate();
        if (invalid) {
            showStatus(invalid);
            return;
        }
        const requestId = ++latestRequestId;
        // 날짜는 양끝을 포함하므로 to는 끝 날짜 다음 날 0시(서버는 from 이상 to 미만)
        const params = {
            regionId: String(regionId),
            from: fromInput.value,
            to: toDateText(addDays(parseDate(toInput.value), 1)),
            item: itemSelect.value,
            dayType: dayTypeSelect.value,
        };
        try {
            const response = await get(HEATMAP_API, params, { toast: false });
            if (requestId !== latestRequestId || !on) {
                return;
            }
            data = response;
            showStatus(response.min === null ? '이 기간에 데이터가 없습니다.' : '');
            paint();
        } catch (error) {
            if (requestId === latestRequestId) {
                data = null;
                layer.paintBoundaries(new Map());
                showStatus(error?.message || LOAD_ERROR);
            }
        }
    }

    // 화면에서 먼저 검사한다. 틀리면 문구를 보이고 조회하지 않는다(screens.md '1년 초과 시 문구')
    function validate() {
        const from = parseDate(fromInput.value);
        const to = parseDate(toInput.value);
        if (!from || !to) {
            return '기간을 골라 주세요.';
        }
        if (to < from) {
            return REVERSED;
        }
        const limit = new Date(from.getFullYear() + 1, from.getMonth(), from.getDate());
        return addDays(to, 1) > limit ? TOO_LONG : null;
    }

    // 지금 슬라이더 시간대의 값으로 경계를 칠한다
    function paint() {
        const hour = Number(slider.value);
        hourLabel.textContent = `${hour}시`;
        if (!data) {
            return;
        }
        const [label, unit, decimals] = ITEM_UNITS[itemSelect.value];
        const min = data.min === null ? 0 : Number(data.min);
        const max = data.max === null ? 0 : Number(data.max);
        range.textContent = data.min === null
            ? ''
            : `${formatDecimal(min, decimals)} ~ ${formatDecimal(max, decimals)}${unit}`;
        const painted = new Map();
        data.units.forEach((unitId) => {
            const value = data.values[String(unitId)]?.[hour];
            if (value === null || value === undefined) {
                return; // 값이 없는 단위는 회색(status-map)
            }
            const number = Number(value);
            const ratio = max > min ? (number - min) / (max - min) : 1;
            painted.set(unitId, {
                color: HEAT_COLOR,
                fillOpacity: MIN_OPACITY + ratio * (MAX_OPACITY - MIN_OPACITY),
                label: `${hour}시 ${label} ${formatDecimal(number, decimals)}${unit}`,
            });
        });
        layer.paintBoundaries(painted);
    }

    function play() {
        playButton.textContent = '정지';
        playButton.setAttribute('aria-pressed', 'true');
        playTimer = window.setInterval(() => {
            slider.value = String((Number(slider.value) + 1) % 24);
            paint();
        }, PLAY_INTERVAL_MS);
    }

    function stop() {
        window.clearInterval(playTimer);
        playTimer = null;
        playButton.textContent = '재생';
        playButton.setAttribute('aria-pressed', 'false');
    }

    function showStatus(text) {
        status.textContent = text;
        status.hidden = text === '';
    }

    return { setRegion };
}

function addDays(date, days) {
    const copy = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    copy.setDate(copy.getDate() + days);
    return copy;
}

function parseDate(text) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text ?? '');
    return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
}

function toDateText(date) {
    const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
