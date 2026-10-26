// 공통 헤더(layout.html adminHeader·userHeader)의 동작: 혼잡 단계 범례, 메뉴 열고 닫기.
// 템플릿 끝에서 fragments/layout :: scripts 로 불러온다.
import { get } from './api.js';
import { loadUncheckedCount, startNotifications } from './notifications.js';

const LEGEND_UNIT = '명/㎡';

initMenus();
loadLegend();
// 관리자 헤더에서만 알림(미확인 배지·새 이벤트 토스트)을 받는다(FNC-A-048)
if (document.querySelector('[data-header="admin"]') !== null) {
    loadUncheckedCount();
    startNotifications();
}

// 혼잡 단계 범례(FNC-C-025, SCR-A-013). 단계 이름·색·기준은 GET /api/v1/meta/levels 값
async function loadLegend() {
    const legend = document.getElementById('level-legend');
    if (!legend) {
        return;
    }
    const isAdmin = document.querySelector('[data-header="admin"]') !== null;
    try {
        const levels = await get('/api/v1/meta/levels', undefined, { toast: false });
        legend.replaceChildren(...levels.map((level) => legendItem(level, isAdmin)));
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
    const item = document.createElement('span');
    const dot = document.createElement('i');
    dot.className = 'dot';
    dot.style.background = color;
    const hasThreshold = showThreshold && Number(minDensity) > 0;
    item.append(dot, hasThreshold ? `${name} ${Number(minDensity)}↑` : name);
    return item;
}

function initMenus() {
    const menus = document.querySelectorAll('[data-menu]');
    menus.forEach((menu) => {
        const toggle = menu.querySelector('[data-menu-toggle]');
        const list = menu.querySelector('[data-menu-list]');
        toggle.addEventListener('click', (event) => {
            event.stopPropagation();
            const opening = list.hidden;
            closeAll(menus);
            setOpen(menu, opening);
        });
    });
    document.addEventListener('click', (event) => {
        menus.forEach((menu) => {
            if (!menu.contains(event.target)) {
                setOpen(menu, false);
            }
        });
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            closeAll(menus);
        }
    });
}

function closeAll(menus) {
    menus.forEach((menu) => setOpen(menu, false));
}

function setOpen(menu, open) {
    menu.querySelector('[data-menu-list]').hidden = !open;
    menu.querySelector('[data-menu-toggle]').setAttribute('aria-expanded', String(open));
}
