package com.example.damai.modules.program.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 专用分类调整请求，不接受状态、价格或库存字段。 */
public record EventCategoryAssignRequest(
        @NotNull(message = "分类ID不能为空")
        @Positive(message = "分类ID必须大于0")
        Long categoryId
) {}
