// 화면 정적 JS 검사(code-conventions.md 5-1). 기준은 eslint:recommended
import js from '@eslint/js';
import globals from 'globals';

export default [
    {
        ignores: ['**/build/**', 'node_modules/**'],
    },
    {
        ...js.configs.recommended,
        files: ['**/src/main/resources/static/**/*.js'],
        languageOptions: {
            // 브라우저 ES2020 이상(5-1). 숫자 구분자(60_000) 등 이후 문법도 쓰므로 최신 문법으로 읽는다
            ecmaVersion: 'latest',
            sourceType: 'module',
            // Leaflet·Chart.js는 화면 템플릿이 CDN으로 불러온 전역이다
            globals: { ...globals.browser, L: 'readonly', Chart: 'readonly' },
        },
    },
    {
        // 서버 호출은 common/api.js 한 곳을 거친다(5-3). 직접 fetch하면 세션이 끝났을 때 로그인 화면으로 가지 않는다
        files: ['**/src/main/resources/static/**/*.js'],
        ignores: ['**/static/js/common/api.js'],
        rules: {
            'no-restricted-globals': ['error', { name: 'fetch', message: 'common/api.js의 함수를 쓴다(code-conventions.md 5-3)' }],
        },
    },
];
