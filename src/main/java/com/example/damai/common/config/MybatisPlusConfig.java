package com.example.damai.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 */
@Configuration
class MybatisPlusConfig {

    /**
     * 注册拦截器，在查询时处理分页。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 指定使用 MySQL 分页语法
        PaginationInnerInterceptor pagination =
                new PaginationInnerInterceptor(DbType.MYSQL);

        // 限制单页最多返回 100 条，避免请求一次读取过多数据
        pagination.setMaxLimit(100L);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}