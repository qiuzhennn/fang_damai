package com.example.damai.modules.order.ratelimit;

import com.example.damai.common.config.RabbitMqConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 读取正常建单队列中尚未确认处理完成的消息数量。
 *
 * 这个数量包括等待投递的消息，以及已投递但尚未确认的消息，
 * 供后续受理保护判断建单链路是否仍能承接新购买。
 */
@Component
public class RabbitQueueBacklogReader {

    private final RestClient client;
    private final String virtualHost;

    public RabbitQueueBacklogReader(
            @Value("${app.rabbitmq.management.base-url}")
            String managementBaseUrl,
            @Value("${spring.rabbitmq.username}")
            String username,
            @Value("${spring.rabbitmq.password}")
            String password,
            @Value("${spring.rabbitmq.virtual-host:/}")
            String virtualHost) {

        this.virtualHost = virtualHost;

        // 管理接口异常时应及时结束查询，
        // 让刷新组件能够识别“当前无法确认积压量”。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(Duration.ofSeconds(1));

        this.client = RestClient.builder()
                .baseUrl(managementBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers ->
                        headers.setBasicAuth(username, password))
                .build();
    }

    /**
     * 查询正常建单队列中尚未确认处理完成的消息数量。
     *
     * 查询失败或计数缺失时抛出异常，
     * 不能把“无法确认积压量”当成“没有积压”。
     */
    public long readPendingCount() {
        QueueStatus status = client.get()
                .uri(
                        "/api/queues/{vhost}/{queue}",
                        virtualHost,
                        RabbitMqConfig.ORDER_CREATE_QUEUE
                )
                .retrieve()
                .body(QueueStatus.class);

        if (status == null
                || status.messages() == null
                || status.messages() < 0) {
            throw new IllegalStateException(
                    "RabbitMQ建单队列积压数量缺失或不合法"
            );
        }

        return status.messages();
    }

    /**
     * messages 已包含待投递数量和已投递未确认数量。
     * 使用 Long，使接口缺少计数时能够识别异常，
     * 防止被误认为积压量为0。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueueStatus(Long messages) {
    }
}