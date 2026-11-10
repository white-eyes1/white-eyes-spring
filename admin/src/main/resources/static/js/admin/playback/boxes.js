// 탐지 박스 표시(FNC-A-046)에 쓰는 DOM 없는 계산. 좌표 파일 형식은 conventions.md '좌표 파일 형식'이다.
//   {"version": 1, "fps": 5, "frames": [{"t": 0.2, "b": [[추적 번호, x, y, 너비, 높이], ...]}, ...]}
//   t: 녹화 시작부터의 초. 좌표는 영상 크기 대비 0~1 비율(왼쪽 위 기준). 추적 번호가 없으면(None) null로 들어올 수 있다.

/**
 * 좌표 파일 JSON에서 프레임을 t 순서로 정리한다. 모양이 틀린 프레임·박스는 버린다. 쓸 수 있는 프레임이 없으면 null.
 * @param {unknown} json
 * @returns {{t: number, boxes: {id: number|null, x: number, y: number, w: number, h: number}[]}[] | null}
 */
export function parseFrames(json) {
    if (!json || !Array.isArray(json.frames)) {
        return null;
    }
    const frames = json.frames
        .filter((frame) => frame && Number.isFinite(frame.t) && Array.isArray(frame.b))
        .map((frame) => ({ t: frame.t, boxes: frame.b.map(toBox).filter((box) => box !== null) }))
        .sort((a, b) => a.t - b.t);
    return frames.length > 0 ? frames : null;
}

// [추적 번호, x, y, 너비, 높이] → 박스. 좌표가 숫자가 아니면 null. 추적 번호는 없어도(null) 박스는 그린다
function toBox(entry) {
    if (!Array.isArray(entry) || entry.length < 5) {
        return null;
    }
    const [id, x, y, w, h] = entry;
    if (![x, y, w, h].every(Number.isFinite)) {
        return null;
    }
    return { id: Number.isFinite(id) ? id : null, x, y, w, h };
}

/**
 * 재생 위치(초)에서 t ≤ position 인 마지막 프레임을 이진 탐색으로 찾는다. 첫 프레임보다 앞이면 null.
 * 다음 프레임이 올 때까지 직전 프레임의 박스를 유지한다(repfocus-004).
 */
export function findFrame(frames, position) {
    let low = 0;
    let high = frames.length - 1;
    let found = -1;
    while (low <= high) {
        const mid = (low + high) >> 1;
        if (frames[mid].t <= position) {
            found = mid;
            low = mid + 1;
        } else {
            high = mid - 1;
        }
    }
    return found === -1 ? null : frames[found];
}

/**
 * object-fit: contain 으로 칸(boxWidth×boxHeight) 안에 그려진 영상(videoWidth×videoHeight)의 실제 위치와 크기.
 * 영상 비율과 칸 비율이 다르면 위아래나 좌우에 빈 띠가 생기므로, 박스는 이 영역 기준으로 그려야 맞는다.
 * 영상 크기를 아직 모르면 null.
 */
export function containRect(boxWidth, boxHeight, videoWidth, videoHeight) {
    if (!(videoWidth > 0 && videoHeight > 0 && boxWidth > 0 && boxHeight > 0)) {
        return null;
    }
    const scale = Math.min(boxWidth / videoWidth, boxHeight / videoHeight);
    const width = videoWidth * scale;
    const height = videoHeight * scale;
    return { x: (boxWidth - width) / 2, y: (boxHeight - height) / 2, width, height };
}

/** 비율 좌표 박스를 화면 좌표(칸 안 픽셀)로 바꾼다. area는 containRect 결과. */
export function toPixels(boxes, area) {
    return boxes.map((box) => ({
        x: area.x + box.x * area.width,
        y: area.y + box.y * area.height,
        w: box.w * area.width,
        h: box.h * area.height,
    }));
}
