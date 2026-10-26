package com.example.damai.modules.order.service;

public enum CompensationClaimResult {

    CLAIMED,          // 本次成功取得补偿资格
    ALREADY_CLAIMED,  // 之前已经取得补偿资格
    ALREADY_COMPLETED, // 新增：Guard=3，Redis已补偿
    ORDER_CREATED,    // 建单事务已经成功
    NOT_EXPIRED,      // 演出尚未开始，不满足本次补偿条件
    MANUAL_REVIEW     // 状态不一致，需要核查
}