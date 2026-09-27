package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.loom.auth.service.UserService;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.domain.Menu;
import com.loom.system.dto.MenuResponse;
import com.loom.system.dto.MenuSaveRequest;
import com.loom.system.dto.MenuSortGroup;
import com.loom.system.mapper.MenuMapper;
import com.loom.system.mapper.RoleRelationMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

    private static final String TYPE_CATALOG = "CATALOG";
    private static final String TYPE_MENU = "MENU";

    /** 同级排序的刻度。和 V1 种子数据里的 10 / 20 一致。 */
    private static final int SORT_STEP = 10;

    private final MenuMapper mapper;
    private final RoleRelationMapper relations;
    private final UserService userService;

    public MenuService(MenuMapper mapper, RoleRelationMapper relations, UserService userService) {
        this.mapper = mapper;
        this.relations = relations;
        this.userService = userService;
    }

    public List<MenuResponse> list() {
        return mapper
                .selectList(
                        Wrappers.<Menu>lambdaQuery()
                                .orderByAsc(Menu::getParentId)
                                .orderByAsc(Menu::getSort)
                                .orderByAsc(Menu::getId))
                .stream()
                .map(MenuService::toResponse)
                .toList();
    }

    /**
     * 当前用户可见的导航条目。
     *
     * <h2>过滤条件只剩 {@code visible}</h2>
     *
     * <p>原来还有一个 {@code status = 1}（菜单启停）。那一列已删除（见 {@code V1__bootstrap_schema.sql}
     * 末尾），因为「停用一个菜单」和「删掉一个菜单」在结果上完全一样。于是这里只剩 {@code visible}： 它是**显式声明**的「隐藏但保留」，语义和删除不重叠。
     *
     * <h2>为什么要逐层判断父级，而不是一条 SQL 查完</h2>
     *
     * <p>见下方循环的说明：只筛子级会让「父目录被隐藏、子菜单还在列表里」变成一条点不开的链接。
     */
    public List<MenuResponse> navigation(long userId) {
        Set<Long> menuIds = userService.menuIds(userId);
        List<MenuResponse> result = new ArrayList<>();
        Set<Long> included = new HashSet<>();
        List<Menu> candidates =
                mapper.selectList(
                        Wrappers.<Menu>lambdaQuery()
                                .eq(Menu::getVisible, 1)
                                .orderByAsc(Menu::getParentId)
                                .orderByAsc(Menu::getSort)
                                .orderByAsc(Menu::getId));
        // 只纳入父节点也可见的条目；不能用数据库递归路径绕过父级状态。
        boolean changed;
        do {
            changed = false;
            for (Menu menu : candidates) {
                if (included.contains(menu.getId())
                        || (menu.getParentId() != 0 && !included.contains(menu.getParentId()))
                        || !menuIds.contains(menu.getId())) {
                    continue;
                }
                included.add(menu.getId());
                result.add(toResponse(menu));
                changed = true;
            }
        } while (changed);
        return result;
    }

    @Transactional
    public MenuResponse create(MenuSaveRequest request) {
        Menu menu = new Menu();
        menu.setId(IdWorker.getId());
        apply(menu, request);
        try {
            mapper.insert(menu);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.MENU_ROUTE_EXISTS);
        }
        return toResponse(require(menu.getId()));
    }

    @Transactional
    public MenuResponse update(long id, MenuSaveRequest request) {
        Menu menu = require(id);
        apply(menu, request);
        try {
            if (mapper.updateById(menu) != 1) {
                throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.MENU_ROUTE_EXISTS);
        }
        return toResponse(require(id));
    }

    @Transactional
    public void delete(long id) {
        require(id);
        if (mapper.countChildren(id) > 0) {
            throw new BusinessException(ErrorCode.MENU_HAS_CHILDREN);
        }
        // 真删之前先清角色菜单关联。没有外键，数据库不会级联，留着就是孤儿关系行：
        // 菜单已经不存在，sys_role_menu 里却还挂着一行，做「角色菜单」查询时 join 出空结果，
        // 前端表现为「勾选的菜单莫名少了一个」。
        relations.clearRolesOfMenu(id);
        if (mapper.deleteById(id) != 1) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }
    }

    /**
     * 批量排序：把若干层级的菜单重新定序（并允许顺带改父级）。
     *
     * <h2>为什么请求是「分组」而不是「一组 id + 一个目标父级」</h2>
     *
     * <p>拖拽一次可能同时改变两个层级：从旧父级的列表里摘掉、插进新父级的列表。分组形式让这两件事落在 同一个事务里，不会出现「已经从旧父级摘掉、还没挂到新父级」的中间态。
     *
     * <h2>为什么环检测是「整体判一次」而不是「逐条边判」</h2>
     *
     * <p>逐条边判断在批量移动时会算错：A 移到 B 下、同时 B 移到 C 下，单看每一条边都合法，合起来才成环。 所以先把新的父子关系全部套到内存里的 map
     * 上，再对每个节点走一遍到根的路径。菜单总数是几十条量级， 多这一趟遍历换的是「不会漏判」。
     *
     * @param groups 只包含受影响的层级，每层是完整的兄弟顺序
     */
    @Transactional
    public void resort(List<MenuSortGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return;
        }

        Map<Long, Menu> all = new HashMap<>();
        for (Menu menu : mapper.selectList(null)) {
            all.put(menu.getId(), menu);
        }

        // 1) ID 与父级必须存在；同一个菜单不能同时出现在两个层级里（否则它的 parent_id 取决于更新顺序）
        Set<Long> scheduled = new HashSet<>();
        for (MenuSortGroup group : groups) {
            long parentId = group.parentId();
            if (parentId > 0 && !all.containsKey(parentId)) {
                throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "父级菜单不存在");
            }
            for (Long id : group.ids()) {
                if (!all.containsKey(id)) {
                    throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "菜单不存在: " + id);
                }
                if (!scheduled.add(id)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "同一个菜单不能出现在两个层级: " + id);
                }
            }
        }

        // 2) 先在内存里套用新的父子关系，再整体判环
        Map<Long, Long> parents = new HashMap<>();
        all.forEach((id, menu) -> parents.put(id, menu.getParentId()));
        for (MenuSortGroup group : groups) {
            for (Long id : group.ids()) {
                parents.put(id, group.parentId());
            }
        }
        for (Long id : parents.keySet()) {
            Set<Long> path = new HashSet<>();
            long cursor = id;
            while (cursor > 0) {
                if (!path.add(cursor)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "父菜单不能形成循环");
                }
                Long next = parents.get(cursor);
                if (next == null) {
                    throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "父菜单不存在");
                }
                cursor = next;
            }
        }

        // 3) 落库。sort 按 10 递增，和 V1 种子的 10 / 20 保持同一套刻度，
        //    以后手工插一条也能落在两条之间，不必整体重排。
        for (MenuSortGroup group : groups) {
            List<Long> ids = group.ids();
            for (int index = 0; index < ids.size(); index++) {
                Menu update = new Menu();
                update.setId(ids.get(index));
                update.setParentId(group.parentId());
                update.setSort((index + 1) * SORT_STEP);
                if (mapper.updateById(update) != 1) {
                    throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
                }
            }
        }
    }

    private void apply(Menu menu, MenuSaveRequest request) {
        String type = normalizeType(request.type());
        long parentId = request.parentId() == null ? 0L : request.parentId();
        if (parentId < 0 || (menu.getId() != null && parentId == menu.getId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "父菜单不合法");
        }
        if (parentId > 0) {
            Set<Long> visited = new HashSet<>();
            long ancestorId = parentId;
            while (ancestorId > 0) {
                if (!visited.add(ancestorId) || ancestorId == menu.getId()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "父菜单不能形成循环");
                }
                Menu ancestor = mapper.selectById(ancestorId);
                if (ancestor == null) {
                    throw new BusinessException(ErrorCode.MENU_NOT_FOUND, "父菜单不存在");
                }
                ancestorId = ancestor.getParentId();
            }
        }
        if (TYPE_MENU.equals(type)
                && (isBlank(request.routeName())
                        || isBlank(request.path())
                        || isBlank(request.componentKey()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单类型必须填写路由名称、路由地址和组件标识");
        }

        menu.setParentId(parentId);
        menu.setType(type);
        menu.setName(request.name().strip());
        menu.setRouteName(normalize(request.routeName()));
        menu.setPath(normalize(request.path()));
        menu.setComponentKey(normalize(request.componentKey()));
        menu.setIconKey(normalize(request.iconKey()));
        menu.setRedirect(normalize(request.redirect()));
        menu.setSort(request.sort() == null ? 0 : request.sort());
        menu.setVisible(Boolean.FALSE.equals(request.visible()) ? 0 : 1);
        menu.setKeepAlive(Boolean.TRUE.equals(request.keepAlive()) ? 1 : 0);
        menu.setRemark(normalize(request.remark()));
    }

    private Menu require(long id) {
        Menu menu = mapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }
        return menu;
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!TYPE_CATALOG.equals(type) && !TYPE_MENU.equals(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "菜单类型只能是 CATALOG 或 MENU");
        }
        return type;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static MenuResponse toResponse(Menu menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getParentId(),
                menu.getType(),
                menu.getName(),
                menu.getRouteName(),
                menu.getPath(),
                menu.getComponentKey(),
                menu.getIconKey(),
                menu.getRedirect(),
                menu.getSort(),
                Integer.valueOf(1).equals(menu.getVisible()),
                Integer.valueOf(1).equals(menu.getKeepAlive()),
                menu.getRemark());
    }
}
