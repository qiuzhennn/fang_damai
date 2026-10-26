
package com.example.damai.modules.order.mapper;

import com.example.damai.modules.order.entity.AsyncOrderGuardSnapshot;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 异步建单并发保护表 Mapper。
 *
 * 建单和补偿通过同一个(user_id, request_id)
 * 唯一键竞争处理资格。
 */
@Mapper
public interface AsyncOrderGuardMapper {

    /**
     * 尝试申请异步建单资格。
     *
     * 返回：
     * 1：插入成功，本事务取得建单资格
     * 0：已存在相同请求的保护记录
     *
     * 必须在MySQL建单事务内部调用。
     */
    @Insert("""
            INSERT IGNORE INTO tr_order_async_guard (
                user_id,
                request_id,
                ticket_category_id,
                quantity,
                outbox_id,
                unit_price,
                event_start_time,
                status
            )
            VALUES (
                #{userId},
                #{requestId},
                #{ticketCategoryId},
                #{quantity},
                #{outboxId},
                #{unitPrice},
                #{eventStartTime},
                0
            )
            """)
    int claimCreate(
            @Param("userId") Long userId,
            @Param("requestId") String requestId,
            @Param("ticketCategoryId") Long ticketCategoryId,
            @Param("quantity") Integer quantity,
            @Param("outboxId") String outboxId,
            @Param("unitPrice") BigDecimal unitPrice,
            @Param("eventStartTime") LocalDateTime eventStartTime
    );

    /**
     * MySQL订单插入成功后，在同一事务中将
     * Guard状态改为已创建。
     *
     * 返回1才表示更新成功。
     */
    @Update("""
            UPDATE tr_order_async_guard
            SET status = 1,
                order_id = #{orderId},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId}
              AND request_id = #{requestId}
              AND outbox_id = #{outboxId}
              AND status = 0
            """)
    int finishCreate(
            @Param("userId") Long userId,
            @Param("requestId") String requestId,
            @Param("outboxId") String outboxId,
            @Param("orderId") Long orderId
    );


    /**
     * 尝试取得异步库存补偿资格。
     *
     * 与 claimCreate 使用同一个联合主键。
     *
     * 返回：
     * 1：本事务取得补偿资格
     * 0：已有Guard记录，当前不能取得新资格
     *
     * 注意：
     * 插入status=2不代表Redis已经补偿成功。
     * 必须在事务提交之后，才能执行Redis补偿。
     */
    @Insert("""
        INSERT IGNORE INTO tr_order_async_guard (
            user_id,
            request_id,
            ticket_category_id,
            quantity,
            outbox_id,
            unit_price,
            event_start_time,
            status
        )
        VALUES (
            #{userId},
            #{requestId},
            #{ticketCategoryId},
            #{quantity},
            #{outboxId},
            #{unitPrice},
            #{eventStartTime},
            2
        )
        """)
    int claimCompensation(
            @Param("userId") Long userId,
            @Param("requestId") String requestId,
            @Param("ticketCategoryId") Long ticketCategoryId,
            @Param("quantity") Integer quantity,
            @Param("outboxId") String outboxId,
            @Param("unitPrice") BigDecimal unitPrice,
            @Param("eventStartTime") LocalDateTime eventStartTime
    );

    /**
     * 查询Guard当前状态。
     *
     * 没有记录时返回null。
     */
    @Select("""
        SELECT status
        FROM tr_order_async_guard
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
        """)
    Integer findStatus(
            @Param("userId") Long userId,
            @Param("requestId") String requestId
    );


    /**
     * 查询Guard中记录的原始Stream任务ID。
     * 用于防止其他消息冒用同一请求的补偿资格。
     */
    @Select("""
        SELECT outbox_id
        FROM tr_order_async_guard
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
        """)
    String findOutboxId(
            @Param("userId") Long userId,
            @Param("requestId") String requestId
    );

    /**
     * Redis库存补偿已经确认后，
     * 将Guard从2(补偿中)更新为3(补偿完成)。
     *
     * 只有匹配原始请求和outboxId时才允许更新。
     */
    @Update("""
        UPDATE tr_order_async_guard
        SET status = 3,
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
          AND outbox_id = #{outboxId}
          AND status = 2
        """)
    int finishCompensation(
            @Param("userId") Long userId,
            @Param("requestId") String requestId,
            @Param("outboxId") String outboxId
    );

    @Select("""
        SELECT
            user_id,
            request_id,
            ticket_category_id,
            quantity,
            outbox_id,
            unit_price,
            event_start_time,
            status,
            order_id
        FROM tr_order_async_guard
        WHERE user_id = #{userId}
          AND request_id = #{requestId}
        """)
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "request_id", javaType = String.class),
            @Arg(column = "ticket_category_id", javaType = Long.class),
            @Arg(column = "quantity", javaType = Integer.class),
            @Arg(column = "outbox_id", javaType = String.class),
            @Arg(column = "unit_price", javaType = BigDecimal.class),
            @Arg(column = "event_start_time", javaType = LocalDateTime.class),
            @Arg(column = "status", javaType = Integer.class),
            @Arg(column = "order_id", javaType = Long.class)
    })
    AsyncOrderGuardSnapshot findGuard(
            @Param("userId") Long userId,
            @Param("requestId") String requestId
    );


}
