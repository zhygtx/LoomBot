package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.loom.auth.service.UserService;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.domain.Menu;
import com.loom.system.dto.MenuResponse;
import com.loom.system.dto.MenuSaveRequest;
import com.loom.system.mapper.MenuMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

    private static final String TYPE_CATALOG = "CATALOG";
    private static final String TYPE_MENU = "MENU";

    private final MenuMapper mapper;
    private final UserService userService;

    public MenuService(MenuMapper mapper, UserService userService) {
        this.mapper = mapper;
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

    public List<MenuResponse> navigation(long userId) {
        Set<Long> menuIds = userService.menuIds(userId);
        List<MenuResponse> result = new ArrayList<>();
        Set<Long> included = new HashSet<>();
        List<Menu> candidates =
                mapper.selectList(
                        Wrappers.<Menu>lambdaQuery()
                                .eq(Menu::getStatus, 1)
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
        if (mapper.deleteById(id) != 1) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }
    }

    @Transactional
    public void setEnabled(long id, boolean enabled) {
        Menu menu = require(id);
        menu.setStatus(enabled ? 1 : 0);
        if (mapper.updateById(menu) != 1) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
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
        if (menu.getStatus() == null) {
            menu.setStatus(1);
        }
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
                Integer.valueOf(1).equals(menu.getStatus()),
                menu.getRemark());
    }
}
