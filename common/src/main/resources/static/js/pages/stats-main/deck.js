// 통계 메인 아래 캠 패널(관리자 앱만, SCR-C-003, FNC-A-022·020·021).
// 지도 화면 안 구역의 캠 카드, 정렬, 접기(가로 한 줄)/펼치기(세로 여러 줄) 전환, 분석 시작·중단을 맡는다.
// 카드 영상 스냅샷은 2단계(FNC-S-011)라 빈 칸만 둔다. 새 통계는 60초마다 다시 읽는다(SSE는 2단계).
import { post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { levelChip } from './panel.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const MIN_MAP_HEIGHT = 64;
const EXPAND_BELOW_MAP_HEIGHT = 360;
const SPARK_WIDTH = 180;
const SPARK_HEIGHT = 24;

// 카드 상태 칩: 정지 / 준비 중 n% / 가동 중 / 오류
const STATUS_CHIPS = {
    STOPPED: { text: '정지', css: 'off' },
    PREPARING: { text: '준비 중', css: 'prep' },
    RUNNING: { text: '가동 중', css: 'run' },
    ERROR: { text: '오류', css: 'err' },
};

const byId = (id) => document.getElementById(id);

/**
 * @param {{levels: object[], onReload: () => Promise<void>, onResize: () => void}} options
 *   onReload: 시작·중단 뒤 캠 목록을 다시 읽는 함수, onResize: 패널 높이가 바뀌어 지도 칸이 달라졌을 때
 */
export function createDeck({ levels, onReload, onResize }) {
    const dash = byId('dash');
    const body = byId('dash-body');
    const cardsBox = byId('deck-cards');
    const sortSelect = byId('deck-sort');
    let cameras = [];
    let visibleZoneIds = new Set();

    sortSelect.addEventListener('change', render);
    byId('deck-toggle').addEventListener('click', () => setExpanded(!dash.classList.contains('expanded')));
    initGrip();

    function render() {
        const shown = sortCameras(
            cameras.filter((camera) => visibleZoneIds.has(camera.zoneId)),
            sortSelect.value,
        );
        byId('deck-count').textContent = String(shown.length);
        cardsBox.replaceChildren(...shown.map((camera) => card(camera, levels, onReload)));
    }

    // 접기: 지도와 같이 보기(카드 한 줄 가로 스크롤) / 펼치기: 지도를 줄이고 여러 줄 세로 스크롤
    function setExpanded(expanded) {
        dash.classList.toggle('expanded', expanded);
        byId('deck-toggle').textContent = expanded ? '지도와 같이 보기' : '전체 펼치기';
        body.style.height = expanded ? `${MIN_MAP_HEIGHT}px` : '';
        body.style.minHeight = '';
        onResize();
    }

    // 위쪽 손잡이를 끌어 높이 조절(두 번 클릭 = 전환). 지도 칸이 일정 높이보다 작아지면 펼친 모양이 된다.
    function initGrip() {
        const grip = byId('deck-grip');
        grip.addEventListener('dblclick', () => setExpanded(!dash.classList.contains('expanded')));
        grip.addEventListener('pointerdown', (event) => {
            grip.setPointerCapture(event.pointerId);
            const top = body.getBoundingClientRect().top;
            const move = (moveEvent) => {
                const mapHeight = Math.min(
                    Math.max(moveEvent.clientY - top, MIN_MAP_HEIGHT),
                    window.innerHeight - 200,
                );
                const expanded = mapHeight < EXPAND_BELOW_MAP_HEIGHT;
                dash.classList.toggle('expanded', expanded);
                byId('deck-toggle').textContent = expanded ? '지도와 같이 보기' : '전체 펼치기';
                body.style.height = `${mapHeight}px`;
                // 접힌 모양에서는 지도 높이를 고정하지 않고 손잡이가 닿은 높이만 따라간다
                body.style.minHeight = expanded ? '0' : `${mapHeight}px`;
            };
            const stop = () => {
                grip.removeEventListener('pointermove', move);
                grip.removeEventListener('pointerup', stop);
                grip.removeEventListener('pointercancel', stop);
                onResize();
            };
            grip.addEventListener('pointermove', move);
            grip.addEventListener('pointerup', stop);
            grip.addEventListener('pointercancel', stop);
        });
    }

    return {
        /** GET /manage/cameras 결과를 받는다 */
        setCameras(list) {
            cameras = list;
            render();
        },
        /** 지도 화면 안 구역 id가 바뀌었을 때 */
        setVisibleZones(zoneIds) {
            visibleZoneIds = zoneIds;
            render();
        },
    };
}

/** 혼잡도 높은 순(밀집도 내림차순, 통계 없는 캠은 뒤) 또는 이름순 */
function sortCameras(list, mode) {
    const copy = [...list];
    if (mode === 'name') {
        return copy.sort((a, b) => a.name.localeCompare(b.name, 'ko'));
    }
    return copy.sort((a, b) => {
        if (!a.stat && !b.stat) {
            return a.name.localeCompare(b.name, 'ko');
        }
        if (!a.stat || !b.stat) {
            return a.stat ? -1 : 1;
        }
        return b.stat.density - a.stat.density;
    });
}

function card(camera, levels, onReload) {
    const article = document.createElement('article');
    article.className = 'camcard';

    const head = document.createElement('header');
    const titleRow = document.createElement('div');
    titleRow.className = 'row-between';
    const name = document.createElement('strong');
    name.textContent = camera.name;
    titleRow.append(name, levelChip(camera.stat?.level, levels));
    const place = document.createElement('span');
    place.className = 'muted small';
    place.textContent = `${camera.regionPath} · ${formatNumber(Math.round(camera.areaM2))}㎡`;
    head.append(titleRow, place);

    const snapshot = document.createElement('div');
    snapshot.className = 'snap';
    snapshot.setAttribute('aria-hidden', 'true');

    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.append(
        statusPart(camera),
        labeled('인원', camera.stat ? formatNumber(Math.round(camera.stat.countAvg)) : '-'),
        labeled('이벤트', formatNumber(camera.todayEventCount)),
    );

    article.append(head, snapshot, sparkline(camera.recent10m), meta);
    if (camera.status === 'ERROR' && camera.errorMessage) {
        const error = document.createElement('div');
        error.className = 'err-msg';
        error.textContent = camera.errorMessage;
        article.append(error);
    }
    article.append(actions(camera, onReload));
    return article;
}

function statusPart(camera) {
    const wrap = document.createElement('span');
    const chip = document.createElement('span');
    const info = STATUS_CHIPS[camera.status] ?? STATUS_CHIPS.STOPPED;
    chip.className = 'chip ' + info.css;
    chip.textContent =
        camera.status === 'PREPARING' && camera.progress !== null ? `${info.text} ${camera.progress}%` : info.text;
    wrap.append('상태 ', chip);
    return wrap;
}

function labeled(label, value) {
    const span = document.createElement('span');
    const strong = document.createElement('b');
    strong.textContent = value;
    span.append(`${label} `, strong);
    return span;
}

// 최근 10분 인원 선(1분 통계). 점이 2개 미만이면 선을 그리지 않는다
function sparkline(recent) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${SPARK_WIDTH} ${SPARK_HEIGHT}`);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('class', 'spark');
    svg.setAttribute('aria-label', '최근 10분 인원');
    if (recent.length < 2) {
        return svg;
    }
    const values = recent.map((point) => Number(point.countAvg));
    const min = Math.min(...values);
    const range = Math.max(...values) - min || 1;
    const points = values
        .map((value, index) => {
            const x = (index / (values.length - 1)) * SPARK_WIDTH;
            const y = SPARK_HEIGHT - 3 - ((value - min) / range) * (SPARK_HEIGHT - 6);
            return `${x.toFixed(1)},${y.toFixed(1)}`;
        })
        .join(' ');
    const line = document.createElementNS(SVG_NS, 'polyline');
    line.setAttribute('points', points);
    line.setAttribute('fill', 'none');
    line.setAttribute('stroke', '#76a7ff');
    line.setAttribute('stroke-width', '1.6');
    line.setAttribute('vector-effect', 'non-scaling-stroke');
    svg.append(line);
    return svg;
}

// STOPPED → 분석 시작 / PREPARING·RUNNING → 중단 / ERROR → 다시 시작·중단 둘 다(FNC-A-020·021)
function actions(camera, onReload) {
    const footer = document.createElement('footer');
    const canStart = camera.status === 'STOPPED' || camera.status === 'ERROR';
    const canStop = camera.status !== 'STOPPED';
    if (canStart) {
        footer.append(actionButton(camera.status === 'ERROR' ? '다시 시작' : '분석 시작', camera.id, 'start', onReload));
    }
    if (canStop) {
        footer.append(actionButton('중단', camera.id, 'stop', onReload));
    }
    return footer;
}

function actionButton(text, cameraId, action, onReload) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = text;
    button.addEventListener('click', async () => {
        button.disabled = true;
        try {
            await post(`/api/v1/manage/cameras/${cameraId}/${action}`);
        } catch {
            // 오류 문구는 api.js가 토스트로 보인다(502·422·409 문구는 서버 message)
        }
        await onReload();
    });
    return button;
}
