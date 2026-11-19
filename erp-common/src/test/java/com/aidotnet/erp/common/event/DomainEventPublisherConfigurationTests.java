package com.aidotnet.erp.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.util.ClassUtils;

class DomainEventPublisherConfigurationTests {
    @Test
    void normalApplicationRegistersTheKafkaPublisherWithoutATestProfile() {
        runner().run(context -> assertThat(context).hasSingleBean(DomainEventPublisher.class)
                .getBean(DomainEventPublisher.class).isInstanceOf(KafkaDomainEventPublisher.class));
    }

    @Test
    void explicitlyProvidedPublisherTakesPrecedenceOverTheDefault() {
        DomainEventPublisher override = event -> {};
        runner().withBean(DomainEventPublisher.class, () -> override)
                .run(context -> assertThat(context).hasSingleBean(DomainEventPublisher.class)
                        .getBean(DomainEventPublisher.class).isSameAs(override));
    }

    @Test
    void testProfileKeepsTheInMemoryPublisher() {
        runner().withInitializer(c -> c.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).hasSingleBean(DomainEventPublisher.class)
                        .getBean(DomainEventPublisher.class).isInstanceOf(InMemoryDomainEventPublisher.class));
    }

    private ApplicationContextRunner runner() {
        var configurations = new ArrayList<Class<?>>();
        ClassLoader loader = getClass().getClassLoader();
        for (String candidate : ImportCandidates.load(AutoConfiguration.class, loader)) {
            if (candidate.startsWith("com.aidotnet.erp.common.event.")) configurations.add(ClassUtils.resolveClassName(candidate, loader));
        }
        return new ApplicationContextRunner().withUserConfiguration(PublisherScan.class)
                .withConfiguration(AutoConfigurations.of(configurations.toArray(Class<?>[]::new)))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StreamBridge.class, () -> mock(StreamBridge.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = KafkaDomainEventPublisher.class, excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {OutboxPublisher.class, DomainEventConsumers.class}),
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = AutoConfiguration.class)
    })
    static class PublisherScan {}
}
