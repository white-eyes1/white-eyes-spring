// 캠 카드의 영상 칸(FNC-A-042, SCR-C-003). 가동 중(RUNNING)이고 화면에 보이는 카드만 최신 화면(스냅샷)을
// 정해진 간격(기본 2초)마다 갱신한다. 요청 순서·개수 제한은 scheduler.js가 정한다. 실패하면 마지막 화면을 두고 '끊김'을 보인다.
import { url } from '../common/api.js';
import { createSnapshotScheduler } from './scheduler.js';

const TICK_MS = 500;
const SNAPSHOT_TIMEOUT_MS = 5000;
const STATUS_TEXT = {
    STOPPED: '정지 · 가동하면 미리보기가 보입니다',
    PREPARING: '준비 중',
    ERROR: '오류 · 영상을 볼 수 없습니다',
};

/**
 * @param {{onOpen: (cameraId: number) => void, intervalSec?: number}} options
 *   onOpen: 영상 칸을 눌렀을 때(실시간 보기 창 열기), intervalSec: 카드 갱신 간격(초, CARD_SNAPSHOT_INTERVAL_SEC). 없거나 잘못되면 기본값
 */
export function createPreviewManager({ onOpen, intervalSec }) {
    // cameraId -> { el, img, badge, objectUrl }
    const entries = new Map();

    const scheduler = createSnapshotScheduler({
        load: loadSnapshot,
        onResult: showResult,
        ...(intervalSec > 0 ? { intervalMs: intervalSec * 1000 } : {}),
    });
    // 보이는 카드만 요청한다. 카드는 목록을 다시 그릴 때마다 새로 만들어지므로 보이는 상태도 그때 다시 알려 준다
    const observer = new IntersectionObserver((records) => {
        records.forEach((record) => {
            const id = Number(record.target.dataset.cameraId);
            scheduler.setVisible(id, record.isIntersecting);
        });
    });

    window.setInterval(() => scheduler.tick(), TICK_MS);
    // 탭이 숨겨지면 멈춘다
    document.addEventListener('visibilitychange', () => scheduler.setPaused(document.hidden));
    scheduler.setPaused(document.hidden);

    async function loadSnapshot(id) {
        const controller = new AbortController();
        const timer = window.setTimeout(() => controller.abort(), SNAPSHOT_TIMEOUT_MS);
        try {
            const response = await fetch(url(`/live/${id}/snapshot`), { signal: controller.signal, cache: 'no-store' });
            if (!response.ok) {
                throw new Error(`snapshot ${response.status}`);
            }
            return URL.createObjectURL(await response.blob());
        } finally {
            window.clearTimeout(timer);
        }
    }

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
            const id = camera.id;
            const previous = entries.get(id);
            if (previous) {
                observer.unobserve(previous.el);
            }
            el.dataset.cameraId = String(id);
            el.classList.add('snap-live');
            el.replaceChildren();

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

            const running = camera.status === 'RUNNING';
            placeholder.textContent = running ? '미리보기를 불러오는 중' : (STATUS_TEXT[camera.status] ?? '');
            const entry = { el, img, badge, placeholder, objectUrl: previous?.objectUrl ?? null };
            if (running && entry.objectUrl) {
                img.src = entry.objectUrl;
                img.hidden = false;
                placeholder.hidden = true;
            }
            if (!running && entry.objectUrl) {
                URL.revokeObjectURL(entry.objectUrl);
                entry.objectUrl = null;
            }
            entries.set(id, entry);
            scheduler.setRunning(id, running);
            // 새 영상 칸의 보이는 상태는 관찰자가 처음 알려 준다
            scheduler.setVisible(id, false);
            observer.observe(el);

            el.onclick = () => onOpen(id);
        },

        /** 목록에서 빠진 카드의 요청과 받아 둔 화면을 정리한다 */
        prune(activeIds) {
            [...entries.keys()]
                .filter((id) => !activeIds.has(id))
                .forEach((id) => {
                    const entry = entries.get(id);
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
