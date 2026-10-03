package com.loombot.system.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 菜单。
 *
 * <h2>没有 {@code status}，也没有 {@code deleted}</h2>
 *
 * <p>两条都被去掉了，理由见 {@code V1__bootstrap_schema.sql} 末尾那一节：
 *
 * <ul>
 *   <li>{@code status} —— 菜单「停用」和「删除」的结果完全一样，多出来的只是一个必须到处补的 过滤条件。不想让它出现在侧边栏就用 {@code visible =
 *       0}，不想让它存在就删掉。
 *   <li>{@code deleted} —— 删除改成真删。逻辑删除要求每条查询都记得加「未删除」，漏了不报错； 而且它和 {@code uk_menu_route_name}
 *       打架，删掉的行还占着路由名。
 * </ul>
 */
@TableName("sys_menu")
@Getter
@Setter
public class Menu {

    @TableId private Long id;

    private Long parentId;
    private String type;
    private String name;
    private String routeName;
    private String path;
    private String componentKey;
    private String iconKey;
    private String redirect;
    private Integer sort;
    private Integer visible;
    private Integer keepAlive;
    private String remark;

    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;
}
