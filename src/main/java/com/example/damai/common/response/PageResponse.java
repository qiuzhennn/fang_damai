package com.example.damai.common.response;

import java.util.List;

/**
 * 统一分页响应。
 *
 * @param records 当前页的数据
 * @param total 符合条件的总条数
 * @param page 当前页码
 * @param size 每页条数
 * @param pages 总页数
 */
public record PageResponse<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages
) {
}