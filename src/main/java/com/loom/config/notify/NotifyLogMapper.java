package com.loom.config.notify;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 通知发送日志的写入。
 *
 * <h2>为什么没有实体类，也没有 {@code BaseMapper}</h2>
 *
 * <p>这张表只有「写」和「人工查」两种用法：没有任何代码需要按条件查出若干行来做业务判断。 为它建一个实体 + {@code BaseMapper} 会得到一堆永远不会被调用的方法，而收益只是
 * 「写法统一」。所以在 Mapper 里直接一条 {@code INSERT}，参数用 {@code @Param} 逐个给 —— 列的清单在 SQL 里一眼可见。
 *
 * <p>{@code id} 由调用方用 MyBatis-Plus 的雪花生成器算好传进来（本表与 {@code id-type: assign_id} 保持一致，同样不用
 * AUTO_INCREMENT）。
 *
 * <h2>为什么没有 {@code deleted} / {@code create_by}</h2>
 *
 * <p>它不是业务数据：不会被逻辑删除，也没有「谁改的」语义。按 docs/database.md 的全局约定 照抄审计字段，只会造出几列永远为 NULL 的字段。
 */
@Mapper
public interface NotifyLogMapper {

    @Insert(
            """
            INSERT INTO notify_log (id, biz_type, recipient, subject, status, error)
            VALUES (#{id}, #{bizType}, #{recipient}, #{subject}, #{status}, #{error})
            """)
    int insert(
            @Param("id") Long id,
            @Param("bizType") String bizType,
            @Param("recipient") String recipient,
            @Param("subject") String subject,
            @Param("status") String status,
            @Param("error") String error);
}
