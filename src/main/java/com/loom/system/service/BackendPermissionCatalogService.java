package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.loom.system.dto.BackendPermissionResponse;
import com.loom.system.mapper.BackendPermissionMapper;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后端权限目录的同步与读取。
 *
 * <h2>这个 Service 现在只做两件事：同步目录、读目录</h2>
 *
 * <p>原来还有第三件 —— {@code setEnabled}，给单条权限做启停。它随 {@code sys_permission.status} 一起删掉了， 理由见 {@code
 * V1__bootstrap_schema.sql} 末尾：权限「暂时不生效」是**授权**问题，落点是角色授权里的勾选， 不是一个挂在权限定义上的开关。
 *
 * <p>删掉之后连带消失的几个部件，都是为那个开关存在的，不是可以顺手留着的：
 *
 * <ul>
 *   <li>{@code CONTROL_PLANE_PERMISSIONS} —— 一份「这几个权限不许停用，否则管理端自锁」的白名单。
 *       它是为了让一个危险的开关不至于把人锁在门外而存在的；开关没了，保护对象也就没了。
 *   <li>{@code enabledRequiredPermissions} 缓存 —— 它服务于「后端要求的权限点只有启用时才生效」这条规则。 规则没了，缓存也就没有读者了。
 *   <li>{@code userService} 依赖 —— 曾经用来在改状态后清受影响用户的授权缓存。目录同步本身 不改变任何人的权限（它只补记录），不需要清缓存。
 * </ul>
 *
 * <p>这里的判据是：<b>为一个机制服务的辅助件，在机制被删除时应当一起删除</b>， 留着会让人以为「停用权限」这条路依然存在，只是入口藏起来了。
 */
@Service
public class BackendPermissionCatalogService {

    private final BackendPermissionMapper mapper;

    public BackendPermissionCatalogService(BackendPermissionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 用启动扫描到的权限串同步目录。
     *
     * <p>只做 upsert：已有记录更新 {@code backend_required} 与 {@code last_seen_time}， 新记录补一条。**不删**没被扫到的记录
     * —— 管理员手工建的通配权限（{@code module:*:*}） 本来就不会出现在 {@code @PreAuthorize} 里，删掉它们等于每次重启都把角色授权打断。
     */
    @Transactional
    public void synchronize(Set<String> permissions) {
        permissions.stream()
                .sorted()
                .forEach(
                        permission -> mapper.upsertBackendPermission(IdWorker.getId(), permission));
    }

    public List<BackendPermissionResponse> listRequired() {
        return mapper.selectBackendRequiredPermissions();
    }

    public List<BackendPermissionResponse> listAll() {
        return mapper.selectAllPermissions();
    }
}
