// 공통 헤더(layout.html adminHeader·userHeader)의 동작: 혼잡 단계 범례, 메뉴 열고 닫기.
// 템플릿 끝에서 fragments/layout :: scripts 로 불러온다.
import { get } from './api.js';
import { loadUncheckedCount, startNotifications } from './notifications.js';

// 관리자 헤더 범례 끝에 붙이는 밀도 단위
const LEGEND_UNIT = '명/㎡';

// 모듈이 불러와지면 곧바로 실행된다(별도 호출 없음): 메뉴 동작 연결, 범례 채우기
initMenus();
loadLegend();
// 관리자 헤더에서만 알림(미확인 배지·새 이벤트 토스트)을 받는다(FNC-A-048)
// data-header 속성으로 관리자/사용자 헤더를 구분한다
if (document.querySelector('[data-header="admin"]') !== null) {
    loadUncheckedCount();
    startNotifications();
}

// 혼잡 단계 범례(FNC-C-025, SCR-A-013). 단계 이름·색·기준은 GET /api/v1/meta/levels 값
async function loadLegend() {
    // 범례 칸이 없는 화면이면 할 일이 없다
    const legend = document.getElementById('level-legend');
    if (!legend) {
        return;
    }
    // 관리자 헤더인지에 따라 기준값·단위 표시 여부가 달라진다
    const isAdmin = document.querySelector('[data-header="admin"]') !== null;
    try {
        // 범례는 부가 정보라 실패해도 오류 토스트를 띄우지 않고 콘솔에만 남긴다
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        // 기존 항목을 모두 교체한다(다시 호출해도 중복되지 않음)
        legend.replaceChildren(...levels.map((level) => legendItem(level, isAdmin)));
        // 단위('명/㎡')는 기준값을 보이는 관리자 헤더에서만 붙인다
        if (isAdmin) {
            const unit = document.createElement('span');
            unit.className = 'muted';
            unit.textContent = LEGEND_UNIT;
            legend.append(unit);
        }
    } catch (error) {
        console.error(error);
    }
}

// 관리자 헤더는 '주의 0.7↑'처럼 기준을 함께 보이고, 사용자 헤더는 이름만 보인다(SCR-U-004)
function legendItem({ name, color, minDensity }, showThreshold) {
    // 색 점(i.dot) + 이름 한 항목을 만든다
    const item = document.createElement('span');
    const dot = document.createElement('i');
    dot.className = 'dot';
    // 혼잡 단계 색은 서버 값이므로 인라인 스타일로 칠한다
    dot.style.background = color;
    // 기준이 0인 가장 낮은 단계는 '0↑'가 의미 없어 기준을 붙이지 않는다
    const hasThreshold = showThreshold && Number(minDensity) > 0;
    item.append(dot, hasThreshold ? `${name} ${Number(minDensity)}↑` : name);
    return item;
}

/** 헤더 드롭다운 메뉴 동작: 버튼을 누르면 열고 닫고, 바깥 클릭·Esc로 모두 닫는다. */
function initMenus() {
    // data-menu 속성이 붙은 메뉴를 모두 찾는다
    const menus = document.querySelectorAll('[data-menu]');
    menus.forEach((menu) => {
        const toggle = menu.querySelector('[data-menu-toggle]');
        // 토글 버튼은 메뉴마다 따로 이벤트를 단다
        const list = menu.querySelector('[data-menu-list]');
        toggle.addEventListener('click', (event) => {
            // 이 클릭이 document 클릭 처리기까지 올라가 방금 연 메뉴를 바로 닫는 것을 막는다
            event.stopPropagation();
            // 목록이 숨김 상태면 이번 클릭은 '열기'다
            const opening = list.hidden;
            // 다른 메뉴가 열려 있으면 닫고 하나만 열리게 한다
            closeAll(menus);
            setOpen(menu, opening);
        });
    });
    // 메뉴 바깥을 누르면 열린 메뉴를 닫는다
    document.addEventListener('click', (event) => {
        menus.forEach((menu) => {
            if (!menu.contains(event.target)) {
                setOpen(menu, false);
            }
        });
    });
    // Esc 키로 모든 메뉴를 닫는다
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            closeAll(menus);
        }
    });
}

function closeAll(menus) {
    menus.forEach((menu) => setOpen(menu, false));
}

// 목록 표시 여부와 접근성 속성(aria-expanded)을 함께 맞춘다
function setOpen(menu, open) {
    menu.querySelector('[data-menu-list]').hidden = !open;
    menu.querySelector('[data-menu-toggle]').setAttribute('aria-expanded', String(open));
}
