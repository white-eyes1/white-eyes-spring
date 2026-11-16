// 탐지 박스 표시(FNC-A-046)에 쓰는 DOM 없는 계산. 좌표 파일 형식은 conventions.md '좌표 파일 형식'이다.
//   {"version": 1, "fps": 5, "frames": [{"t": 0.2, "b": [[추적 번호, x, y, 너비, 높이], ...]}, ...]}
//   t: 녹화 시작부터의 초. 좌표는 영상 크기 대비 0~1 비율(왼쪽 위 기준). 추적 번호가 없으면(None) null로 들어올 수 있다.

/**
 * 좌표 파일 JSON에서 프레임을 t 순서로 정리한다. 모양이 틀린 프레임·박스는 버린다. 쓸 수 있는 프레임이 없으면 null.
 * @param {unknown} json
 * @returns {{t: number, boxes: {id: number|null, x: number, y: number, w: number, h: number}[]}[] | null}
 */
export function parseFrames(json) {
    // 최상위 모양이 틀리면(객체가 아니거나 frames가 배열이 아님) 쓸 수 없는 파일로 본다.
    if (!json || !Array.isArray(json.frames)) {
        return null;
    }
    // t가 숫자이고 b가 배열인 프레임만 남기고, 각 프레임의 박스를 toBox로 변환해 잘못된 박스를 버린다. 이진 탐색(findFrame)이 가능하도록 t 오름차순으로 정렬한다.
    const frames = json.frames
        .filter((frame) => frame && Number.isFinite(frame.t) && Array.isArray(frame.b))
        .map((frame) => ({ t: frame.t, boxes: frame.b.map(toBox).filter((box) => box !== null) }))
        .sort((a, b) => a.t - b.t);
    // 유효한 프레임이 하나도 없으면 '좌표 없음'과 같게 null을 돌려준다.
    return frames.length > 0 ? frames : null;
}

// [추적 번호, x, y, 너비, 높이] → 박스. 좌표가 숫자가 아니면 null. 추적 번호는 없어도(null) 박스는 그린다
function toBox(entry) {
    // 항목이 배열이 아니거나 5개 값(번호, x, y, 너비, 높이)보다 짧으면 버린다.
    if (!Array.isArray(entry) || entry.length < 5) {
        return null;
    }
    const [id, x, y, w, h] = entry;
    // 좌표 네 개는 모두 숫자여야 한다. 추적 번호는 그리기에 필수가 아니라서 검사하지 않고 아래에서 null로 처리한다.
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
    // 이진 탐색: found는 지금까지 찾은 '조건을 만족하는 마지막 인덱스'.
    let low = 0;
    let high = frames.length - 1;
    let found = -1;
    while (low <= high) {
        const mid = (low + high) >> 1;
        // 이 프레임이 위치보다 이르거나 같으면 후보로 기록하고 더 뒤쪽을 계속 찾는다. 아니면 앞쪽으로 좁힌다.
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
    // 영상이나 칸의 크기를 아직 알 수 없으면(0 또는 숫자 아님) 계산하지 않는다.
    if (!(videoWidth > 0 && videoHeight > 0 && boxWidth > 0 && boxHeight > 0)) {
        return null;
    }
    // 칸에 영상이 모두 들어가도록 가로·세로 비율 중 작은 쪽으로 축소·확대한다(contain).
    const scale = Math.min(boxWidth / videoWidth, boxHeight / videoHeight);
    const width = videoWidth * scale;
    const height = videoHeight * scale;
    // 남는 공간을 양쪽에 똑같이 나눠 영상을 가운데에 놓는다.
    return { x: (boxWidth - width) / 2, y: (boxHeight - height) / 2, width, height };
}

/** 비율 좌표 박스를 화면 좌표(칸 안 픽셀)로 바꾼다. area는 containRect 결과. */
export function toPixels(boxes, area) {
    // 0~1 비율 좌표에 영상 영역 크기를 곱하고 영역의 시작 위치를 더해 칸 안의 픽셀 좌표로 만든다.
    return boxes.map((box) => ({
        x: area.x + box.x * area.width,
        y: area.y + box.y * area.height,
        w: box.w * area.width,
        h: box.h * area.height,
    }));
}
