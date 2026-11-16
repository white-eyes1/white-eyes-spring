// 대시보드 지도 우클릭 메뉴(SCR-C-003, 관리자 앱만). 메뉴 틀만 맡고, 항목과 그 동작은 stats-main.js가 정한다.
// 서버 값(구역 이름)은 textContent로만 넣는다(code-conventions 5-3).

// 메뉴가 화면 가장자리에 붙지 않게 두는 여백(px)
const EDGE_GAP_PX = 8;

/**
 * 우클릭 메뉴를 만든다. 메뉴 칸(#map-menu)은 templates/stats/main.html에 있다.
 * @param {{onClose?: () => void}} [handlers] onClose: 메뉴가 닫힐 때(구역 강조를 풀 때 쓴다)
 * @returns {{open: (menu: {x: number, y: number, title: string, items: MenuItem[]}) => void, close: () => void, isOpen: () => boolean}}
 * MenuItem: {label: string, onClick: () => void, disabled?: boolean, hint?: string, danger?: boolean}
 *   disabled이면 눌러도 반응하지 않고, hint(꺼진 이유)를 항목 아래에 보인다
 */
export function createMapMenu({ onClose } = {}) {
    // 템플릿(main.html)에 미리 있는 메뉴 틀
    const menu = document.getElementById('map-menu');

    // 메뉴 밖을 누르거나 Esc를 누르거나 창이 바뀌면 닫는다
    // 메뉴 바깥을 누르면 닫는다
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
    // 창이 포커스를 잃거나 크기가 바뀌거나 스크롤되면 위치가 어긋나므로 닫는다. scroll은 캡처 단계(true)로 받아 안쪽 스크롤도 잡는다
    window.addEventListener('blur', close);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);

    // 메뉴를 연다: 제목과 항목을 채우고, 화면 안에 들어오도록 위치를 조정한다
    function open({ x, y, title, items }) {
        // 항목이 없으면 메뉴를 띄우지 않는다
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
        const left = Math.max(
            EDGE_GAP_PX,
            Math.min(x, window.innerWidth - rect.width - EDGE_GAP_PX),
        );
        const top = Math.max(
            EDGE_GAP_PX,
            Math.min(y, window.innerHeight - rect.height - EDGE_GAP_PX),
        );
        menu.style.left = `${left}px`;
        menu.style.top = `${top}px`;
        // 포커스가 문서를 스크롤하면 아래 scroll 규칙이 열자마자 메뉴를 닫으므로, 스크롤 없이 포커스만 준다
        // 키보드 사용자를 위해 첫 활성 항목에 포커스
        menu.querySelector('button:not([disabled])')?.focus({ preventScroll: true });
    }

    // 메뉴를 닫는다. 이미 열려 있었을 때만 onClose를 부른다
    function close() {
        const wasOpen = !menu.hidden;
        menu.hidden = true;
        if (wasOpen) {
            onClose?.();
        }
    }

    // 메뉴 항목 하나(버튼). 위험한 항목은 danger 클래스로 강조
    function buildItem(item) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'map-menu-item' + (item.danger ? ' danger' : '');
        button.setAttribute('role', 'menuitem');
        const label = document.createElement('span');
        label.textContent = item.label;
        button.append(label);
        // 비활성 항목은 눌러도 반응하지 않고 이유(hint)를 보인다
        if (item.disabled) {
            button.disabled = true;
            if (item.hint) {
                const hint = document.createElement('small');
                hint.textContent = item.hint;
                button.append(hint);
            }
        } else {
            // 항목을 누르면 메뉴를 먼저 닫고 동작을 실행한다
            button.addEventListener('click', () => {
                close();
                item.onClick();
            });
        }
        return button;
    }

    // 외부에 노출하는 함수들
    return { open, close, isOpen: () => !menu.hidden };
}
