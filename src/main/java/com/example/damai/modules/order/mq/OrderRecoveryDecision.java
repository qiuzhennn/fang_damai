package com.example.damai.modules.order.mq;

public enum OrderRecoveryDecision {

    // MySQL 已有订单，本次成功修复 Redis 状态
    REDIS_REPAIRED,

    // MySQL 和 Redis 都确认订单已经创建
    ALREADY_CREATED,

    // 请求仍处于预扣状态，后续需要重试或进一步对账
    RETRY_REQUIRED,

    // 数据不一致，不能自动处理
    MANUAL_REVIEW
}