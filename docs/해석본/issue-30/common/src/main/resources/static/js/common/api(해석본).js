/*
 * [해석본] 원본: common/src/main/resources/static/js/common/api.js (feature/30-signup 브랜치)
 * 관련 이슈: #30(SCR-U-001)에서 처음 만듦. 규칙: code-conventions.md 5-3, conventions.md 'CSRF'
 *
 * [무엇] 화면 JS가 서버 API를 부를 때 거치는 공용 모듈. 이 파일을 쓰는 첫 화면이 회원가입이라 #30에서 만들었다.
 * [다른 팀원] 통계·내 정보·관리자 화면도 이 파일을 import해서 쓴다(code-conventions 5-3: API 호출은 common/api.js 한 곳).
 *   지금은 get·post만 있다. 다른 메서드가 필요하면 request를 부르는 함수를 같은 모양으로 더하면 된다.
 * [하는 일] CSRF 헤더 추가, JSON 변환, 오류 응답을 ApiError로 바꿔 던지기, 401이면 로그인 화면 이동.
 */
// 화면의 API 호출은 모두 이 모듈을 거친다(code-conventions.md 5-3).
// CSRF 헤더 추가, JSON 변환, 오류 응답({code, message, fieldErrors}) 해석, 401이면 로그인 화면 이동을 맡는다.
// 템플릿은 <meta name="context-path" th:content="@{/}">와 <meta name="_csrf" th:content="${_csrf?.token}">를 둔다.

// [해석] conventions.md 'CSRF': 화면은 meta 태그로 토큰을 받고 fetch 요청에 X-CSRF-TOKEN 헤더로 보낸다.
//        Spring Security의 기본 헤더 이름과 같아서 보안 설정에서 따로 바꿀 것이 없다.
const CSRF_HEADER = 'X-CSRF-TOKEN';
const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const UNAUTHORIZED_MESSAGE = '로그인이 필요합니다.';

/** 오류 응답. status 0은 서버에 닿지 못한 경우다. */
// [해석] fetch는 4xx·5xx 응답에도 예외를 던지지 않는다. 화면 코드가 try/catch 하나로 오류를 다루도록, 실패 응답이면
//        이 예외로 바꿔 던진다. 서버 오류 본문({code, message, fieldErrors})을 그대로 담고, fieldErrors가 없으면
//        빈 배열로 둬서 화면 쪽이 null 검사 없이 돌 수 있게 했다.
export class ApiError extends Error {
    constructor(status, code, message, fieldErrors) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors ?? [];
    }
}

/** 앱 안의 경로(/api/v1/... 처럼 /로 시작)에 컨텍스트 경로를 붙인다. 관리자 앱은 /admin 아래에서 돈다. */
// [해석] 관리자 앱은 nginx에서 /admin/ 아래로 들어오고 API도 /admin/api/v1/... 이다(conventions.md 경로·nginx 규칙).
//        두 앱이 같은 JS를 쓰므로 주소를 박지 않고, 템플릿이 @{/}로 넣어 준 컨텍스트 경로를 앞에 붙인다.
//        끝의 /를 떼고 붙여서 '//api/...'가 생기지 않게 한다. 화면 이동 주소(/login 등)를 만들 때도 쓴다.
export function url(path) {
    const meta = document.querySelector('meta[name="context-path"]');
    const contextPath = (meta?.content ?? '/').replace(/\/$/, '');
    return contextPath + path;
}

export function get(path, params) {
    return request('GET', path, { params });
}

export function post(path, body) {
    return request('POST', path, { body });
}

async function request(method, path, { params, body } = {}) {
    let target = url(path);
    if (params) {
        target += '?' + new URLSearchParams(params).toString();
    }
    const headers = { Accept: 'application/json' };
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    // 보안 설정이 생기기 전에는 토큰이 없어 헤더를 붙이지 않는다
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    // [해석] GET에는 붙이지 않는다. Spring Security는 GET·HEAD 같은 안전한 메서드에는 CSRF를 검사하지 않는다.
    if (csrfToken && method !== 'GET') {
        headers[CSRF_HEADER] = csrfToken;
    }

    let response;
    try {
        response = await fetch(target, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            credentials: 'same-origin',
        });
    // [해석] 네트워크가 끊기거나 서버가 응답하지 않으면 fetch가 TypeError를 던진다. 화면에 영어 오류 대신 한국어 문장을
    //        보이도록 status 0의 ApiError로 바꾼다. 원래 예외를 쓰지 않으므로 catch 변수를 생략했다(ESLint no-unused-vars).
    } catch {
        throw new ApiError(0, null, NETWORK_ERROR_MESSAGE, []);
    }

    // [해석] code-conventions 5-3 '401 시 로그인 화면 이동'. 이동은 바로 끝나지 않으므로, 그 사이 호출한 쪽 코드가
    //        성공으로 착각하고 계속 돌지 않게 예외도 함께 던진다.
    if (response.status === 401) {
        window.location.href = url('/login');
        throw new ApiError(401, 'UNAUTHORIZED', UNAUTHORIZED_MESSAGE, []);
    }
    const data = await readJson(response);
    if (!response.ok) {
        throw new ApiError(
            response.status,
            data?.code ?? null,
            data?.message ?? DEFAULT_ERROR_MESSAGE,
            data?.fieldErrors,
        );
    }
    return data;
}

// 본문이 없거나 JSON이 아니면(프록시 오류 화면 등) null
// [해석] response.json()은 본문이 비었거나(204) HTML(프록시 오류 화면)이면 예외를 던진다.
//        text로 받아 직접 파싱하고 실패하면 null로 둔다. 그러면 위에서 기본 문구를 쓴다.
async function readJson(response) {
    const text = await response.text();
    if (!text) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}
