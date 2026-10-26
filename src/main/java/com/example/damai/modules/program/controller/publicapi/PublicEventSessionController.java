package com.example.damai.modules.program.controller.publicapi;

import com.example.damai.common.response.ApiResponse;
import com.example.damai.modules.program.entity.EventSession;
import com.example.damai.modules.program.service.EventSessionService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 公开浏览接口；仅提供原有查询，不包含管理写操作。 */
@RestController
@RequestMapping("/public/event-sessions")
public class PublicEventSessionController {
    private final EventSessionService eventSessionService;

    public PublicEventSessionController(EventSessionService eventSessionService) {
        this.eventSessionService = eventSessionService;
    }

    @GetMapping
    public ApiResponse<List<EventSession>> listByEventId(
            @RequestParam("eventId") Long eventId) {



        return ApiResponse.success(
                eventSessionService.listByEventId(eventId)
        );
    }
}
