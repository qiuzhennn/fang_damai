package com.example.damai.modules.ticket.redis;

/**
 * Redis预扣的处理结果，不等同于订单状态。
 */
public enum StockReservationResult {

    // 本次调用刚刚完成预扣，对应Lua返回0
    RESERVED_NOW,

    // 同一请求之前已经预扣，对应Lua返回5
    RESERVED_BEFORE,

    // 请求记录显示订单已创建，对应Lua返回7
    ORDER_CREATED,

    // 请求已经完成库存补偿，对应Lua返回8
    COMPENSATED
}