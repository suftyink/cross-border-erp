package com.aidotnet.erp.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;

/** 在普通组件注册完成后提供默认发布器，避免组件上的缺省条件把自身排除。 */
@AutoConfiguration
public class DomainEventPublisherAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DomainEventPublisher.class)
    public DomainEventPublisher kafkaDomainEventPublisher(StreamBridge streamBridge, ObjectMapper objectMapper) {
        return new KafkaDomainEventPublisher(streamBridge, objectMapper);
    }
}
