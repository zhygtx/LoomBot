package com.loom.auth.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 用户实体。
 *
 * <h2>为什么用 Lombok（D35 的第二个例外）</h2>
 *
 * <p>D35 立下「全工程只有 {@code WsConnection} 用 Lombok」，判据是「唯一一个纯数据 + 必须可变的类」。这条判据在第二个 MyBatis-Plus
 * 实体出现时就不成立了：实体必须有 无参构造器 + setter 才能被回填，因此用不了 {@code record}。手写的结果是 14 个字段配 28
 * 个机械的读写方法。判据本身没变，只是它现在匹配到了两个类 —— 见 decisions.md D64。
 *
 * <h2>{@code @ToString(exclude = "password")} 是安全要求</h2>
 *
 * <p>与 {@code WsConnection.config} 同理：密码哈希进日志是**永久性**泄漏，日志比数据库 更容易被翻到。区别是密码哈希还能被离线爆破，而它本来只该存在于数据库里。
 */
@TableName("sys_user")
@Getter
@Setter
@ToString(exclude = "password")
public class SysUser {

    /** {@code status} 的正常值。0 = 停用，登录时被拒。 */
    public static final int STATUS_ENABLED = 1;

    /** 当前测试阶段注册用户默认绑定站长角色，便于验证全部管理端与用户端能力。 */
    public static final String DEFAULT_ROLE_CODE = "OWNER";

    @TableId private Long id;

    /** BCrypt 哈希，绝不存明文。{@code toString} 已排除。 */
    private String password;

    /** 邮箱。登录凭据 + 找回密码的收件地址。 */
    private String email;

    private String phone;

    /** 1=正常 0=停用。 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    private String remark;

    @TableLogic private Integer deleted;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    /**
     * 用户是否可登录。
     *
     * <p>方法名刻意不用 {@code isEnabled()} —— 以 {@code is} 开头的无参方法会被 Jackson 当成属性序列化出去（{@code Result}
     * 里踩过同一个坑）。本类目前不会被直接返回给前端， 但把判据写成「会不会被序列化」而不是「现在有没有被序列化」，才不会被下次改动坑掉。
     */
    public boolean enabled() {
        return status != null && status == STATUS_ENABLED;
    }
}
