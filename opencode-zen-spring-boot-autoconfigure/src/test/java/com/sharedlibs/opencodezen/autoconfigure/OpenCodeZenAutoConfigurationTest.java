package com.sharedlibs.opencodezen.autoconfigure;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter assembly tests: the auto-configuration must supply exactly one
 * {@link OpenCodeZenRequestFactory} bean, honour the configured User-Agent,
 * and back off when the application defines its own factory (no duplicate
 * injection). Deterministic, offline — no network.
 */
class OpenCodeZenAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    OpenCodeZenAutoConfiguration.class));

    @Test
    void providesExactlyOneRequestFactoryWithDefaultUserAgent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OpenCodeZenRequestFactory.class);
            assertThat(context).hasSingleBean(OpenCodeZenProperties.class);
            assertThat(context.getBean(OpenCodeZenRequestFactory.class).userAgent())
                    .isEqualTo(OpenCodeZenProtocolPolicy.USER_AGENT);
        });
    }

    @Test
    void honoursCustomUserAgentProperty() {
        runner.withPropertyValues("opencode.zen.user-agent=acme/1.0")
                .run(context -> assertThat(
                        context.getBean(OpenCodeZenRequestFactory.class).userAgent())
                        .isEqualTo("acme/1.0"));
    }

    @Test
    void backsOffWhenApplicationDefinesItsOwnFactory() {
        runner.withBean(OpenCodeZenRequestFactory.class,
                        () -> new OpenCodeZenRequestFactory("application/2.0"))
                .run(context -> {
                    // Still exactly one bean: the auto-configured one backs off.
                    assertThat(context).hasSingleBean(OpenCodeZenRequestFactory.class);
                    assertThat(context.getBean(OpenCodeZenRequestFactory.class).userAgent())
                            .isEqualTo("application/2.0");
                });
    }
}
