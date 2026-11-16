// 화면의 서버 호출은 JSON API·파일 응답 모두 이 모듈을 거친다(code-conventions.md 5-3).
// CSRF 헤더 추가, JSON 변환, 오류 응답({code, message, fieldErrors}) 해석, 401이면 로그인 화면 이동을 맡는다.
// 400 fieldErrors는 호출한 쪽이 입력 아래에 보이고, 그 밖의 오류는 기본으로 토스트(toast.js)에 message를 띄운다.
// 호출한 쪽이 직접 보여 줄 때는 마지막 인자로 { toast: false }를 준다.
// 마지막 인자의 다른 옵션:
// - absolute: true면 path를 그대로 쓴다. 서버가 컨텍스트 경로까지 붙여 준 주소(boxesUrl 등)에 쓴다.
// - signal·cache: fetch에 그대로 넘긴다. signal로 끊기면 status 0 ApiError가 되므로 { toast: false }와 같이 쓴다.
// 템플릿은 <meta name="context-path" th:content="@{/}">와 <meta name="_csrf" th:content="${_csrf?.token}">를 둔다.

import { showToast } from './toast.js';

// 서버가 CSRF 검증에 쓰는 요청 헤더 이름(Spring Security 기본값). 토큰 값은 <meta name="_csrf">에서 읽는다.
const CSRF_HEADER = 'X-CSRF-TOKEN';
// 서버에 아예 닿지 못했을 때(오프라인·서버 다운) 토스트에 보일 문구
const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.';
// 서버가 오류 본문(message)을 주지 않았을 때(프록시 오류 화면 등) 대신 보일 기본 문구
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
// 401 응답 때 ApiError에 담는 문구(실제로는 곧바로 로그인 화면으로 이동한다)
const UNAUTHORIZED_MESSAGE = '로그인이 필요합니다.';

/**
 * 오류 응답을 담는 예외. 호출한 쪽이 status·code로 분기하고, 400이면 fieldErrors를 입력 아래에 보여 준다.
 * status 0은 서버에 닿지 못한 경우다.
 */
/** 오류 응답. status 0은 서버에 닿지 못한 경우다. */
export class ApiError extends Error {
    // status: HTTP 상태, code: 서버 오류 코드(없으면 null), fieldErrors: 입력 검증 실패 목록
    constructor(status, code, message, fieldErrors) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.code = code;
        // fieldErrors가 없는 응답(400이 아닌 오류)도 항상 배열로 만들어 호출한 쪽이 .length를 바로 볼 수 있게 한다
        this.fieldErrors = fieldErrors ?? [];
    }
}

/** 앱 안의 경로(/api/v1/... 처럼 /로 시작)에 컨텍스트 경로를 붙인다. 관리자 앱은 /admin 아래에서 돈다. */
export function url(path) {
    // 템플릿 <head>의 context-path 메타에서 앱 루트 경로를 읽는다(없으면 '/')
    const meta = document.querySelector('meta[name="context-path"]');
    // 끝의 '/'를 떼어 둔다. path가 '/'로 시작하므로 그대로 붙이면 '//'가 되기 때문이다
    const contextPath = (meta?.content ?? '/').replace(/\/$/, '');
    return contextPath + path;
}

// 아래 get/post/put/patch/del은 request에 HTTP 메서드만 달리 넘기는 얇은 껍데기다.
// params는 쿼리스트링(GET), body는 JSON 본문(POST·PUT·PATCH), options는 { toast, absolute, signal, cache } 등이다.
export function get(path, params, options) {
    return request('GET', path, { params, ...options });
}

export function post(path, body, options) {
    return request('POST', path, { body, ...options });
}

export function put(path, body, options) {
    return request('PUT', path, { body, ...options });
}

export function patch(path, body, options) {
    return request('PATCH', path, { body, ...options });
}

export function del(path, options) {
    return request('DELETE', path, { ...options });
}

/**
 * 파일을 받는다(GET). 성공하면 { blob, fileName }을 돌려준다. fileName은 Content-Disposition의 filename*에서 읽는다.
 * 오류 응답은 다른 호출과 같이 ApiError로 던진다. params는 같은 이름을 여러 번 보내려면 [이름, 값] 쌍 배열로 준다.
 */
export function download(path, params, options) {
    return request('GET', path, { params, ...options, file: true });
}

// 모든 호출의 실제 처리부: 주소 만들기 -> 헤더 -> fetch -> 상태별 분기(401 이동, 파일, JSON, 오류)
async function request(
    method,
    path,
    { params, body, toast = true, file = false, absolute = false, signal, cache } = {},
) {
    // absolute면 서버가 컨텍스트 경로까지 붙여 준 주소이므로 그대로 쓴다
    let target = absolute ? path : url(path);
    // GET 쿼리스트링. 같은 이름 여러 개는 호출한 쪽이 [이름, 값] 쌍 배열로 넘긴다
    if (params) {
        target += '?' + new URLSearchParams(params).toString();
    }
    // 응답은 JSON으로 받겠다고 알린다(파일 응답이어도 오류는 JSON으로 오기 때문)
    const headers = { Accept: 'application/json' };
    // 본문이 있을 때만 JSON 본문임을 밝힌다
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    // 보안 설정이 생기기 전에는 토큰이 없어 헤더를 붙이지 않는다
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    // 서버 변경 요청(GET 제외)에만 CSRF 토큰을 붙인다
    if (csrfToken && method !== 'GET') {
        headers[CSRF_HEADER] = csrfToken;
    }

    // try 밖에서 쓰려고 먼저 선언한다
    let response;
    try {
        // 같은 출처 쿠키(세션)를 함께 보낸다. signal은 화면이 요청을 취소할 때, cache는 캐시 정책을 정할 때 쓴다
        response = await fetch(target, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            credentials: 'same-origin',
            signal,
            cache,
        });
    // fetch 자체가 실패한 경우(네트워크 단절·취소). status 0으로 알린다
    } catch {
        return fail(new ApiError(0, null, NETWORK_ERROR_MESSAGE, []), toast);
    }

    // 세션이 끝난 경우: 로그인 화면으로 보내고, 호출한 쪽 후속 처리가 돌지 않게 예외도 던진다
    if (response.status === 401) {
        window.location.href = url('/login');
        throw new ApiError(401, 'UNAUTHORIZED', UNAUTHORIZED_MESSAGE, []);
    }
    // 파일 다운로드는 JSON이 아니라 blob으로 돌려준다. 오류면 아래 JSON 경로로 내려가 ApiError가 된다
    if (file && response.ok) {
        return { blob: await response.blob(), fileName: fileNameOf(response) };
    }
    // 성공·실패 모두 본문을 JSON으로 읽는다(본문이 없거나 JSON이 아니면 null)
    const data = await readJson(response);
    if (!response.ok) {
        // 서버가 준 code·message·fieldErrors를 ApiError로 옮긴다. 본문을 못 읽었으면 기본 문구를 쓴다
        return fail(
            new ApiError(
                response.status,
                data?.code ?? null,
                data?.message ?? DEFAULT_ERROR_MESSAGE,
                data?.fieldErrors,
            ),
            toast,
        );
    }
    return data;
}

// 입력 검증 오류(400 fieldErrors)는 입력 아래에 보이므로 토스트를 띄우지 않는다
/** 오류를 알리고 던진다. fail은 항상 throw 하므로 request의 return fail(...)은 값을 돌려주지 않는다. */
function fail(error, toast) {
    if (toast && error.fieldErrors.length === 0) {
        showToast(error.message);
    }
    throw error;
}

// Content-Disposition: attachment; filename="…"; filename*=UTF-8''… 에서 filename*를 먼저 읽는다. 없으면 null
// 응답 헤더에서 파일 이름을 꺼낸다. 한글 이름은 filename*(UTF-8 퍼센트 인코딩)에만 제대로 들어 있어 먼저 본다
function fileNameOf(response) {
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (encoded) {
        return decodeURIComponent(encoded[1]);
    // filename*가 없으면 따옴표로 감싼 일반 filename을 쓴다
    }
    const plain = /filename="([^"]+)"/i.exec(disposition);
    return plain ? plain[1] : null;
}

// 본문이 없거나 JSON이 아니면(프록시 오류 화면 등) null
async function readJson(response) {
    // 본문은 한 번만 읽을 수 있어 text()로 먼저 읽고 직접 파싱한다(빈 본문에서 json()이 예외를 던지는 것을 피함)
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
