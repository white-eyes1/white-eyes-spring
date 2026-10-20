// 화면의 API 호출은 모두 이 모듈을 거친다(code-conventions.md 5-3).
// CSRF 헤더 추가, JSON 변환, 오류 응답({code, message, fieldErrors}) 해석, 401이면 로그인 화면 이동을 맡는다.
// 템플릿은 <meta name="context-path" th:content="@{/}">와 <meta name="_csrf" th:content="${_csrf?.token}">를 둔다.

const CSRF_HEADER = 'X-CSRF-TOKEN';
const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.';
const DEFAULT_ERROR_MESSAGE = '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const UNAUTHORIZED_MESSAGE = '로그인이 필요합니다.';

/** 오류 응답. status 0은 서버에 닿지 못한 경우다. */
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
    } catch {
        throw new ApiError(0, null, NETWORK_ERROR_MESSAGE, []);
    }

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
