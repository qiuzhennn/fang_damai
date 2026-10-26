package com.example.damai.modules.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.dto.TicketCategoryCreateRequest;
import com.example.damai.modules.ticket.entity.TicketCategory;
import com.example.damai.modules.ticket.mapper.TicketCategoryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 票档业务层。
 */
@Service
public class TicketCategoryService {

    private final EventSessionMapper eventSessionMapper;
    private final TicketCategoryMapper ticketCategoryMapper;

    public TicketCategoryService(
            EventSessionMapper eventSessionMapper,
            TicketCategoryMapper ticketCategoryMapper) {
        this.eventSessionMapper = eventSessionMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
    }

    /**
     * 新增票档，请求字段的校验由接口层的 @Valid 执行。
     *
     * @param request 票档信息
     * @return 是否新增成功
     */
    public boolean create(TicketCategoryCreateRequest request) {
        // 必须关联到一个实际存在的场次
        if (eventSessionMapper.selectById(request.getSessionId()) == null) {
            throw new BusinessException("SESSION_NOT_FOUND", "所属场次不存在");
        }

        TicketCategory category = new TicketCategory();
        category.setSessionId(request.getSessionId());
        category.setName(request.getName().trim());
        category.setPrice(request.getPrice());

        // 初始库存全部可售，尚无订单锁定或支付
        category.setTotalStock(request.getTotalStock());
        category.setAvailableStock(request.getTotalStock());
        category.setLockedStock(0);
        category.setSoldStock(0);

        try {
            // 只执行一次插入
            return ticketCategoryMapper.insert(category) == 1;
        } catch (DuplicateKeyException exception) {
            // 当前新增流程不设置主键，预期的唯一键冲突是同场次票档重名
            throw new BusinessException(
                    "TICKET_CATEGORY_DUPLICATE",
                    "该场次已存在同名票档"
            );
        }
    }

    /**
     * 查询指定场次的票档，按价格从低到高排列。
     *
     * @param sessionId 场次 ID
     * @return 票档列表；尚未配置票档时返回空列表
     */
    public List<TicketCategory> listBySessionId(Long sessionId) {
        // 区分“场次不存在”和“场次存在但没有票档”
        if (eventSessionMapper.selectById(sessionId) == null) {
            throw new BusinessException(
                    "SESSION_NOT_FOUND", "场次不存在");
        }

        LambdaQueryWrapper<TicketCategory> query = new LambdaQueryWrapper<>();

        query.eq(TicketCategory::getSessionId, sessionId)
                .orderByAsc(TicketCategory::getPrice)
                .orderByAsc(TicketCategory::getId);

        return ticketCategoryMapper.selectList(query);
    }
}