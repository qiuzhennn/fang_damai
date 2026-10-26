package com.example.damai.modules.program.service;

import com.example.damai.common.exception.BusinessException;
import com.example.damai.modules.program.entity.EventCategory;
import com.example.damai.modules.program.mapper.EventCategoryMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不依赖数据库，验证新增分类边界。 */
class EventCategoryServiceTest {
    private final EventCategoryMapper mapper = mock(EventCategoryMapper.class);
    private final EventCategoryService service = new EventCategoryService(mapper);

    @Test
    void rejectsInvalidIdsBeforeDatabaseAccess() {
        assertThrows(BusinessException.class, () -> service.requireEnabled(null));
        assertThrows(BusinessException.class, () -> service.requireEnabled(0L));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingCategoryUsesNotFoundCode() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireEnabled(8L));
        assertEquals("EVENT_CATEGORY_NOT_FOUND", error.getCode());
    }

    @Test
    void disabledCategoryCannotBeAssigned() {
        EventCategory category = new EventCategory();
        category.setId(8L);
        category.setStatus(0);
        when(mapper.selectById(8L)).thenReturn(category);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireEnabled(8L));
        assertEquals("EVENT_CATEGORY_DISABLED", error.getCode());
    }

    @Test
    void enabledCategoryCanBeAssigned() {
        EventCategory category = new EventCategory();
        category.setId(8L);
        category.setStatus(1);
        when(mapper.selectById(8L)).thenReturn(category);
        assertDoesNotThrow(() -> service.requireEnabled(8L));
    }
}
