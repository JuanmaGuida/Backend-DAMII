package com.reclamos.backend.service;

import com.reclamos.backend.config.PriorityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class PriorityPropertiesBindingTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Binding.class);
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(PriorityProperties.class)
    static class Binding { }

    @ParameterizedTest @ValueSource(strings={"ticket.priority.w-risk=.7", "ticket.priority.w-age=-.1",
            "ticket.priority.scan-delay=0", "ticket.priority.batch-size=0", "ticket.priority.medium-threshold=.5",
            "ticket.priority.affected-bands[0].minimum-count=1", "ticket.priority.affected-bands[0].score=2"})
    void invalidOverridesFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test void bindsValidatedOverridesAndNestedBands() {
        runner.withPropertyValues("ticket.priority.w-risk=.5","ticket.priority.w-affected=.3","ticket.priority.w-age=.2",
                "ticket.priority.scan-delay=15000", "ticket.priority.batch-size=7",
                "ticket.priority.affected-bands[0].minimum-count=0","ticket.priority.affected-bands[0].score=0",
                "ticket.priority.affected-bands[1].minimum-count=10","ticket.priority.affected-bands[1].score=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PriorityProperties p=context.getBean(PriorityProperties.class);
                    assertThat(p.getWRisk()).isEqualByComparingTo(".5");
                    assertThat(p.getBatchSize()).isEqualTo(7);
                    assertThat(p.getScanDelay()).isEqualTo(15000);
                    assertThat(p.getAffectedBands()).hasSize(2);
                });
    }
}
