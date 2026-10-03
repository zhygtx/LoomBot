package com.loombot.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统配置。
 *
 * <h2>一条配置就是一个键 + 一个值</h2>
 *
 * <p>{@link #configValue} 是 **JSON 文本**，值是它自己的开关：纯开关类配置的值就是 {@code true} / {@code false}，数值类就是
 * {@code 30}，字符串类就是 {@code "abc"}，复杂结构就是 整个对象或数组。所以这里**没有** {@code valueType}（JSON 自己带类型），也**没有**
 * {@code status}（值已经表达了「开还是关」，再来一列只会和值互相矛盾：值 true 但 status 0， 界面上就成了「已开启 + 已停用」两个开关说同一件事）。
 *
 * <p>剩下的 {@code configGroup} / {@code name} / {@code description} 是管理界面的元数据， 不参与判断；{@code builtin}
 * 只用来在页面上标一个「内置」标签。
 */
@TableName("sys_config")
@Getter
@Setter
public class SystemConfig {

    @TableId private Long id;
    private String configKey;
    private String configValue;
    private String configGroup;
    private String name;
    private String description;
    private Integer builtin;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
