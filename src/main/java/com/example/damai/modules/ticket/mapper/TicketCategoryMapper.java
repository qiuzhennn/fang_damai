package com.example.damai.modules.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.damai.modules.ticket.entity.TicketCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/**
 * 票档数据访问接口。
 */
@Mapper
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {

    /**
     * 将指定数量的可售库存转为待支付锁定库存。
     *  AND available_stock >= #{quantity}库存判断和扣减在同一条 SQL 中完成
     * @return 更新行数；1 表示成功，0 表示票档不存在或库存不足
     */
    @Update("""
        UPDATE tr_ticket_category
        SET available_stock = available_stock - #{quantity},
            locked_stock = locked_stock + #{quantity}
        WHERE id = #{id}
          AND available_stock >= #{quantity} 
          AND #{quantity} > 0
        """)
    int lockStock(
            @Param("id") Long id,
            @Param("quantity") int quantity);

    /**
     * 将锁定库存释放回可售库存。
     */
    @Update("""
        UPDATE tr_ticket_category
        SET available_stock = available_stock + #{quantity},
            locked_stock = locked_stock - #{quantity}
        WHERE id = #{id}
          AND locked_stock >= #{quantity}
          AND #{quantity} > 0
        """)
    int releaseStock(
            @Param("id") Long id,
            @Param("quantity") int quantity);


    /**
     * 支付成功后，将锁定库存转为已售库存。
     *
     * @return 1 表示转换成功；0 表示票档不存在或锁定库存不足
     */
    @Update("""
        UPDATE tr_ticket_category
        SET locked_stock = locked_stock - #{quantity},
            sold_stock = sold_stock + #{quantity}
        WHERE id = #{id}
          AND locked_stock >= #{quantity}
          AND #{quantity} > 0
        """)
    int confirmSold(
            @Param("id") Long id,
            @Param("quantity") int quantity);

    @Select("""
    SELECT *
    FROM tr_ticket_category
    WHERE id = #{id}
    FOR UPDATE
    """)
    TicketCategory selectByIdForUpdate(@Param("id") Long id);
}