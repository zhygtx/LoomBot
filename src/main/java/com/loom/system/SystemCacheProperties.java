package com.loom.system;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 系统模块读模型缓存参数。 */
@ConfigurationProperties(prefix = "loom.system.cache")
public record SystemCacheProperties(@DefaultValue("30m") Duration navigationTtl) {

    public SystemCacheProperties {
        if (navigationTtl == null || navigationTtl.isZero() || navigationTtl.isNegative()) {
            throw new IllegalStateException("loom.system.cache.navigation-ttl 必须是正的时间长度。");
        }
    }
}
