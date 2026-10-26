package com.example.damai.modules.order.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.common.response.PageResponse;
import com.example.damai.modules.order.entity.TicketOrder;
import com.example.damai.modules.order.mapper.TicketOrderMapper;
import org.springframework.stereotype.Service;

/**
 * 管理员订单查询。
 */
@Service
public class AdminOrderService {

    private final TicketOrderMapper ticketOrderMapper;

    public AdminOrderService(TicketOrderMapper ticketOrderMapper) {
        this.ticketOrderMapper = ticketOrderMapper;
    }

    /**
     * 分页查询全部订单，支持按用户和状态筛选。
     */
    public PageResponse<TicketOrder> list(Long userId, Integer status, long page, long size) {

        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(
                    "INVALID_PAGE_PARAM",
                    "页码必须大于等于1，每页条数必须在1到100之间");
        }

        if (userId != null && userId <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "用户ID必须大于0");
        }

        if (status != null && (status < 0 || status > 3)) {
            throw new BusinessException(
                    "INVALID_PARAM", "订单状态只能为0、1、2或3");
        }

        LambdaQueryWrapper<TicketOrder> query = new LambdaQueryWrapper<>();

        // 管理员可以指定用户；不传则不限制用户
        if (userId != null) {
            query.eq(TicketOrder::getUserId, userId);
        }

        if (status != null) {
            query.eq(TicketOrder::getStatus, status);
        }

        query.orderByDesc(TicketOrder::getId);

        Page<TicketOrder> result = ticketOrderMapper.selectPage(
                new Page<>(page, size), query);

        return new PageResponse<>(
                result.getRecords(),
                result.getTotal(),
                result.getCurrent(),
                result.getSize(),
                result.getPages()
        );
    }

    /**
     * 查询指定订单，不限制所属用户。
     * 调用入口必须受 ADMIN 权限保护。
     */
    public TicketOrder getById(Long id) {
        if (id <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "订单ID必须大于0");
        }
        TicketOrder order = ticketOrderMapper.selectById(id);

        if (order == null) {
            throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        }

        return order;
    }
}
