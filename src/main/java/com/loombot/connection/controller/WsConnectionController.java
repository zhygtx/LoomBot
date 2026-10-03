package com.loombot.connection.controller;

import com.loombot.common.api.ErrorCode;
import com.loombot.common.api.PageResult;
import com.loombot.common.api.Result;
import com.loombot.common.exception.BusinessException;
import com.loombot.common.security.CurrentUser;
import com.loombot.connection.domain.ConnectionStatus;
import com.loombot.connection.domain.ConnectionTypeDescriptor;
import com.loombot.connection.dto.ConnectionCreateRequest;
import com.loombot.connection.dto.ConnectionResponse;
import com.loombot.connection.dto.ConnectionUpdateRequest;
import com.loombot.connection.manager.ConnectionManager;
import com.loombot.connection.service.WsConnectionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WS 连接管理接口。
 *
 * <h2>两类接口，权限点分开</h2>
 *
 * <p>「配置」类（增删改查）与「运行时」类（启停 / 状态）用不同权限点。原因是它们 的操作后果完全不同：改配置是改数据，启停是在**动线上连接** —— 停掉一条正在跑的 bot
 * 连接，用户是立刻能感知到的。混成一个权限点的话，想给「只读运维」开状态查看 就不得不连改配置的权限一起给。
 *
 * <h2>{@code /types} 为什么是独立资源而不是查某条连接</h2>
 *
 * <p>连接类型的真相在适配器（插件）里，不在数据库里。前端要渲染「新建连接」表单时 还没有任何连接，只能问「现在有哪些类型可用」。所以它是列表接口，不是子资源。
 */
@RestController
@RequestMapping("/api/connection")
public class WsConnectionController {

    private final WsConnectionService service;
    private final ConnectionManager manager;

    public WsConnectionController(WsConnectionService service, ConnectionManager manager) {
        this.service = service;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // 连接类型（来自适配器声明）
    // ------------------------------------------------------------------

    /**
     * 当前可用的连接类型，用于渲染配置表单。
     *
     * <p>返回类型描述本身就是读模型（不是持久化实体），所以直接返回，不再套一层 DTO —— 多一层 只会让「插件加了字段」变成「两处都要改」。
     */
    @GetMapping("/types")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:list')")
    public Result<List<ConnectionTypeDescriptor>> types() {
        return Result.success(manager.connectionTypes());
    }

    // ------------------------------------------------------------------
    // 配置 CRUD
    // ------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@permission.has(authentication, 'connection:ws:list')")
    public Result<PageResult<ConnectionResponse>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String connectionType,
            @RequestParam(required = false) Integer enabled) {
        checkPage(pageNum, pageSize);
        return Result.success(
                service.page(
                        pageNum,
                        pageSize,
                        keyword,
                        connectionType,
                        enabled,
                        CurrentUser.requireId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:read')")
    public Result<ConnectionResponse> detail(@PathVariable long id) {
        return Result.success(service.get(id, CurrentUser.requireId()));
    }

    @PostMapping
    @PreAuthorize("@permission.has(authentication, 'connection:ws:create')")
    public Result<ConnectionResponse> create(@Valid @RequestBody ConnectionCreateRequest request) {
        return Result.success(service.create(request, CurrentUser.requireId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:update')")
    public Result<ConnectionResponse> update(
            @PathVariable long id, @Valid @RequestBody ConnectionUpdateRequest request) {
        return Result.success(service.update(id, request, CurrentUser.requireId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:delete')")
    public Result<Void> delete(@PathVariable long id) {
        service.delete(id, CurrentUser.requireId());
        return Result.success();
    }

    // ------------------------------------------------------------------
    // 运行时
    // ------------------------------------------------------------------

    @GetMapping("/{id}/status")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:read')")
    public Result<ConnectionStatus> status(@PathVariable long id) {
        return Result.success(service.status(id, CurrentUser.requireId()));
    }

    @GetMapping("/statuses")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:list')")
    public Result<List<ConnectionStatus>> statuses() {
        return Result.success(service.statuses(CurrentUser.requireId()));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:operate')")
    public Result<ConnectionResponse> enable(@PathVariable long id) {
        return Result.success(service.setEnabled(id, true, CurrentUser.requireId()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("@permission.has(authentication, 'connection:ws:operate')")
    public Result<ConnectionResponse> disable(@PathVariable long id) {
        return Result.success(service.setEnabled(id, false, CurrentUser.requireId()));
    }

    /**
     * 分页参数上限。
     *
     * <p>不挡的话 {@code pageSize=1000000} 会一次性把整表捞进内存 —— 这是最容易被随手打出来的 拒绝服务。用 400
     * 而不是静默截断：静默截断会让人以为「数据就这么多」。
     */
    private static void checkPage(long pageNum, long pageSize) {
        if (pageNum < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > 200) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "每页条数必须在 1~200 之间");
        }
    }
}
