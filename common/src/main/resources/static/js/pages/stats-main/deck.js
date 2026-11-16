// 통계 메인 아래 캠 패널(관리자 앱만, SCR-C-003, FNC-A-022·020·021).
// 지도 화면 안 구역의 캠 카드, 정렬, 접기(가로 한 줄)/펼치기(세로 여러 줄) 전환, 가동·정지를 맡는다.
// 영상 칸은 가동 중이고 화면에 보이는 카드만 최신 화면을 2초마다 갱신하고(FNC-A-042, live/preview.js), 누르면 실시간 보기 창을 연다.
// 최근 10분은 1분마다 혼잡 단계 색 막대(결정 0016, live/mini-charts.js). 단계는 서버가 판정한 recent10m[].level이다.
import { post } from '../../common/api.js';
import { formatNumber } from '../../common/format.js';
import { bindNowCount } from '../../common/now-count.js';
import { levelBars, niceMax } from '../../live/mini-charts.js';
import { createPreviewManager } from '../../live/preview.js';
import { levelChip } from './panel.js';

// 지도 칸 최소 높이(px). 펼치기 전환 기준·손잡이 하한
const MIN_MAP_HEIGHT = 64;
// 지도 높이가 이 값보다 작으면 펼친 모양(여러 줄)으로 본다
const EXPAND_BELOW_MAP_HEIGHT = 360;
// 카드 안 최근 10분 막대 높이
const RECENT_BAR_HEIGHT = 40;
// 주의 단계(GET /meta/levels의 level 2)의 시작 밀집도 × 면적 = 주의 기준 인원(막대 위 점선)
// 주의 단계의 시작 밀집도 x 구역 면적 = 주의 기준 인원
const WARN_LEVEL = 2;

// 카드 상태 칩: 정지 / 준비 중 n% / 가동 중 / 오류
// 캠 상태 코드 -> 칩 글자와 CSS 클래스
const STATUS_CHIPS = {
    STOPPED: { text: '정지', css: 'off' },
    PREPARING: { text: '준비 중', css: 'prep' },
    RUNNING: { text: '가동 중', css: 'run' },
    ERROR: { text: '오류', css: 'err' },
};

// document.getElementById 줄임
const byId = (id) => document.getElementById(id);

/**
 * @param {{levels: object[], onReload: () => Promise<void>, onResize: () => void, onOpenLive: (camera: object) => void}} options
 *   onReload: 가동·정지 뒤 캠 목록을 다시 읽는 함수, onResize: 패널 높이가 바뀌어 지도 칸이 달라졌을 때,
 *   onOpenLive: 영상 칸을 눌렀을 때(실시간 보기 창 열기)
 */
export function createDeck({ levels, onReload, onResize, onOpenLive }) {
    // 화면 요소: 전체 영역, 지도 본문, 카드 상자, 정렬 선택
    const dash = byId('dash');
    const body = byId('dash-body');
    const cardsBox = byId('deck-cards');
    const sortSelect = byId('deck-sort');
    // cameras: 서버에서 받은 캠 목록, visibleZoneIds: 지도에 보이는 구역 id(보이는 구역의 캠만 카드로 보인다)
    let cameras = [];
    let visibleZoneIds = new Set();
    // 영상 칸 갱신 관리자. 카드를 누르면 실시간 보기 창을 연다
    const previews = createPreviewManager({
        // 관리자 앱이 환경변수 CARD_SNAPSHOT_INTERVAL_SEC를 템플릿으로 내려 준다
        intervalSec: Number(dash.dataset.snapshotIntervalSec),
        onOpen: (cameraId) => {
            const camera = cameras.find((item) => item.id === cameraId);
            if (camera) {
                onOpenLive(camera);
            }
        },
    });

    // 정렬을 바꾸면 카드를 다시 그린다
    sortSelect.addEventListener('change', render);
    // 접기/펼치기 버튼
    byId('deck-toggle').addEventListener('click', () =>
        setExpanded(!dash.classList.contains('expanded')),
    );
    // 높이 조절 손잡이 이벤트를 연결한다
    initGrip();

    // 카드를 다시 그린다: 보이는 구역의 캠만 골라 정렬하고 카드를 만든다
    function render() {
        const shown = sortCameras(
            cameras.filter((camera) => visibleZoneIds.has(camera.zoneId)),
            sortSelect.value,
        );
        byId('deck-count').textContent = String(shown.length);
        // 카드마다 article(카드 요소), snapshot(영상 칸)을 받는다
        const built = shown.map((camera) => card(camera, levels, onReload));
        cardsBox.replaceChildren(...built.map((item) => item.article));
        // 영상 칸은 카드를 다시 그릴 때마다 새로 만들어지므로 받아 둔 마지막 화면과 요청 대상을 다시 이어 준다
        built.forEach((item) => previews.bind(item.snapshot, item.camera));
        previews.prune(new Set(shown.map((camera) => camera.id)));
    }

    // 접기: 지도와 같이 보기(카드 한 줄 가로 스크롤) / 펼치기: 지도를 줄이고 여러 줄 세로 스크롤
    function setExpanded(expanded) {
        // expanded 클래스로 CSS 모양을 바꾸고 버튼 글자·지도 높이를 맞춘다. 높이가 바뀌므로 지도 크기를 다시 계산하게 알린다
        dash.classList.toggle('expanded', expanded);
        byId('deck-toggle').textContent = expanded ? '지도와 같이 보기' : '전체 펼치기';
        body.style.height = expanded ? `${MIN_MAP_HEIGHT}px` : '';
        body.style.minHeight = '';
        onResize();
    }

    // 위쪽 손잡이를 끌어 높이 조절(두 번 클릭 = 전환). 지도 칸이 일정 높이보다 작아지면 펼친 모양이 된다.
    // 손잡이: 두 번 클릭하면 전환, 끌면 높이 조절
    function initGrip() {
        const grip = byId('deck-grip');
        grip.addEventListener('dblclick', () => setExpanded(!dash.classList.contains('expanded')));
        // 포인터를 손잡이에 붙잡아 두면 손잡이 밖으로 나가도 이동 이벤트를 계속 받는다
        grip.addEventListener('pointerdown', (event) => {
            grip.setPointerCapture(event.pointerId);
            // 지도 본문의 위쪽 y 좌표. 포인터 y에서 이 값을 빼면 지도 높이다
            const top = body.getBoundingClientRect().top;
            const move = (moveEvent) => {
                // 지도 높이를 최소~(창 높이-200) 사이로 제한한다
                const mapHeight = Math.min(
                    Math.max(moveEvent.clientY - top, MIN_MAP_HEIGHT),
                    window.innerHeight - 200,
                );
                // 일정 높이보다 작으면 펼친 모양
                const expanded = mapHeight < EXPAND_BELOW_MAP_HEIGHT;
                dash.classList.toggle('expanded', expanded);
                byId('deck-toggle').textContent = expanded ? '지도와 같이 보기' : '전체 펼치기';
                body.style.height = `${mapHeight}px`;
                // 접힌 모양에서는 지도 높이를 고정하지 않고 손잡이가 닿은 높이만 따라간다
                body.style.minHeight = expanded ? '0' : `${mapHeight}px`;
            };
            // 끌기가 끝나면 이벤트를 모두 떼고 지도 크기를 다시 계산하게 알린다
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
        /** 그 구역의 캠(구역당 1대). 없으면 undefined */
        cameraByZone(zoneId) {
            return cameras.find((camera) => camera.zoneId === zoneId);
        },
        /** 지도 화면 안 구역 id가 바뀌었을 때 */
        setVisibleZones(zoneIds) {
            // 펼친 동안 지도가 얇은 띠로 줄어 시야 계산이 무의미하므로, 펼치기 직전의 구역 집합을 그대로 쓴다.
            // 접으면 onResize에서 다시 계산한다.
            if (dash.classList.contains('expanded')) {
                return;
            }
            visibleZoneIds = zoneIds;
            render();
        },
    };
}

/** 혼잡도 높은 순(밀집도 내림차순, 통계 없는 캠은 뒤) 또는 이름순 */
// 정렬: 'name'이면 이름순, 아니면 밀집도 높은 순(통계가 없는 캠은 뒤로, 서로 없으면 이름순)
function sortCameras(list, mode) {
    // 원본 배열을 바꾸지 않으려고 복사해서 정렬한다
    const copy = [...list];
    if (mode === 'name') {
        // 한글 이름 순서(localeCompare 'ko')
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

// 캠 카드 하나를 만든다. 제목·위치, 영상 칸, 최근 10분, 상태·인원·이벤트, 가동·정지 버튼
function card(camera, levels, onReload) {
    const article = document.createElement('article');
    article.className = 'camcard';

    const head = document.createElement('header');
    const titleRow = document.createElement('div');
    titleRow.className = 'row-between';
    const name = document.createElement('strong');
    name.textContent = camera.name;
    // 혼잡 단계 칩은 최신 1분 통계의 단계(없으면 칩이 단계 없음 모양)
    titleRow.append(name, levelChip(camera.stat?.level, levels));
    const place = document.createElement('span');
    place.className = 'muted small';
    place.textContent = `${camera.regionPath} · ${formatNumber(Math.round(camera.areaM2))}㎡`;
    head.append(titleRow, place);

    // 영상 칸은 비워 두고 createPreviewManager가 나중에 채운다
    const snapshot = document.createElement('div');
    snapshot.className = 'snap';

    const meta = document.createElement('div');
    meta.className = 'meta';
    // 인원 숫자: 1분 통계가 있을 때만 값, 없으면 '-'
    const people = labeled('인원', camera.stat ? formatNumber(currentOf(camera.stat)) : '-');
    // 현재 인원은 5초마다(통계 SSE event: now) 카드를 다시 그리지 않고 숫자만 바꾼다. 1분 통계가 아직 없는 캠도 받는다(FNC-A-054)
    bindNowCount(people.querySelector('b'), camera.zoneId, people.querySelector('b').textContent);
    meta.append(
        statusPart(camera),
        people,
        labeled('이벤트', formatNumber(camera.todayEventCount)),
    );

    // ERROR 상태면 서버가 준 오류 문구를 보인다
    article.append(head, snapshot, recentBlock(camera, levels), meta);
    if (camera.status === 'ERROR' && camera.errorMessage) {
        const error = document.createElement('div');
        error.className = 'err-msg';
        error.textContent = camera.errorMessage;
        article.append(error);
    }
    article.append(actions(camera, onReload));
    return { article, snapshot, camera };
}

// 5초 현재 인원(currentCount)이 있으면 그것을, 없으면 1분 평균을 반올림해 쓴다
// 5초 현재 인원이 없으면 1분 평균을 반올림해 쓴다
function currentOf(stat) {
    return stat.currentCount ?? Math.round(stat.countAvg);
}

// 상태 칩. 준비 중이면 진행률(%)도 붙인다
function statusPart(camera) {
    const wrap = document.createElement('span');
    const chip = document.createElement('span');
    // 알 수 없는 상태는 정지로 본다
    const info = STATUS_CHIPS[camera.status] ?? STATUS_CHIPS.STOPPED;
    chip.className = 'chip ' + info.css;
    chip.textContent =
        camera.status === 'PREPARING' && camera.progress !== null
            ? `${info.text} ${camera.progress}%`
            : info.text;
    wrap.append('상태 ', chip);
    return wrap;
}

// '이름 값' 모양의 작은 칸
function labeled(label, value) {
    const span = document.createElement('span');
    const strong = document.createElement('b');
    strong.textContent = value;
    span.append(`${label} `, strong);
    return span;
}

// 최근 10분: 머리(현재 인원, 10분 전 대비 증감), 단계 색 막대(주의 기준 점선), 아래 줄(-10분 · 최대·최소 · 지금)
function recentBlock(camera, levels) {
    // 최근 10분 데이터(분당 평균 인원과 혼잡 단계)
    const recent = camera.recent10m;
    const box = document.createElement('div');
    box.className = 'recent';
    const head = document.createElement('div');
    head.className = 'recent-head';
    const title = document.createElement('span');
    title.className = 'muted';
    title.textContent = '최근 10분';
    const now = document.createElement('span');
    // 데이터가 있으면 가장 최근 값과 10분 전 대비 증감을 보인다
    if (recent.length > 0) {
        const latest = Math.round(recent[recent.length - 1].countAvg);
        const value = document.createElement('b');
        value.textContent = formatNumber(latest);
        now.append(value, '명');
        if (recent.length > 1) {
            const change = latest - Math.round(recent[0].countAvg);
            const delta = document.createElement('span');
            delta.className = 'recent-delta';
            delta.textContent =
                change === 0 ? ' 0' : ` ${change > 0 ? '▲' : '▼'}${formatNumber(Math.abs(change))}`;
            now.append(delta);
        }
    }
    head.append(title, now);

    const values = recent.map((point) => point.countAvg);
    // 주의 단계의 시작 밀집도를 구한다
    const warnDensity = levels.find((level) => level.level === WARN_LEVEL)?.minDensity;
    // 주의 기준 인원 = 밀집도 x 면적(기준이 없으면 점선 없음)
    const warn = warnDensity ? Number(warnDensity) * camera.areaM2 : null;
    // 단계 색 막대. 서버가 판정한 level을 그대로 쓴다
    const bars = levelBars(
        recent.map((point) => ({ value: point.countAvg, level: point.level })),
        {
            height: RECENT_BAR_HEIGHT,
            max: niceMax(values),
            warn,
            label: '최근 10분 인원, 혼잡 단계 색',
        },
    );

    const foot = document.createElement('div');
    foot.className = 'recent-foot muted';
    // 아래 줄: 10분 중 최대·최소
    const range =
        recent.length > 0
            ? `최대 ${formatNumber(Math.round(Math.max(...values)))} · 최소 ${formatNumber(Math.round(Math.min(...values)))}`
            : '데이터 없음';
    foot.append(span('-10분'), span(range), span('지금'));
    box.append(head, bars, foot);
    return box;
}

// 글자만 있는 span 하나
function span(text) {
    const element = document.createElement('span');
    element.textContent = text;
    return element;
}

// STOPPED → 가동 / PREPARING·RUNNING → 정지 / ERROR → 다시 시작·정지 둘 다(FNC-A-020·021). 캠 관리 표(SCR-A-006)와 같은 낱말
// 상태에 맞는 가동·다시 시작·정지 버튼을 만든다
function actions(camera, onReload) {
    const footer = document.createElement('footer');
    // 가동할 수 있는 상태: 정지, 오류
    const canStart = camera.status === 'STOPPED' || camera.status === 'ERROR';
    // 정지할 수 있는 상태: 정지가 아닌 모든 상태
    const canStop = camera.status !== 'STOPPED';
    if (canStart) {
        footer.append(
            actionButton(
                camera.status === 'ERROR' ? '다시 시작' : '가동',
                camera.id,
                'start',
                onReload,
            ),
        );
    }
    if (canStop) {
        footer.append(actionButton('정지', camera.id, 'stop', onReload));
    }
    return footer;
}

// 버튼 하나: 누르면 서버에 가동(start)·정지(stop)를 요청하고 캠 목록을 다시 읽는다
function actionButton(text, cameraId, action, onReload) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn secondary sm';
    button.textContent = text;
    button.addEventListener('click', async () => {
        // 요청 중 중복 클릭 방지
        button.disabled = true;
        try {
            await post(`/api/v1/manage/cameras/${cameraId}/${action}`);
        } catch {
            // 오류 문구는 api.js가 토스트로 보인다(502·422·409 문구는 서버 message)
        }
        // 성공이든 실패든 서버 상태가 바뀌었을 수 있어 다시 읽는다
        await onReload();
    });
    return button;
}
