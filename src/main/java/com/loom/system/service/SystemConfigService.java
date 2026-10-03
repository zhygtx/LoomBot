package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.domain.SystemConfig;
import com.loom.system.dto.AuthOptionsResponse;
import com.loom.system.dto.SystemConfigResponse;
import com.loom.system.dto.SystemConfigUpdate;
import com.loom.system.mapper.SystemConfigMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 系统配置。
 *
 * <h2>一条配置就是一个键 + 一个 JSON 值</h2>
 *
 * <p>{@code config_value} 是 JSON 文本，**值是它自己的开关**：布尔配置的值就是 {@code true} / {@code false}，数值配置就是
 * {@code 30}，字符串就是 {@code "abc"}，复杂结构就是整个对象。 没有类型列（JSON 自己带类型），也没有 status 列 —— 值已经表达了开还是关，再来一列只会和值
 * 互相矛盾（值 true 但 status 0，界面上就成了两个开关说同一件事）。
 *
 * <p>数据库那一列是 {@code TEXT} 而不是 MySQL 原生 {@code JSON} 类型：校验权威在应用层，
 * 因为值不合法时我们想把它当成一条**可在配置页上修复的脏数据**显示出来，而不是在写入阶段就被数据库拒绝。
 *
 * <h2>「不允许关闭」是规则，不是数据</h2>
 *
 * <p>某些配置关掉会让系统进入不可恢复的状态。这类约束写在 {@link #MUST_STAY_TRUE} 里，
 * 而不是在表上加一列标志：规则放代码里，改规则要过评审；放数据库里就等于多一个能被随手改掉的开关。
 */
@Service
public class SystemConfigService {

    public static final String AUTH_REGISTER_ENABLED = "auth.register.enabled";
    public static final String AUTH_LOGIN_ENABLED = "auth.login.enabled";
    public static final String AUTH_EMAIL_CODE_ENABLED = "auth.email-code.enabled";
    public static final String AUTH_PASSWORD_RESET_ENABLED = "auth.password-reset.enabled";

    /** 原始执行日志保留天数。 */
    public static final String WORKFLOW_LOG_RETENTION_DAYS = "workflow.log.retention-days";

    /** 小时汇总保留天数，比原始日志长得多。 */
    public static final String WORKFLOW_LOG_SUMMARY_RETENTION_DAYS =
            "workflow.log.summary-retention-days";

    // ---------- 插件库机器人 ----------
    /** 总开关。默认关闭：它只在有人提交插件时才需要。 */
    public static final String PLUGIN_MARKET_ENABLED = "plugin.market.enabled";

    /** 插件库的 git 地址与分支。 */
    public static final String PLUGIN_MARKET_REPO = "plugin.market.repo";

    /** AI 的地址与模型。**密钥不在这里**——它在服务器的 python/secrets/plugin-market.json。 */
    public static final String PLUGIN_MARKET_AI = "plugin.market.ai";

    /** 闸门开关：auto_merge / auto_publish / approve_before_merge / merge_method。 */
    public static final String PLUGIN_MARKET_GATES = "plugin.market.gates";

    /**
     * 值必须保持为 true 的布尔配置：key → 不允许关掉的理由。
     *
     * <h2>为什么这是一个注册表，而不是一堆散落的 if</h2>
     *
     * <p>「这条配置能不能关」不是某一类配置的通用属性，而是**每条配置自己的事**。有些是纯开关， 关了只是少个功能（注册、验证码、找回密码 ——
     * 已经登录的人自己就能改回来）；有些关掉会把 系统弄成不可恢复的状态。
     *
     * <p>规矩定成：核心功能配置往这里加一行，并在理由里写清楚代价。理由会**原样回给前端**， 配置页据此把开关置灰并显示原因 ——
     * 让用户提前看到「这条不能关」，而不是点了保存才收到报错。
     *
     * <p>现有唯一一条是登录开关：关掉之后 {@code AuthService} 拒绝签发任何新令牌，而改配置又必须 带着令牌，于是没有任何人能再登进来（包括站长），只能去数据库改回去。
     */
    private static final Map<String, String> MUST_STAY_TRUE =
            Map.of(AUTH_LOGIN_ENABLED, "「允许登录」不能关闭：那会让所有人（包括站长）再也登不进来，只能直接改数据库恢复");

    /** 值里表示「开关」的键名。见 {@link #switchKeyOf(String)}。 */
    private static final String SWITCH_KEY = "enabled";

    private final SystemConfigMapper mapper;
    private final ObjectMapper jsonMapper;

    public SystemConfigService(SystemConfigMapper mapper, ObjectMapper jsonMapper) {
        this.mapper = mapper;
        this.jsonMapper = jsonMapper;
    }

    public List<SystemConfigResponse> list() {
        return mapper
                .selectList(
                        Wrappers.<SystemConfig>lambdaQuery()
                                .orderByAsc(SystemConfig::getConfigGroup)
                                .orderByAsc(SystemConfig::getConfigKey))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public AuthOptionsResponse authOptions() {
        return new AuthOptionsResponse(
                enabled(AUTH_REGISTER_ENABLED),
                enabled(AUTH_LOGIN_ENABLED),
                enabled(AUTH_EMAIL_CODE_ENABLED),
                enabled(AUTH_PASSWORD_RESET_ENABLED));
    }

    /**
     * 这条配置的开关是否为「开」。
     *
     * <p>值的形状有两种，这里统一成同一件事：
     *
     * <ul>
     *   <li>值是布尔 —— 纯开关配置直接存 {@code true}；
     *   <li>值是对象且带 {@code enabled} 键 —— 开关 + 参数，如 {@code {"enabled": true, "maxUsers": 3}}。
     * </ul>
     *
     * <p>两种都不匹配（数字、字符串、非法 JSON）时返回 false，即**失败即关闭**。这和只有 status 列时的 行为一致（{@code
     * Boolean.parseBoolean(垃圾)} 也是 false），没有偷偷改掉语义。对 {@link #AUTH_LOGIN_ENABLED} 来说「失败即关闭」等于锁死，但它是
     * {@link #MUST_STAY_TRUE} 成员， 通过接口写不进关闭的值 —— 只有手工改库才会走到那一步。
     */
    public boolean enabled(String key) {
        JsonNode node = parse(require(key).getConfigValue());
        if (node == null) {
            return false;
        }
        // 两条路都认：值是布尔（像 auth.register.enabled 那样直接存 true），
        // 或者值是对象且带 enabled 键（像 {"enabled": true, "maxUsers": 3}）。
        // 统一成「开关」这一件事，调用方不用关心这条配置长什么样。
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject()) {
            JsonNode flag = node.get(SWITCH_KEY);
            return flag != null && flag.isBoolean() && flag.booleanValue();
        }
        return false;
    }

    /**
     * 读一个必须为正的整数配置字段（保留天数这类），读不到 / 不是数字 / 不是正数就用 {@code fallback}。
     *
     * <p>和 {@link #enabled(String)} 的「失败即关闭」一个道理：读路径必须容忍脏数据。这里不能用 {@link #require}，
     * 因为那条路在配置项缺失时直接抛异常——而调用方（日志清理）希望缺了就退回配置文件里的默认值， 不该因为一条配置没种上就整个任务失败。
     */
    public int positiveInt(String key, String field, int fallback) {
        Integer value = intField(key, field);
        return value != null && value > 0 ? value : fallback;
    }

    /**
     * 读一个非负整数配置字段，{@code 0} 的含义由调用方解释（日志汇总里表示「永久保留」）。
     *
     * <p>和 {@link #positiveInt} 分开，是因为 {@code 0} 在那边是非法值、在这边是合法值： 合成一个方法就得再加一个布尔开关，读起来反而更绕。
     */
    public int nonNegativeInt(String key, String field, int fallback) {
        Integer value = intField(key, field);
        return value != null && value >= 0 ? value : fallback;
    }

    /**
     * 读一条配置的原始 JSON 文本；配置项不存在时返回 {@code null}。
     *
     * <p>和 {@link #require} 不同：这条路径容忍"配置还没种上"，缺了怎么办由调用方决定。
     */
    public String rawValue(String key) {
        SystemConfig config =
                mapper.selectOne(
                        Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, key));
        return config == null ? null : config.getConfigValue();
    }

    /** 取配置对象里的一个整数字段；配置项缺失、值不是对象、字段缺失或不是数字都返回 null。 */
    private Integer intField(String key, String field) {
        SystemConfig config =
                mapper.selectOne(
                        Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, key));
        if (config == null) {
            return null;
        }
        JsonNode node = parse(config.getConfigValue());
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.intValue() : null;
    }

    @Transactional
    public SystemConfigResponse update(String key, String rawValue) {
        SystemConfig existing = require(key);
        return write(existing, rawValue);
    }

    /**
     * 批量保存：整页改完一次提交。
     *
     * <p>一个事务里逐条写，而不是一条 SQL 更新多行 —— 每条都要做 JSON 校验，出错时抛异常， 整个批次一起回滚。这样不会出现「前三条存进去了、第四条不是合法
     * JSON」的半截状态。
     */
    @Transactional
    public List<SystemConfigResponse> batchUpdate(List<SystemConfigUpdate> updates) {
        Set<String> seen = new HashSet<>();
        for (SystemConfigUpdate update : updates) {
            if (!seen.add(update.key().strip())) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST, "同一个配置项重复提交: " + update.key().strip());
            }
        }
        List<SystemConfigResponse> result = new ArrayList<>(updates.size());
        for (SystemConfigUpdate update : updates) {
            result.add(write(require(update.key().strip()), update.value()));
        }
        return result;
    }

    private SystemConfigResponse write(SystemConfig existing, String rawValue) {
        String normalized = normalize(rawValue);
        assertAllowed(existing.getConfigKey(), normalized);

        SystemConfig patch = new SystemConfig();
        patch.setId(existing.getId());
        patch.setConfigValue(normalized);
        if (mapper.updateById(patch) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_NOT_FOUND);
        }
        return toResponse(require(existing.getConfigKey()));
    }

    private SystemConfigResponse toResponse(SystemConfig config) {
        String reason = MUST_STAY_TRUE.get(config.getConfigKey());
        return new SystemConfigResponse(
                config.getId(),
                config.getConfigKey(),
                config.getConfigValue(),
                kindOf(config.getConfigValue()),
                config.getConfigGroup(),
                config.getName(),
                config.getDescription(),
                Integer.valueOf(1).equals(config.getBuiltin()),
                switchKeyOf(config.getConfigValue()),
                reason != null,
                reason);
    }

    /**
     * 值里那个「开关」的键名，没有就是 null。
     *
     * <p>约定：值是个 JSON 对象、且顶层含 {@code enabled} 键，就认为这条配置有开关。后端只负责 **认出来**，不负责规定它的含义 ——
     * 同一个键在不同配置上可以是「功能开不开」「要不要自动执行」， 由使用方解释。
     *
     * <p>为什么只认这一个固定键名，而不是让每条配置自己声明：声明又要占一列或一份配置，而前端需要的 信息只有「有没有开关」。多一层间接只会多一处能对不上的地方。
     */
    private String switchKeyOf(String rawValue) {
        JsonNode node = parse(rawValue);
        return node != null && node.isObject() && node.has(SWITCH_KEY) ? SWITCH_KEY : null;
    }

    /**
     * 拦住「把必须保持开启的配置关掉」。
     *
     * <p>值的形状有两种：布尔，或者带 {@code enabled} 键的对象。两边都要判 —— 只看一种的话，
     * 换个写法就能绕过。非布尔、非对象的值（数字、字符串）没有真假可言，直接放行： 一条必须开启的整数配置如果用 {@code parseBoolean("30")}
     * 去判，会被拒掉每一次写入。
     */
    private void assertAllowed(String key, String normalizedValue) {
        String reason = MUST_STAY_TRUE.get(key);
        if (reason == null) {
            return;
        }
        JsonNode node = parse(normalizedValue);
        if (node == null) {
            return;
        }
        boolean turnedOff = false;
        if (node.isBoolean()) {
            turnedOff = !node.booleanValue();
        } else if (node.isObject()) {
            JsonNode flag = node.get(SWITCH_KEY);
            turnedOff = flag != null && flag.isBoolean() && !flag.booleanValue();
        }
        if (turnedOff) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, reason);
        }
    }

    public void requireEnabled(String key, ErrorCode errorCode) {
        if (!enabled(key)) {
            throw new BusinessException(errorCode);
        }
    }

    private SystemConfig require(String key) {
        SystemConfig config =
                mapper.selectOne(
                        Wrappers.<SystemConfig>lambdaQuery().eq(SystemConfig::getConfigKey, key));
        if (config == null) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_NOT_FOUND, "系统配置不存在: " + key);
        }
        return config;
    }

    /**
     * 校验并规范化 JSON 值。
     *
     * <p>先解析再重新序列化，而不是原样存：落库的永远是规范化后的 JSON（空白、key 顺序都统一）， 页面上看到的和库里存的不会是两份不同的文本。
     */
    private String normalize(String rawValue) {
        String stripped = rawValue == null ? "" : rawValue.strip();
        if (stripped.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, "配置值不能为空");
        }
        JsonNode node = parse(stripped);
        if (node == null) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, "配置值不是合法的 JSON");
        }
        return node.toString();
    }

    /** 解析失败返回 null，调用方自己决定怎么处理 —— 读路径要能容忍脏数据，不能抛。 */
    private JsonNode parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(rawValue);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 值的种类，给前端挑编辑器用。
     *
     * <p>这是**从值本身算出来的**，不是另存一列声明出来的 —— 所以它不可能和值不一致。 解析不出来时给 {@code
     * INVALID}：配置页把它显示成一条待修复的脏数据，而不是让整个页面 500。
     */
    private String kindOf(String rawValue) {
        JsonNode node = parse(rawValue);
        if (node == null) {
            return "INVALID";
        }
        if (node.isBoolean()) {
            return "BOOLEAN";
        }
        if (node.isNumber()) {
            return "NUMBER";
        }
        if (node.isTextual()) {
            return "STRING";
        }
        if (node.isArray()) {
            return "ARRAY";
        }
        if (node.isObject()) {
            return "OBJECT";
        }
        return "NULL";
    }
}
