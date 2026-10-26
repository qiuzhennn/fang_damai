package com.example.damai.modules.program.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.entity.EventCategory;
import com.example.damai.modules.program.mapper.EventCategoryMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 分类读取和有效性检查，第一版不开放分类增删改接口。 */
@Service
public class EventCategoryService {
    private final EventCategoryMapper mapper;

    public EventCategoryService(EventCategoryMapper mapper) {
        this.mapper = mapper;
    }

    public List<EventCategory> list(boolean enabledOnly) {
        LambdaQueryWrapper<EventCategory> query = new LambdaQueryWrapper<>();
        if (enabledOnly) query.eq(EventCategory::getStatus, 1);
        query.orderByAsc(EventCategory::getSortOrder).orderByAsc(EventCategory::getId);
        return mapper.selectList(query);
    }

    public void requireEnabled(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException("INVALID_PARAM", "分类ID必须大于0");
        }
        EventCategory category = mapper.selectById(id);
        if (category == null) {
            throw new BusinessException("EVENT_CATEGORY_NOT_FOUND", "演出分类不存在");
        }
        if (!Integer.valueOf(1).equals(category.getStatus())) {
            throw new BusinessException("EVENT_CATEGORY_DISABLED", "演出分类已停用");
        }
    }
}
