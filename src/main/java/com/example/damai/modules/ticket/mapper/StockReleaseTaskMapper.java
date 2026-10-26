package com.example.damai.modules.ticket.mapper;

import com.example.damai.modules.ticket.entity.StockReleaseTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 库存释放任务的数据访问。
 */
@Mapper
public interface StockReleaseTaskMapper {

    /**
     * 使用订单中已经保存的资料创建任务。
     * 状态、重试次数和时间使用数据库默认值。
     */
    @Insert("""
        INSERT INTO tr_stock_release_task (
            order_id,
            user_id,
            request_id,
            ticket_category_id,
            quantity
        )
        VALUES (
            #{orderId},
            #{userId},
            #{requestId},
            #{ticketCategoryId},
            #{quantity}
        )
        """)
    int insertTask(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("requestId") String requestId,
            @Param("ticketCategoryId") Long ticketCategoryId,
            @Param("quantity") Integer quantity);

    /**
     * 找出尚未完成、且已到执行时间的Redis库存释放任务。
     */
    @Select("""
    SELECT id
    FROM tr_stock_release_task
    WHERE status = 0
      AND next_retry_at <= CURRENT_TIMESTAMP
    ORDER BY next_retry_at ASC, id ASC
    LIMIT #{limit}
    """)
    List<Long> selectPendingIds(@Param("limit") int limit);


    /**
     * 获取刚刚记录的释放任务ID，用于事务提交后立即执行。
     */
    @Select("""
    SELECT id
    FROM tr_stock_release_task
    WHERE order_id = #{orderId}
    """)
    Long selectIdByOrderId(@Param("orderId") Long orderId);

    /**
     * 读取并锁住一条当前允许执行的任务。
     * 任务完成、进入核对或尚未到重试时间时，不再执行。
     */
    @Select("""
    SELECT id, order_id, user_id, request_id,
           ticket_category_id, quantity
    FROM tr_stock_release_task
    WHERE id = #{id}
      AND status = 0
      AND next_retry_at <= CURRENT_TIMESTAMP
    FOR UPDATE
    """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "order_id", javaType = Long.class),
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "request_id", javaType = String.class),
            @Arg(column = "ticket_category_id", javaType = Long.class),
            @Arg(column = "quantity", javaType = Integer.class)
    })
    StockReleaseTask selectPendingForUpdate(@Param("id") Long id);

    /**
     * Redis已确认释放成功，结束任务。
     */
    @Update("""
    UPDATE tr_stock_release_task
    SET status = 1,
        last_error = NULL
    WHERE id = #{id}
      AND status = 0
    """)
    int markDone(@Param("id") Long id);

    /**
     * Redis调用失败或结果未确认，30秒后再尝试。
     */
    @Update("""
    UPDATE tr_stock_release_task
    SET retry_count = retry_count + 1,
        next_retry_at =
            DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND),
        last_error = #{error}
    WHERE id = #{id}
      AND status = 0
    """)
    int retryLater(
            @Param("id") Long id,
            @Param("error") String error);

    /**
     * 数据缺失或冲突时停止自动重试，保留原因供核对。
     */
    @Update("""
    UPDATE tr_stock_release_task
    SET status = 2,
        last_error = #{error}
    WHERE id = #{id}
      AND status = 0
    """)
    int markReview(
            @Param("id") Long id,
            @Param("error") String error);

    /**
     * 等待执行、等待重试、待核对的释放任务，都说明这笔库存尚未完成处理。
     */
    @Select("""
    SELECT EXISTS (
        SELECT 1
        FROM tr_stock_release_task
        WHERE ticket_category_id = #{id}
          AND status <> 1
    )
    """)
    boolean existsUnfinishedByCategoryId(@Param("id") Long id);
}