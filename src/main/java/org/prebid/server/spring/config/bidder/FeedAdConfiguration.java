package org.prebid.server.spring.config.bidder;

import jakarta.validation.constraints.NotBlank;

import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.feedad.FeedAdBidder;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.spring.config.bidder.model.BidderConfigurationProperties;
import org.prebid.server.spring.config.bidder.util.BidderDepsAssembler;
import org.prebid.server.spring.config.bidder.util.UsersyncerCreator;
import org.prebid.server.spring.env.YamlPropertySourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource(value = "classpath:/bidder-config/feedad.yaml", factory = YamlPropertySourceFactory.class)
public class FeedAdConfiguration {

    private static final String BIDDER_NAME = "feedad";

    @Bean("feedadConfigurationProperties")
    @ConfigurationProperties("adapters.feedad")
    BidderConfigurationProperties configurationProperties() {
        return new BidderConfigurationProperties();
    }

    @Bean
    BidderDeps feedadBidderDeps(BidderConfigurationProperties feedadConfigurationProperties,
                                @NotBlank @Value("${external-url}") String externalUrl,
                                JacksonMapper mapper) {

        return BidderDepsAssembler.forBidder(BIDDER_NAME)
                .withConfig(feedadConfigurationProperties)
                .usersyncerCreator(UsersyncerCreator.create(externalUrl))
                .bidderCreator(config -> new FeedAdBidder(config.getEndpoint(), mapper))
                .assemble();
    }
}
