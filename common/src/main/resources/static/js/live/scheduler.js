// 캠 카드 미리보기(FNC-A-042)의 요청 순서를 정한다. 화면(DOM)을 건드리지 않아 따로 시험할 수 있다.
// 카드가 16장 떠 있어도 실시간 보기 창(MJPEG)·통계 SSE가 막히지 않도록 다음을 지킨다.
//  - 화면에 보이는 카드, 가동 중(RUNNING)인 카드만 요청한다(화면 밖 카드는 요청하지 않는다)
//  - 브라우저는 한 서버에 동시 연결을 6개쯤만 열 수 있으므로, 한꺼번에 날아가는 요청은 maxInFlight개까지
//  - 같은 카드의 이전 요청이 끝나기 전에는 다시 요청하지 않고, 카드마다 intervalMs 간격을 둔다
//  - 탭이 숨겨지면 멈춘다(pause)

/**
 * @param {{
 *   load: (id: number) => Promise<unknown>,
 *   onResult: (id: number, ok: boolean, value?: unknown) => void,
 *   maxInFlight?: number,
 *   intervalMs?: number,
 *   now?: () => number
 * }} options
 *   load: 카드 하나의 최신 화면을 읽는 함수, onResult: 읽은 결과(성공이면 ok=true와 값, 실패면 ok=false)
 */
export function createSnapshotScheduler({ load, onResult, maxInFlight = 2, intervalMs = 2000, now = () => Date.now() }) {
    const cards = new Map();
    let inFlight = 0;
    let paused = false;

    const ensure = (id) => {
        if (!cards.has(id)) {
            cards.set(id, { visible: false, running: false, requestedAt: -Infinity, loading: false });
        }
        return cards.get(id);
    };

    function startLoad(id, card) {
        card.loading = true;
        card.requestedAt = now();
        inFlight += 1;
        Promise.resolve()
            .then(() => load(id))
            .then(
                (value) => onResult(id, true, value),
                () => onResult(id, false),
            )
            .finally(() => {
                card.loading = false;
                inFlight -= 1;
            });
    }

    return {
        /** 카드가 화면에 들어오거나 나갔을 때 */
        setVisible(id, visible) {
            ensure(id).visible = visible;
        },
        /** 캠이 가동 중인지(RUNNING만 미리보기를 읽는다) */
        setRunning(id, running) {
            ensure(id).running = running;
        },
        /** 카드가 사라졌을 때 */
        remove(id) {
            cards.delete(id);
        },
        /** 탭이 숨겨지면 멈추고, 다시 보이면 이어간다 */
        setPaused(value) {
            paused = value;
        },
        /**
         * 지금 읽을 차례인 카드를 읽기 시작한다. 짧은 주기(예: 0.5초)로 부른다.
         * @returns {number[]} 이번에 읽기 시작한 카드 번호
         */
        tick() {
            if (paused) {
                return [];
            }
            const current = now();
            const due = [...cards.entries()]
                .filter(([, card]) => card.visible && card.running && !card.loading && current - card.requestedAt >= intervalMs)
                // 오래 기다린 카드부터
                .sort((first, second) => first[1].requestedAt - second[1].requestedAt);
            const started = [];
            for (const [id, card] of due) {
                if (inFlight >= maxInFlight) {
                    break;
                }
                startLoad(id, card);
                started.push(id);
            }
            return started;
        },
        /** 시험·진단용: 지금 읽는 중인 요청 수 */
        inFlightCount() {
            return inFlight;
        },
    };
}
