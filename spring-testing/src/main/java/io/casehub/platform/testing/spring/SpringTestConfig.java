package io.casehub.platform.testing.spring;

import io.casehub.platform.api.identity.CurrentPrincipal;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot {@link TestConfiguration} providing platform test fixtures.
 *
 * <p>Import in test classes via {@code @Import(SpringTestConfig.class)} to get
 * a mutable {@link CurrentPrincipal} and other platform SPI test doubles.
 */
@TestConfiguration
public class SpringTestConfig {

    @Bean
    public SpringFixedCurrentPrincipal currentPrincipal() {
        return new SpringFixedCurrentPrincipal();
    }
}
