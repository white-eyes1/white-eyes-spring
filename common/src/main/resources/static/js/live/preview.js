// 캠 카드의 영상 칸(FNC-A-042, SCR-C-003). 가동 중(RUNNING)이고 화면에 보이는 카드만 최신 화면(스냅샷)을
// 정해진 간격(기본 2초)마다 갱신한다. 요청 순서·개수 제한은 scheduler.js가 정한다. 실패하면 마지막 화면을 두고 '끊김'을 보인다.
import { download } from '../common/api.js';
import { createSnapshotScheduler } from './scheduler.js';

// 스케줄러를 깨우는 주기(0.5초). 실제 카드별 요청 간격은 scheduler의 intervalMs가 정한다
const TICK_MS = 500;
// 스냅샷 한 장을 5초 안에 못 받으면 끊긴 것으로 본다
const SNAPSHOT_TIMEOUT_MS = 5000;
// 가동 중이 아닌 캠의 영상 칸에 보일 문구
const STATUS_TEXT = {
    STOPPED: '정지 · 가동하면 미리보기가 보입니다',
    PREPARING: '준비 중',
    ERROR: '오류 · 영상을 볼 수 없습니다',
};

/**
 * @param {{onOpen: (cameraId: number) => void, intervalSec?: number}} options
 *   onOpen: 영상 칸을 눌렀을 때(실시간 보기 창 열기), intervalSec: 카드 갱신 간격(초, CARD_SNAPSHOT_INTERVAL_SEC). 없거나 잘못되면 기본값
 */
// 카드마다 영상 칸을 관리하는 객체를 만든다. 화면 쪽은 bind/prune만 부른다
export function createPreviewManager({ onOpen, intervalSec }) {
    // cameraId -> { el, img, badge, objectUrl }
    // cameraId -> 영상 칸 정보(el 칸, img 이미지, badge '끊김' 표시, placeholder 안내 글자, objectUrl 마지막 받은 화면)
    const entries = new Map();

    // 요청 순서·동시 요청 수 제한은 scheduler가 맡고, 이 모듈은 읽는 방법(load)과 결과 표시(onResult)만 준다
    const scheduler = createSnapshotScheduler({
        load: loadSnapshot,
        onResult: showResult,
        // 간격이 유효한 양수일 때만 기본값(2초)을 덮어쓴다
        ...(intervalSec > 0 ? { intervalMs: intervalSec * 1000 } : {}),
    });
    // 보이는 카드만 요청한다. 카드는 목록을 다시 그릴 때마다 새로 만들어지므로 보이는 상태도 그때 다시 알려 준다
    // IntersectionObserver: 카드가 화면에 보이는지 알려 준다. 보이는 카드만 요청한다
    const observer = new IntersectionObserver((records) => {
        records.forEach((record) => {
            const id = Number(record.target.dataset.cameraId);
            scheduler.setVisible(id, record.isIntersecting);
        });
    });

    // 0.5초마다 스케줄러에게 '읽을 카드가 있나' 확인시킨다
    window.setInterval(() => scheduler.tick(), TICK_MS);
    // 탭이 숨겨지면 멈춘다
    // 탭이 숨겨지면 요청을 멈춘다
    document.addEventListener('visibilitychange', () => scheduler.setPaused(document.hidden));
    scheduler.setPaused(document.hidden);

    // 스냅샷 한 장을 읽어 blob URL로 돌려준다
    async function loadSnapshot(id) {
        // 시간 제한을 걸기 위한 취소 장치
        const controller = new AbortController();
        const timer = window.setTimeout(() => controller.abort(), SNAPSHOT_TIMEOUT_MS);
        try {
            // toast:false: 실패해도 토스트는 띄우지 않고 카드에 '끊김'만 표시한다. cache no-store: 항상 새 화면을 받는다
            const { blob } = await download(`/live/${id}/snapshot`, undefined, {
                signal: controller.signal,
                cache: 'no-store',
                toast: false,
            });
            // blob을 <img>가 바로 쓸 수 있는 임시 주소로 만든다
            return URL.createObjectURL(blob);
        } finally {
            // 성공이든 실패든 시간 제한 타이머를 정리한다
            window.clearTimeout(timer);
        }
    }

    // 스케줄러가 읽기 결과를 알려 준다
    function showResult(id, ok, objectUrl) {
        const entry = entries.get(id);
        if (!entry) {
            // 읽는 사이 카드가 사라졌다
            if (ok) {
                URL.revokeObjectURL(objectUrl);
            }
            return;
        }
        if (!ok) {
            // 마지막 화면은 그대로 두고 끊김만 표시한다
            entry.badge.hidden = false;
            return;
        }
        // 새 화면을 받았으면 이전 임시 주소를 해제해 메모리가 쌓이지 않게 한다
        if (entry.objectUrl) {
            URL.revokeObjectURL(entry.objectUrl);
        }
        entry.objectUrl = objectUrl;
        entry.img.src = objectUrl;
        entry.img.hidden = false;
        entry.badge.hidden = true;
        entry.placeholder.hidden = true;
    }

    return {
        /**
         * 카드의 영상 칸을 채운다. 목록을 다시 그릴 때마다 새 칸으로 부르며, 이미 받아 둔 마지막 화면은 바로 보여 준다.
         * @param {HTMLElement} el 영상 칸
         * @param {{id: number, status: string}} camera
         */
        bind(el, camera) {
            // 같은 캠 카드가 목록 재그리기로 새 칸이 되었을 수 있으므로 이전 칸 정보를 찾아 둔다
            const id = camera.id;
            const previous = entries.get(id);
            // 이전 칸은 더 이상 화면에 없으니 관찰을 멈춘다
            if (previous) {
                observer.unobserve(previous.el);
            }
            // 관찰자가 칸에서 카메라 번호를 알 수 있게 data 속성에 단다
            el.dataset.cameraId = String(id);
            el.classList.add('snap-live');
            // 칸 내용을 비우고 아래에서 다시 채운다
            el.replaceChildren();

            // 영상 이미지(받기 전에는 숨김), 안내 글자, '끊김' 배지
            const img = document.createElement('img');
            img.alt = '';
            img.hidden = true;
            const placeholder = document.createElement('span');
            placeholder.className = 'snap-text';
            const badge = document.createElement('span');
            badge.className = 'snap-lost';
            badge.textContent = '끊김';
            badge.hidden = true;
            el.append(img, placeholder, badge);

            // 가동 중이면 '불러오는 중', 아니면 상태별 문구
            const running = camera.status === 'RUNNING';
            placeholder.textContent = running
                ? '미리보기를 불러오는 중'
                : (STATUS_TEXT[camera.status] ?? '');
            // 이미 받아 둔 마지막 화면은 객체를 다시 만들 때 이어받는다
            const entry = { el, img, badge, placeholder, objectUrl: previous?.objectUrl ?? null };
            // 가동 중이고 이전 화면이 있으면 바로 보여 준다(깜빡임 방지)
            if (running && entry.objectUrl) {
                img.src = entry.objectUrl;
                img.hidden = false;
                placeholder.hidden = true;
            }
            // 가동이 멈춘 캠은 이전 화면을 버린다
            if (!running && entry.objectUrl) {
                URL.revokeObjectURL(entry.objectUrl);
                entry.objectUrl = null;
            }
            entries.set(id, entry);
            // 스케줄러에 이 카드의 가동 여부를 알린다
            scheduler.setRunning(id, running);
            // 새 영상 칸의 보이는 상태는 관찰자가 처음 알려 준다
            scheduler.setVisible(id, false);
            observer.observe(el);

            // 영상 칸을 누르면 실시간 보기 창을 연다
            el.onclick = () => onOpen(id);
        },

        /** 목록에서 빠진 카드의 요청과 받아 둔 화면을 정리한다 */
        prune(activeIds) {
            // 목록에 남아 있지 않은 카드를 정리한다
            [...entries.keys()]
                .filter((id) => !activeIds.has(id))
                .forEach((id) => {
                    const entry = entries.get(id);
                    // 관찰 중지, 임시 주소 해제, 스케줄러에서 제거
                    observer.unobserve(entry.el);
                    if (entry.objectUrl) {
                        URL.revokeObjectURL(entry.objectUrl);
                    }
                    entries.delete(id);
                    scheduler.remove(id);
                });
        },
    };
}
