// 화면의 API 호출은 모두 이 모듈을 거친다(code-conventions.md 5-3).
// CSRF 헤더 추가, JSON 변환, 오류 응답({code, message, fieldErrors}) 해석, 401이면 로그인 화면 이동을 맡는다.
// 400 fieldErrors는 호출한 쪽이 입력 아래에 보이고, 그 밖의 오류는 기본으로 토스트(toast.js)에 message를 띄운다.
// 호출한 쪽이 직접 보여 줄 때는 세 번째 인자로 { toast: false }를 준다.
// 템플릿은 <meta name="context-path" th:content="@{/}">와 <meta name="_csrf" th:content="${_csrf?.token}">를 둔다.

import { showToast } from './toast.js';

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

async function request(method, path, { params, body, toast = true } = {}) {
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
        return fail(new ApiError(0, null, NETWORK_ERROR_MESSAGE, []), toast);
    }

    if (response.status === 401) {
        window.location.href = url('/login');
        throw new ApiError(401, 'UNAUTHORIZED', UNAUTHORIZED_MESSAGE, []);
    }
    const data = await readJson(response);
    if (!response.ok) {
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
function fail(error, toast) {
    if (toast && error.fieldErrors.length === 0) {
        showToast(error.message);
    }
    throw error;
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
