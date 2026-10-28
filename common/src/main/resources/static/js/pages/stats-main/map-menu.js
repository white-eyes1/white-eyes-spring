// 대시보드 지도 우클릭 메뉴(SCR-C-003, 관리자 앱만). 메뉴 틀만 맡고, 항목과 그 동작은 stats-main.js가 정한다.
// 서버 값(구역 이름)은 textContent로만 넣는다(code-conventions 5-3).

const EDGE_GAP_PX = 8;

/**
 * 우클릭 메뉴를 만든다. 메뉴 칸(#map-menu)은 templates/stats/main.html에 있다.
 * @param {{onClose?: () => void}} [handlers] onClose: 메뉴가 닫힐 때(구역 강조를 풀 때 쓴다)
 * @returns {{open: (menu: {x: number, y: number, title: string, items: MenuItem[]}) => void, close: () => void, isOpen: () => boolean}}
 * MenuItem: {label: string, onClick: () => void, disabled?: boolean, hint?: string, danger?: boolean}
 *   disabled이면 눌러도 반응하지 않고, hint(꺼진 이유)를 항목 아래에 보인다
 */
export function createMapMenu({ onClose } = {}) {
    const menu = document.getElementById('map-menu');

    // 메뉴 밖을 누르거나 Esc를 누르거나 창이 바뀌면 닫는다
    document.addEventListener('mousedown', (event) => {
        if (!menu.hidden && !menu.contains(event.target)) {
            close();
        }
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && !menu.hidden) {
            close();
        }
    });
    window.addEventListener('blur', close);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);

    function open({ x, y, title, items }) {
        if (items.length === 0) {
            close();
            return;
        }
        const heading = document.createElement('div');
        heading.className = 'map-menu-title';
        heading.textContent = title;
        menu.replaceChildren(heading, ...items.map(buildItem));
        menu.hidden = false;
        // 화면 밖으로 나가지 않게 밀어 넣는다
        const rect = menu.getBoundingClientRect();
        const left = Math.max(EDGE_GAP_PX, Math.min(x, window.innerWidth - rect.width - EDGE_GAP_PX));
        const top = Math.max(EDGE_GAP_PX, Math.min(y, window.innerHeight - rect.height - EDGE_GAP_PX));
        menu.style.left = `${left}px`;
        menu.style.top = `${top}px`;
        menu.querySelector('button:not([disabled])')?.focus();
    }

    function close() {
        const wasOpen = !menu.hidden;
        menu.hidden = true;
        if (wasOpen) {
            onClose?.();
        }
    }

    function buildItem(item) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-menu-item' + (item.danger ? ' danger' : '');
        button.setAttribute('role', 'menuitem');
        const label = document.createElement('span');
        label.textContent = item.label;
        button.append(label);
        if (item.disabled) {
            button.disabled = true;
            if (item.hint) {
                const hint = document.createElement('small');
                hint.textContent = item.hint;
                button.append(hint);
            }
        } else {
            button.addEventListener('click', () => {
                close();
                item.onClick();
            });
        }
        return button;
    }

    return { open, close, isOpen: () => !menu.hidden };
}
