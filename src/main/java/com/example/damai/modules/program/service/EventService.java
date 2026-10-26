package com.example.damai.modules.program.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.dto.EventCreateRequest;
import com.example.damai.modules.program.entity.Event;
import com.example.damai.modules.program.mapper.EventMapper;
import com.example.damai.modules.ticket.redis.TicketPurchaseCache;
import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
@Service
public class EventService {

    private final EventMapper eventMapper;
    private final EventCategoryService eventCategoryService;
    private final TicketPurchaseCache ticketPurchaseCache;

    public EventService(EventMapper eventMapper, EventCategoryService eventCategoryService, TicketPurchaseCache ticketPurchaseCache) {
        this.eventMapper = eventMapper;
        this.eventCategoryService = eventCategoryService;
        this.ticketPurchaseCache = ticketPurchaseCache;
    }

    /**
     * 根据演出 ID 查询详情。
     *
     * @param id 演出 ID
     * @return 演出信息；不存在时返回 null
     */
    public Event getById(Long id) {
        Event event = eventMapper.selectById(id);
        return event;
    }

    /**
     * 查询已上架的演出列表。
     *
     * @param city 城市名称，为空时不限制城市
     * @param keyword 演出名称关键词，为空时不限制名称
     * @return 符合条件的演出列表，没有匹配数据时返回空列表
     */
    /**
     * 分页查询已上架的演出。
     *
     * @param city 城市，为空时不限制
     * @param keyword 演出名称关键词，为空时不限制
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页结果，包含演出记录和总条数等信息
     */
    public Page<Event> listOnSale(String city, String keyword, Long categoryId, long page, long size) {

        LambdaQueryWrapper<Event> query = new LambdaQueryWrapper<>();

        // 只查询已上架的演出
        query.eq(Event::getStatus, 1);

        // 分类只是附加筛选条件，不改变原有上架限制
        if (categoryId != null) {
            query.eq(Event::getCategoryId, categoryId);
        }

        // 城市精确筛选
        if (StringUtils.hasText(city)) {
            query.eq(Event::getCity, city.trim());
        }

        // 演出名称模糊搜索
        if (StringUtils.hasText(keyword)) {
            query.like(Event::getTitle, keyword.trim());
        }

        // 按唯一的 ID 排序，保证分页顺序明确
        query.orderByDesc(Event::getId);

        // 指定当前页和每页条数，默认还会查询符合条件的总条数
        Page<Event> pageRequest = new Page<>(page, size);

        return eventMapper.selectPage(pageRequest, query);
    }

    /**
     * 新增演出。
     *
     * @param request 客户端提交的演出信息
     * @return 数据库生成的演出 ID
     */
    public boolean create(EventCreateRequest request) {
        // 即使内部直接调用Service，也不能绕过必选分类规则
        eventCategoryService.requireEnabled(request.getCategoryId());
        Event event = new Event();
        event.setCategoryId(request.getCategoryId());

        // 将请求字段赋给实体，并去掉首尾空白
        event.setTitle(request.getTitle().trim());
        event.setArtist(request.getArtist().trim());
        event.setCity(request.getCity().trim());
        event.setVenue(request.getVenue().trim());

        // 新演出默认待上架
        event.setStatus(0);


        // 插入一条记录时，受影响的行数应为 1
        return eventMapper.insert(event) == 1;
    }

    /**
     * 上架演出。
     *
     * @param id 演出 ID
     * @return 是否上架成功
     */
    public boolean publish(Long id) {

        LambdaUpdateWrapper<Event> update = new LambdaUpdateWrapper<>();

        // 只允许将待上架或已下架的演出设置为上架状态
        update.eq(Event::getId, id)
                .in(Event::getStatus, 0, 2)
                .set(Event::getStatus, 1);

        // 将状态判断和更新放在同一条 SQL 中，避免先查再改的并发问题
        int rows = eventMapper.update(null, update);

        if (rows == 1) {
            // 状态更新成功后，让后续请求使用新版本的购买资料
            ticketPurchaseCache.invalidateAfterCommit();
            return true;
        }

        // 没有更新记录时，再区分演出不存在还是已上架
        Event event = eventMapper.selectById(id);

        if (event == null) {
            throw new BusinessException("EVENT_NOT_FOUND", "演出不存在");
        }

        throw new BusinessException("EVENT_STATUS_CONFLICT", "演出状态已变化或当前不允许上架");
    }
    /**
     * 下架演出，只允许已上架的演出执行此操作。
     *
     * @param id 演出 ID
     * @return 是否下架成功
     */
    public boolean unpublish(Long id) {
        LambdaUpdateWrapper<Event> update = new LambdaUpdateWrapper<>();

        // 将状态校验和更新放在同一条 SQL 中
        update.eq(Event::getId, id)
                .eq(Event::getStatus, 1)
                .set(Event::getStatus, 2);

        int rows = eventMapper.update(null, update);

        if (rows == 1) {
            // 状态更新成功后，让后续请求使用新版本的购买资料
            ticketPurchaseCache.invalidateAfterCommit();
            return true;
        }

        // 更新失败后，区分演出不存在和状态不符合要求
        Event event = eventMapper.selectById(id);

        if (event == null) {
            throw new BusinessException(
                    "EVENT_NOT_FOUND", "演出不存在");
        }

        throw new BusinessException(
                "EVENT_STATUS_CONFLICT",
                "演出状态已变化或当前不允许下架"
        );
    }
    /** 为已有演出设置分类，仅更新category_id，不修改上架状态或库存。 */
    public boolean changeCategory(Long id, Long categoryId) {
        if (id == null || id <= 0) {
            throw new BusinessException("INVALID_PARAM", "演出ID必须大于0");
        }
        eventCategoryService.requireEnabled(categoryId);
        if (eventMapper.selectById(id) == null) {
            throw new BusinessException("EVENT_NOT_FOUND", "演出不存在");
        }
        LambdaUpdateWrapper<Event> update = new LambdaUpdateWrapper<>();
        update.eq(Event::getId, id).set(Event::getCategoryId, categoryId);
        return eventMapper.update(null, update) == 1;
    }
}
