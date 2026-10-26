package com.example.damai.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.order.entity.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 购票订单数据访问接口。
 */
@Mapper
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    /**
     * 取消当前用户的待支付订单。
     *
     * @return 1 表示取消成功；0 表示订单不存在、不属于该用户或状态不允许
     */
    @Update("""
        UPDATE tr_order
        SET status = 2
        WHERE id = #{id}
          AND user_id = #{userId}
          AND status = 0
        """)
    int cancelPending(
            @Param("id") Long id,
            @Param("userId") Long userId);

    /**
     * 查询一批过期待支付订单的 ID。
     *
     * @param now 本轮扫描的时间
     * @param limit 本次最多查询的订单数
     */
    @Select("""
        SELECT id
        FROM tr_order
        WHERE status = 0
          AND expire_time <= #{now}
        ORDER BY expire_time ASC, id ASC
        LIMIT #{limit}
        """)
    List<Long> selectExpiredIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    /**
     * 将过期待支付订单关闭为已超时。
     *
     * @return 1 表示关闭成功；0 表示条件已不满足
     */
    @Update("""
        UPDATE tr_order
        SET status = 3
        WHERE id = #{id}
          AND status = 0
          AND expire_time <= #{now}
        """)
    int closeExpired(
            @Param("id") Long id,
            @Param("now") LocalDateTime now);

    /**
     * 将当前用户尚未过期的待支付订单改为已支付。
     *
     * @return 1 表示更新成功；0 表示订单不满足支付条件
     */
    @Update("""
        UPDATE tr_order
        SET status = 1,
            paid_at = #{now}
        WHERE id = #{id}
          AND user_id = #{userId}
          AND status = 0
          AND expire_time > #{now}
        """)
    int payPending(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);

}



