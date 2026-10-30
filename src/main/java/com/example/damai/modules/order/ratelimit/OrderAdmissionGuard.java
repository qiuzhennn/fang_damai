package com.example.damai.modules.order.ratelimit;

import com.example.damai.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 限制当前Java实例同时处理的新购买请求数。
 *
 * 所有票档共用名额，避免大量不同票档的购买请求
 * 同时进入校验、Redis预扣和任务登记流程。
 */
@Component
public class OrderAdmissionGuard {

    private final Semaphore permits;

    public OrderAdmissionGuard(
            @Value("${app.order.admission.max-concurrent:50}")
            int maxConcurrent) {

        if (maxConcurrent < 1) {
            throw new IllegalArgumentException(
                    "抢票受理并发上限必须大于0"
            );
        }

        this.permits = new Semaphore(maxConcurrent);
    }

    /**
     * 尝试取得一个受理名额。
     * 名额不足时立即拒绝，避免请求排队占用处理线程。
     */
    public void enter() {
        if (!permits.tryAcquire()) {
            throw new BusinessException(
                    "SYSTEM_BUSY",
                    "当前抢票服务繁忙，请稍后重试"
            );
        }
    }

    /**
     * 当前调用已经取得名额，受理流程结束后归还。
     * 必须与成功的enter配对，不能重复归还。
     */
    public void leave() {
        permits.release();
    }
}