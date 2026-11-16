// 서버가 보내는 SSE(text/event-stream)에 연결한다. 끊기면 EventSource가 서버가 알려 준 retry(5초)로 다시 연결한다.
import { url } from './api.js';

/**
 * path에 연결하고 이벤트 이름별 처리기를 단다. 돌려주는 객체의 close()로 연결을 닫는다.
 * 데이터는 JSON으로 읽어 처리기에 넘긴다. 읽을 수 없는 데이터는 건너뛴다.
 */
export function connectStream(path, handlers, { onOpen, onError } = {}) {
    // 앱 컨텍스트 경로를 붙여 SSE 연결을 연다. 쿠키(세션)는 같은 출처라 자동으로 간다
    const source = new EventSource(url(path));
    // 연결 성공 콜백(선택)
    if (onOpen) {
        source.addEventListener('open', onOpen);
    }
    // 오류(끊김) 콜백(선택). 재연결은 브라우저가 알아서 하므로 닫지 않는다
    if (onError) {
        source.addEventListener('error', onError);
    }
    // 이벤트 이름(서버의 event: 값)마다 처리기를 하나씩 단다
    Object.entries(handlers).forEach(([name, handler]) => {
        source.addEventListener(name, (message) => {
            // 데이터는 JSON 문자열이다
            try {
                handler(JSON.parse(message.data));
            } catch {
                // 형식이 어긋난 소식 하나가 연결과 다음 소식을 막지 않게 한다
            }
        });
    });
    // 화면을 떠나거나 구독을 끝낼 때 호출한다
    return { close: () => source.close() };
}
