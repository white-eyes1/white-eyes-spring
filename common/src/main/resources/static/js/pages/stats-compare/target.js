// 기간 비교의 대상 고르기(시·도 → 시·군·구 → 읍·면·동 → 구역). 고른 가장 아래 단위가 대상이다.
// 통계 메인·통계 그래프(#25)와 같은 방법으로 주소의 대상에서 시·구·동 경로를 찾는다.
import { get } from '../../common/api.js';

const LEVEL_LABELS = ['시·도', '시·군·구', '읍·면·동'];

/**
 * @param regionSelects 시·도, 시·군·구, 읍·면·동 select 세 개
 * @param zoneSelect 구역 select
 * @param onChange 사용자가 대상을 바꾸면 부른다
 */
export function createTargetSelect({ regionSelects, zoneSelect, onChange }) {
    const childrenCache = new Map(); // parentId(없으면 'root') -> 하위 지역 목록
    const zonesCache = new Map(); // dongId -> 구역 목록
    let path = []; // 시·도부터 고른 지역 목록
    let zone = null; // 고른 구역(없으면 지역이 대상)

    async function children(parentId) {
        const key = parentId ?? 'root';
        if (!childrenCache.has(key)) {
            childrenCache.set(key, await get('/api/v1/regions', parentId === null ? undefined : { parentId }));
        }
        return childrenCache.get(key);
    }

    async function zonesOf(dongId) {
        if (!zonesCache.has(dongId)) {
            zonesCache.set(dongId, await get('/api/v1/zones', { dongId }));
        }
        return zonesCache.get(dongId);
    }

    // 상위 지역을 되묻는 API가 없어 위에서부터 한 단계씩 내려가며 찾는다
    async function findPath(regionId) {
        let frontier = (await children(null)).map((region) => [region]);
        for (let depth = 0; depth < LEVEL_LABELS.length && frontier.length > 0; depth++) {
            const hit = frontier.find((candidate) => candidate[depth].id === regionId);
            if (hit) {
                return hit;
            }
            if (depth === LEVEL_LABELS.length - 1) {
                break;
            }
            const next = [];
            for (const candidate of frontier) {
                const kids = await children(candidate[depth].id);
                kids.forEach((kid) => next.push([...candidate, kid]));
            }
            frontier = next;
        }
        return null;
    }

    /** 주소의 대상으로 고른다. 없거나 찾지 못하면 첫 시 단위(통계 메인과 같음). */
    async function select(targetType, targetId) {
        let found = null;
        zone = null;
        if (targetType === 'ZONE' && targetId) {
            // 구역은 소속 동을 알아야 경로를 찾을 수 있어 전체 구역에서 찾는다
            zone = (await get('/api/v1/zones')).find((candidate) => candidate.id === targetId) ?? null;
            found = zone ? await findPath(zone.dongId) : null;
            if (!found) {
                zone = null;
            }
        } else if (targetType === 'REGION' && targetId) {
            found = await findPath(targetId);
        }
        if (!found) {
            const top = await children(null);
            found = top.length > 0 ? [top[0]] : [];
        }
        path = found;
        await render();
    }

    async function render() {
        for (let depth = 0; depth < regionSelects.length; depth++) {
            const element = regionSelects[depth];
            const options =
                depth === 0 ? await children(null) : path[depth - 1] ? await children(path[depth - 1].id) : [];
            element.replaceChildren(...optionList(options, depth > 0 ? `${LEVEL_LABELS[depth]} 전체` : null));
            element.value = path[depth] ? String(path[depth].id) : '';
            element.disabled = options.length === 0 || (depth > 0 && !path[depth - 1]);
            element.onchange = () => onRegionChange(depth, element.value);
        }
        const dong = path[LEVEL_LABELS.length - 1];
        const zones = dong ? await zonesOf(dong.id) : [];
        zoneSelect.replaceChildren(...optionList(zones, '구역 전체'));
        zoneSelect.value = zone ? String(zone.id) : '';
        zoneSelect.disabled = zones.length === 0;
        zoneSelect.onchange = () => {
            zone = zones.find((candidate) => String(candidate.id) === zoneSelect.value) ?? null;
            onChange();
        };
    }

    async function onRegionChange(depth, value) {
        const base = path.slice(0, depth);
        if (value !== '') {
            const options = depth === 0 ? await children(null) : await children(path[depth - 1].id);
            base.push(options.find((region) => String(region.id) === value));
        }
        path = base;
        zone = null;
        await render();
        onChange();
    }

    /** 지금 대상. 지역이 하나도 없으면 null. */
    function current() {
        if (zone) {
            return { targetType: 'ZONE', targetId: zone.id };
        }
        return path.length > 0 ? { targetType: 'REGION', targetId: path[path.length - 1].id } : null;
    }

    return { select, current };
}

function optionList(entries, allLabel) {
    const list = entries.map((entry) => {
        const option = document.createElement('option');
        option.value = String(entry.id);
        option.textContent = entry.name;
        return option;
    });
    if (allLabel) {
        const all = document.createElement('option');
        all.value = '';
        all.textContent = allLabel;
        list.unshift(all);
    }
    return list;
}
