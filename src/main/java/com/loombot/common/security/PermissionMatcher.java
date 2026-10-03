package com.loombot.common.security;

import com.loombot.system.service.BackendPermissionCatalogService;
import java.lang.reflect.AnnotatedElement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 方法级权限匹配与后端权限要求加载器。
 *
 * <p>接口要求固定为三段式 domain:resource:action。用户持有的权限也是固定三段式 glob 模式，例如 connection:ws:*、connection:*:* 和
 * *:*:*。
 */
@Component("permission")
public class PermissionMatcher {

    private static final Logger log = LoggerFactory.getLogger(PermissionMatcher.class);

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String EXPRESSION_PREFIX = "@permission.has";
    private static final int REQUIRED_SEGMENT_COUNT = 3;

    private final RequestMappingHandlerMapping handlerMapping;
    private final BackendPermissionCatalogService catalogService;
    private volatile Set<String> requiredPermissions = Set.of();

    public PermissionMatcher(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            BackendPermissionCatalogService catalogService) {
        this.handlerMapping = handlerMapping;
        this.catalogService = catalogService;
    }

    /**
     * 当前认证是否拥有所要求的具体权限。
     *
     * <h2>这里原来还有第四道关，已经删掉</h2>
     *
     * <p>原来是 {@code catalogService.isEnabledRequirement(requiredPermission)} —— 「这个权限点必须在
     * 权限目录里处于启用状态，glob 授权才算数」。它随 {@code sys_permission.status} 一起删除了， 理由见 {@code
     * V1__bootstrap_schema.sql} 末尾：权限「暂时不生效」是**授权**问题， 落点是角色授权里的勾选，不是挂在权限定义上的开关。
     *
     * <p>删掉它<b>不是放宽了校验</b>，而是去掉了一个**现在必然成立**的条件：既然目录里不存在 「已停用的权限」，那「存在即有效」就是一条恒真的判断。留着它反而有害 ——
     * 它会让人以为权限还有一条启停暗道，也会在下一次改动里变成一个说不清为什么存在的分支。
     *
     * <p>真正的边界仍然在：{@code isConcreteRequirement} 保证接口声明的权限串格式合法， 下面的逐条 glob 匹配保证调用者确实持有它。
     */
    public boolean has(Authentication authentication, String requiredPermission) {
        if (!isConcreteRequirement(requiredPermission)
                || authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities() == null) {
            return false;
        }

        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority == null) {
                continue;
            }
            String grantedPattern = authority.getAuthority();
            if (grantedPattern == null
                    || grantedPattern.startsWith(ROLE_PREFIX)
                    || !isValidGrantedPattern(grantedPattern)) {
                continue;
            }
            if (globMatches(grantedPattern, requiredPermission)) {
                return true;
            }
        }
        return false;
    }

    /** 启动时扫描控制器权限要求，并补齐到权限表供管理端分配和控制。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadPermissionRequirements() {
        Set<String> discovered = new LinkedHashSet<>();
        handlerMapping
                .getHandlerMethods()
                .values()
                .forEach(
                        handler -> {
                            collect(handler.getMethod(), discovered);
                            collect(handler.getBeanType(), discovered);
                        });
        catalogService.synchronize(discovered);
        requiredPermissions = Set.copyOf(discovered);
        log.info("已加载并同步 {} 个后端权限要求", discovered.size());
    }

    public Set<String> requiredPermissions() {
        return requiredPermissions;
    }

    /** 菜单可见权限复用同一 glob 语义；不要求它一定是后端接口权限。 */
    public static boolean matchesGrantedPattern(String pattern, String required) {
        return isValidGrantedPattern(pattern)
                && isValidGrantedPattern(required)
                && globMatches(pattern, required);
    }

    private static void collect(AnnotatedElement element, Set<String> output) {
        PreAuthorize annotation =
                AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        if (annotation == null || !annotation.value().contains(EXPRESSION_PREFIX)) {
            return;
        }
        String expression = annotation.value();
        int firstQuote = expression.indexOf('\'');
        int lastQuote = expression.lastIndexOf('\'');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            throw new IllegalStateException("无法解析权限表达式: " + expression);
        }
        String permission = expression.substring(firstQuote + 1, lastQuote);
        if (!isConcreteRequirement(permission)) {
            throw new IllegalStateException("接口声明了非法权限要求: " + permission);
        }
        output.add(permission);
    }

    private static boolean isConcreteRequirement(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        String[] segments = permission.split(":", -1);
        if (segments.length != REQUIRED_SEGMENT_COUNT) {
            return false;
        }
        for (String segment : segments) {
            if (!isConcreteSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConcreteSegment(String segment) {
        if (segment == null || segment.isEmpty() || segment.indexOf('*') >= 0) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if (!Character.isLetterOrDigit(value) && value != '_' && value != '-' && value != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidGrantedPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String[] segments = pattern.split(":", -1);
        if (segments.length != REQUIRED_SEGMENT_COUNT) {
            return false;
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return false;
            }
            if ("*".equals(segment)) {
                continue;
            }
            for (int index = 0; index < segment.length(); index++) {
                char value = segment.charAt(index);
                if (!Character.isLetterOrDigit(value)
                        && value != '_'
                        && value != '-'
                        && value != '.') {
                    return false;
                }
            }
        }
        return true;
    }

    /** 线性时间 glob 匹配：星号匹配任意长度、任意文本。 */
    private static boolean globMatches(String pattern, String text) {
        int patternIndex = 0;
        int textIndex = 0;
        int lastStar = -1;
        int starTextIndex = -1;
        while (textIndex < text.length()) {
            if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == text.charAt(textIndex)) {
                patternIndex++;
                textIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                lastStar = patternIndex++;
                starTextIndex = textIndex;
            } else if (lastStar >= 0) {
                patternIndex = lastStar + 1;
                textIndex = ++starTextIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
