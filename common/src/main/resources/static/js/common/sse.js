// 서버가 보내는 SSE(text/event-stream)에 연결한다. 끊기면 EventSource가 서버가 알려 준 retry(5초)로 다시 연결한다.
import { url } from './api.js';

/**
 * path에 연결하고 이벤트 이름별 처리기를 단다. 돌려주는 객체의 close()로 연결을 닫는다.
 * 데이터는 JSON으로 읽어 처리기에 넘긴다. 읽을 수 없는 데이터는 건너뛴다.
 */
export function connectStream(path, handlers, { onOpen, onError } = {}) {
    const source = new EventSource(url(path));
    if (onOpen) {
        source.addEventListener('open', onOpen);
    }
    if (onError) {
        source.addEventListener('error', onError);
    }
    Object.entries(handlers).forEach(([name, handler]) => {
        source.addEventListener(name, (message) => {
            try {
                handler(JSON.parse(message.data));
            } catch {
                // 형식이 어긋난 소식 하나가 연결과 다음 소식을 막지 않게 한다
            }
        });
    });
    return { close: () => source.close() };
}
