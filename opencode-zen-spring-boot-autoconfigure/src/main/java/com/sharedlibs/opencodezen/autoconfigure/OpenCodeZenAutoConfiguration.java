package com.sharedlibs.opencodezen.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configures shared OpenCode Zen request support when the starter is present. */
@AutoConfiguration
@EnableConfigurationProperties(OpenCodeZenProperties.class)
public class OpenCodeZenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenCodeZenRequestFactory openCodeZenRequestFactory(OpenCodeZenProperties properties) {
        // relay-url defaults to auto: an already-running local Bun relay wins,
        // then the bundled relay script started with the local Bun, then the
        // direct egress (which the free tier currently refuses).
        return new OpenCodeZenRequestFactory(properties.getUserAgent(),
                properties.isSessionAffinity(),
                BunRelay.resolve(properties.getRelayUrl()));
    }
}
