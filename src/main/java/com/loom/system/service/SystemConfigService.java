package com.loom.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.loom.common.api.ErrorCode;
import com.loom.common.exception.BusinessException;
import com.loom.system.domain.SystemConfig;
import com.loom.system.dto.AuthOptionsResponse;
import com.loom.system.mapper.SystemConfigMapper;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemConfigService {

    public static final String AUTH_REGISTER_ENABLED = "auth.register.enabled";
    public static final String AUTH_LOGIN_ENABLED = "auth.login.enabled";
    public static final String AUTH_EMAIL_CODE_ENABLED = "auth.email-code.enabled";
    public static final String AUTH_PASSWORD_RESET_ENABLED = "auth.password-reset.enabled";

    private final SystemConfigMapper mapper;

    public SystemConfigService(SystemConfigMapper mapper) {
        this.mapper = mapper;
    }

    public List<SystemConfig> list() {
        return mapper.selectList(
                Wrappers.<SystemConfig>lambdaQuery()
                        .orderByAsc(SystemConfig::getConfigGroup)
                        .orderByAsc(SystemConfig::getConfigKey));
    }

    public AuthOptionsResponse authOptions() {
        return new AuthOptionsResponse(
                enabled(AUTH_REGISTER_ENABLED),
                enabled(AUTH_LOGIN_ENABLED),
                enabled(AUTH_EMAIL_CODE_ENABLED),
                enabled(AUTH_PASSWORD_RESET_ENABLED));
    }

    public boolean enabled(String key) {
        SystemConfig config = require(key);
        return config.getStatus() != null
                && config.getStatus() == 1
                && Boolean.parseBoolean(config.getConfigValue().strip());
    }

    @Transactional
    public SystemConfig update(String key, String rawValue) {
        SystemConfig existing = require(key);
        String normalized = normalize(existing.getValueType(), rawValue);
        SystemConfig patch = new SystemConfig();
        patch.setId(existing.getId());
        patch.setConfigValue(normalized);
        if (mapper.updateById(patch) != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_NOT_FOUND);
        }
        return require(key);
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

    private static String normalize(String type, String value) {
        String stripped = value == null ? "" : value.strip();
        if ("BOOLEAN".equalsIgnoreCase(type)) {
            if (!"true".equalsIgnoreCase(stripped) && !"false".equalsIgnoreCase(stripped)) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, "布尔配置只能是 true 或 false");
            }
            return stripped.toLowerCase(Locale.ROOT);
        }
        if ("INTEGER".equalsIgnoreCase(type)) {
            try {
                return String.valueOf(Long.parseLong(stripped));
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, "整数配置格式不正确");
            }
        }
        if (stripped.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_CONFIG_VALUE_INVALID, "配置值不能为空");
        }
        return stripped;
    }
}
