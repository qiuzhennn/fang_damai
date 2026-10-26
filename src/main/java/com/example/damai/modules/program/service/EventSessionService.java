package com.example.damai.modules.program.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.dto.EventSessionCreateRequest;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.program.mapper.EventSessionMapper;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 演出场次业务层。
 */
@Service
public class EventSessionService {

    private final EventMapper eventMapper;
    private final EventSessionMapper eventSessionMapper;
    private final TicketPurchaseCache ticketPurchaseCache;

    // 同时注入演出和场次 Mapper，用于存在性检查及新增场次
    public EventSessionService(
            EventMapper eventMapper,
            EventSessionMapper eventSessionMapper,
            TicketPurchaseCache ticketPurchaseCache) {
        this.eventMapper = eventMapper;
        this.eventSessionMapper = eventSessionMapper;
        this.ticketPurchaseCache = ticketPurchaseCache;
    }

    /**
     * 新增场次，请求字段的非空校验由接口层的 @Valid 执行。
     *
     * @param request 场次信息
     * @return 是否新增成功
     */
    public boolean create(EventSessionCreateRequest request) {
        // 校验所属演出是否存在，允许为待上架演出提前配置场次
        if (eventMapper.selectById(request.getEventId()) == null) {
            throw new BusinessException(
                    "EVENT_NOT_FOUND", "所属演出不存在");
        }

        // 结束时间必须严格晚于开始时间
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(
                    "INVALID_SESSION_TIME",
                    "演出结束时间必须晚于开始时间"
            );
        }

        // 开售时间必须严格早于演出开始时间
        if (!request.getSaleStartTime().isBefore(request.getStartTime())) {
            throw new BusinessException(
                    "INVALID_SESSION_TIME",
                    "开售时间必须早于演出开始时间"
            );
        }

        EventSession session = new EventSession();
        session.setEventId(request.getEventId());
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        session.setSaleStartTime(request.getSaleStartTime());

        // 新场次默认待开售，暂不允许直接进入可售状态
        session.setStatus(0);

        // 只执行一次插入，ID 和记录时间由数据库生成
        return eventSessionMapper.insert(session) == 1;
    }

    /**
     * 查询指定演出下的全部场次。
     *
     * @param eventId 演出 ID
     * @return 场次列表，按开始时间升序排列
     */
    public List<EventSession> listByEventId(Long eventId) {
        // 校验 ID 的取值范围
        if (eventId <= 0) {
            throw new BusinessException(
                    "INVALID_PARAM", "演出ID必须大于0");
        }

        // 区分“演出不存在”和“演出存在但尚未配置场次”
        if (eventMapper.selectById(eventId) == null) {
            throw new BusinessException(
                    "EVENT_NOT_FOUND", "演出不存在");
        }

        LambdaQueryWrapper<EventSession> query = new LambdaQueryWrapper<>();

        query.eq(EventSession::getEventId, eventId)
                .orderByAsc(EventSession::getStartTime)
                .orderByAsc(EventSession::getId);

        return eventSessionMapper.selectList(query);
    }

    /**
     * 启用场次销售。
     *
     * @param id 场次 ID
     * @return 是否启用成功
     */
    public boolean enableSale(Long id) {
        LambdaUpdateWrapper<EventSession> update = new LambdaUpdateWrapper<>();

        // 待开售或停售的场次，可以切换为可售状态
        update.eq(EventSession::getId, id)
                .in(EventSession::getStatus, 0, 2)
                .set(EventSession::getStatus, 1);

        // 状态判断和修改在同一条 SQL 中完成
        int rows = eventSessionMapper.update(null, update);

        if (rows == 1) {
            // 状态更新成功后，让后续请求使用新版本的购买资料
            ticketPurchaseCache.invalidateAfterCommit();
            return true;
        }

        // 未更新时，区分场次不存在和状态冲突
        if (eventSessionMapper.selectById(id) == null) {
            throw new BusinessException(
                    "SESSION_NOT_FOUND", "场次不存在");
        }

        throw new BusinessException(
                "SESSION_STATUS_CONFLICT",
                "场次状态已变化或当前不允许启售"
        );
    }

    /**
     * 停止场次销售，只允许可售状态的场次执行。
     *
     * @param id 场次 ID
     * @return 是否停售成功
     */
    public boolean disableSale(Long id) {
        LambdaUpdateWrapper<EventSession> update =
                new LambdaUpdateWrapper<>();

        // 只允许从“可售”变为“停售”
        update.eq(EventSession::getId, id)
                .eq(EventSession::getStatus, 1)
                .set(EventSession::getStatus, 2);

        int rows = eventSessionMapper.update(null, update);

        if (rows == 1) {
            // 状态更新成功后，让后续请求使用新版本的购买资料
            ticketPurchaseCache.invalidateAfterCommit();
            return true;
        }

        // 更新未生效时，区分场次不存在和状态冲突
        if (eventSessionMapper.selectById(id) == null) {
            throw new BusinessException(
                    "SESSION_NOT_FOUND", "场次不存在");
        }

        throw new BusinessException(
                "SESSION_STATUS_CONFLICT",
                "场次状态已变化或当前不允许停售"
        );
    }
}