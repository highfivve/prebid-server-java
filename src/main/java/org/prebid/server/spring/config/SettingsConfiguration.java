package org.prebid.server.spring.config;

import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.ObjectUtils;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.floors.PriceFloorsConfigResolver;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.json.JsonMerger;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.settings.ApplicationSettings;
import org.prebid.server.settings.CachingApplicationSettings;
import org.prebid.server.settings.CompositeApplicationSettings;
import org.prebid.server.settings.EnrichingApplicationSettings;
import org.prebid.server.settings.FileApplicationSettings;
import org.prebid.server.settings.HttpApplicationSettings;
import org.prebid.server.settings.JdbcApplicationSettings;
import org.prebid.server.settings.S3ApplicationSettings;
import org.prebid.server.settings.SettingsCache;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.service.HttpPeriodicRefreshService;
import org.prebid.server.settings.service.JdbcPeriodicRefreshService;
import org.prebid.server.settings.service.S3PeriodicRefreshService;
import org.prebid.server.spring.config.database.DatabaseConfiguration;
import org.prebid.server.vertx.http.HttpClient;
import org.prebid.server.vertx.jdbc.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@UtilityClass
public class SettingsConfiguration {

    @Configuration
    @ConditionalOnProperty(prefix = "settings.filesystem",
            name = {"settings-filename", "stored-requests-dir", "stored-imps-dir"})
    static class FileSettingsConfiguration {

        @Bean
        FileApplicationSettings fileApplicationSettings(
                @Value("${settings.filesystem.settings-filename}") String settingsFileName,
                @Value("${settings.filesystem.stored-requests-dir}") String storedRequestsDir,
                @Value("${settings.filesystem.stored-imps-dir}") String storedImpsDir,
                @Value("${settings.filesystem.stored-responses-dir}") String storedResponsesDir,
                @Value("${settings.filesystem.categories-dir}") String categoriesDir,
                FileSystem fileSystem,
                JacksonMapper jacksonMapper) {

            return new FileApplicationSettings(fileSystem, settingsFileName, storedRequestsDir, storedImpsDir,
                    storedResponsesDir, categoriesDir, jacksonMapper);
        }
    }

    @Configuration
    @ConditionalOnBean(DatabaseConfiguration.class)
    static class DatabaseSettingsConfiguration {

        @Bean
        JdbcApplicationSettings jdbcApplicationSettings(
                @Value("${settings.database.account-query}") String accountQuery,
                @Value("${settings.database.stored-requests-query}") String storedRequestsQuery,
                @Value("${settings.database.amp-stored-requests-query}") String ampStoredRequestsQuery,
                @Value("${settings.database.stored-responses-query}") String storedResponsesQuery,
                JdbcClient jdbcClient,
                JacksonMapper jacksonMapper) {

            return new JdbcApplicationSettings(
                    jdbcClient,
                    jacksonMapper,
                    accountQuery,
                    storedRequestsQuery,
                    ampStoredRequestsQuery,
                    storedResponsesQuery);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "settings.http", name = {"endpoint", "amp-endpoint"})
    static class HttpSettingsConfiguration {

        @Bean
        HttpApplicationSettings httpApplicationSettings(
                HttpClient httpClient,
                JacksonMapper mapper,
                @Value("${settings.http.endpoint}") String endpoint,
                @Value("${settings.http.amp-endpoint}") String ampEndpoint,
                @Value("${settings.http.video-endpoint}") String videoEndpoint,
                @Value("${settings.http.category-endpoint}") String categoryEndpoint) {

            return new HttpApplicationSettings(httpClient, mapper, endpoint, ampEndpoint, videoEndpoint,
                    categoryEndpoint);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "settings.in-memory-cache.http-update",
            name = {"endpoint", "amp-endpoint", "refresh-rate", "timeout"})
    static class HttpPeriodicRefreshServiceConfiguration {

        @Value("${settings.in-memory-cache.http-update.refresh-rate}")
        long refreshPeriod;

        @Value("${settings.in-memory-cache.http-update.timeout}")
        long timeout;

        @Autowired
        Vertx vertx;

        @Autowired
        HttpClient httpClient;

        @Bean
        public HttpPeriodicRefreshService httpPeriodicRefreshService(
                @Value("${settings.in-memory-cache.http-update.endpoint}") String endpoint,
                SettingsCache settingsCache,
                JacksonMapper mapper) {

            return new HttpPeriodicRefreshService(
                    endpoint, refreshPeriod, timeout, settingsCache, vertx, httpClient, mapper);
        }

        @Bean
        public HttpPeriodicRefreshService ampHttpPeriodicRefreshService(
                @Value("${settings.in-memory-cache.http-update.amp-endpoint}") String ampEndpoint,
                SettingsCache ampSettingsCache,
                JacksonMapper mapper) {

            return new HttpPeriodicRefreshService(
                    ampEndpoint, refreshPeriod, timeout, ampSettingsCache, vertx, httpClient, mapper);
        }
    }

    @Configuration
    @ConditionalOnProperty(
            prefix = "settings.in-memory-cache.jdbc-update",
            name = {"refresh-rate", "timeout", "init-query", "update-query", "amp-init-query", "amp-update-query"})
    static class JdbcPeriodicRefreshServiceConfiguration {

        @Value("${settings.in-memory-cache.jdbc-update.refresh-rate}")
        long refreshPeriod;

        @Value("${settings.in-memory-cache.jdbc-update.timeout}")
        long timeout;

        @Autowired
        Vertx vertx;

        @Autowired
        JdbcClient jdbcClient;

        @Autowired
        TimeoutFactory timeoutFactory;

        @Autowired
        Metrics metrics;

        @Autowired
        Clock clock;

        @Bean
        public JdbcPeriodicRefreshService jdbcPeriodicRefreshService(
                @Qualifier("settingsCache") SettingsCache settingsCache,
                @Value("${settings.in-memory-cache.jdbc-update.init-query}") String initQuery,
                @Value("${settings.in-memory-cache.jdbc-update.update-query}") String updateQuery) {

            return new JdbcPeriodicRefreshService(
                    initQuery,
                    updateQuery,
                    refreshPeriod,
                    timeout,
                    MetricName.stored_request,
                    settingsCache,
                    vertx,
                    jdbcClient,
                    timeoutFactory,
                    metrics,
                    clock);
        }

        @Bean
        public JdbcPeriodicRefreshService ampJdbcPeriodicRefreshService(
                @Qualifier("ampSettingsCache") SettingsCache ampSettingsCache,
                @Value("${settings.in-memory-cache.jdbc-update.amp-init-query}") String ampInitQuery,
                @Value("${settings.in-memory-cache.jdbc-update.amp-update-query}") String ampUpdateQuery) {

            return new JdbcPeriodicRefreshService(
                    ampInitQuery,
                    ampUpdateQuery,
                    refreshPeriod,
                    timeout,
                    MetricName.amp_stored_request,
                    ampSettingsCache,
                    vertx,
                    jdbcClient,
                    timeoutFactory,
                    metrics,
                    clock);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "settings.s3", name = {"accounts-dir", "stored-imps-dir", "stored-requests-dir"})
    static class S3SettingsConfiguration {

        @Component
        @ConfigurationProperties(prefix = "settings.s3")
        @ConditionalOnProperty(prefix = "settings.s3", name = {"accessKeyId", "secretAccessKey"})
        @Validated
        @Data
        @NoArgsConstructor
        private static class S3ConfigurationProperties {
            @NotBlank
            private String accessKeyId;
            @NotBlank
            private String secretAccessKey;
            @NotBlank
            private String endpoint;
            @NotBlank
            private String bucket;
            @NotBlank
            private String accountsDir;
            @NotBlank
            private String storedImpsDir;
            @NotBlank
            private String storedRequestsDir;
            @NotBlank
            private String storedResponsesDir;
        }

        @Bean
        S3AsyncClient s3AsyncClient(S3ConfigurationProperties s3ConfigurationProperties) throws URISyntaxException {
            final AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    s3ConfigurationProperties.getAccessKeyId(),
                    s3ConfigurationProperties.getSecretAccessKey());

            return S3AsyncClient
                    .builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(new URI(s3ConfigurationProperties.getEndpoint()))
                    .region(Region.EU_CENTRAL_1)
                    .build();
        }

        @Bean
        S3ApplicationSettings s3ApplicationSettings(
                JacksonMapper mapper,
                S3ConfigurationProperties s3ConfigurationProperties,
                S3AsyncClient s3AsyncClient,
                Vertx vertx) {

            return new S3ApplicationSettings(
                    s3AsyncClient,
                    s3ConfigurationProperties.getBucket(),
                    s3ConfigurationProperties.getAccountsDir(),
                    s3ConfigurationProperties.getStoredImpsDir(),
                    s3ConfigurationProperties.getStoredRequestsDir(),
                    s3ConfigurationProperties.getStoredResponsesDir(),
                    mapper,
                    vertx);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "settings.in-memory-cache.s3-update",
            name = {"refresh-rate", "timeout"})
    static class S3PeriodicRefreshServiceConfiguration {

        @Value("${settings.in-memory-cache.s3-update.refresh-rate}")
        long refreshPeriod;

        @Value("${settings.in-memory-cache.s3-update.timeout}")
        long timeout;

        @Autowired
        Vertx vertx;

        @Autowired
        HttpClient httpClient;
        @Autowired
        Metrics metrics;
        @Autowired
        Clock clock;

        @Bean
        public S3PeriodicRefreshService s3PeriodicRefreshService(
                S3AsyncClient s3AsyncClient,
                S3SettingsConfiguration.S3ConfigurationProperties s3ConfigurationProperties,
                SettingsCache settingsCache,
                JacksonMapper mapper) {
            return new S3PeriodicRefreshService(
                    s3AsyncClient,
                    s3ConfigurationProperties.getBucket(),
                    s3ConfigurationProperties.getStoredRequestsDir(),
                    s3ConfigurationProperties.getStoredImpsDir(),
                    refreshPeriod,
                    timeout,
                    MetricName.stored_request,
                    settingsCache,
                    vertx,
                    metrics,
                    clock);
        }
    }

    /**
     * This configuration defines a collection of application settings fetchers and its ordering.
     */
    @Configuration
    static class CompositeSettingsConfiguration {

        @Bean
        CompositeApplicationSettings compositeApplicationSettings(
                @Autowired(required = false) FileApplicationSettings fileApplicationSettings,
                @Autowired(required = false) JdbcApplicationSettings jdbcApplicationSettings,
                @Autowired(required = false) HttpApplicationSettings httpApplicationSettings,
                @Autowired(required = false) S3ApplicationSettings s3ApplicationSettings) {

            final List<ApplicationSettings> applicationSettingsList =
                    Stream.of(s3ApplicationSettings,
                                    fileApplicationSettings,
                                    jdbcApplicationSettings,
                                    httpApplicationSettings)
                            .filter(Objects::nonNull)
                            .toList();

            return new CompositeApplicationSettings(applicationSettingsList);
        }
    }

    @Configuration
    static class EnrichingSettingsConfiguration {

        @Bean
        EnrichingApplicationSettings enrichingApplicationSettings(
                @Value("${settings.enforce-valid-account}") boolean enforceValidAccount,
                @Value("${logging.sampling-rate:0.01}") double logSamplingRate,
                Account defaultAccount,
                CompositeApplicationSettings compositeApplicationSettings,
                PriceFloorsConfigResolver priceFloorsConfigResolver,
                JsonMerger jsonMerger) {

            return new EnrichingApplicationSettings(
                    enforceValidAccount,
                    logSamplingRate,
                    defaultAccount,
                    compositeApplicationSettings,
                    priceFloorsConfigResolver,
                    jsonMerger);
        }
    }

    @Configuration
    static class CachingSettingsConfiguration {

        @Bean
        @ConditionalOnProperty(prefix = "settings.in-memory-cache", name = {"ttl-seconds", "cache-size"})
        CachingApplicationSettings cachingApplicationSettings(
                EnrichingApplicationSettings enrichingApplicationSettings,
                ApplicationSettingsCacheProperties cacheProperties,
                @Qualifier("settingsCache") SettingsCache cache,
                @Qualifier("ampSettingsCache") SettingsCache ampCache,
                @Qualifier("videoSettingCache") SettingsCache videoCache,
                Metrics metrics) {

            return new CachingApplicationSettings(
                    enrichingApplicationSettings,
                    cache,
                    ampCache,
                    videoCache,
                    metrics,
                    cacheProperties.getTtlSeconds(),
                    cacheProperties.getCacheSize());
        }
    }

    @Configuration
    static class ApplicationSettingsConfiguration {

        @Bean
        ApplicationSettings applicationSettings(
                @Autowired(required = false) CachingApplicationSettings cachingApplicationSettings,
                EnrichingApplicationSettings enrichingApplicationSettings) {
            return ObjectUtils.defaultIfNull(cachingApplicationSettings, enrichingApplicationSettings);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "settings.in-memory-cache", name = {"ttl-seconds", "cache-size"})
    static class CacheConfiguration {

        @Bean
        @Qualifier("settingsCache")
        SettingsCache settingsCache(ApplicationSettingsCacheProperties cacheProperties) {
            return new SettingsCache(cacheProperties.getTtlSeconds(), cacheProperties.getCacheSize());
        }

        @Bean
        @Qualifier("ampSettingsCache")
        SettingsCache ampSettingsCache(ApplicationSettingsCacheProperties cacheProperties) {
            return new SettingsCache(cacheProperties.getTtlSeconds(), cacheProperties.getCacheSize());
        }

        @Bean
        @Qualifier("videoSettingCache")
        SettingsCache videoSettingCache(ApplicationSettingsCacheProperties cacheProperties) {
            return new SettingsCache(cacheProperties.getTtlSeconds(), cacheProperties.getCacheSize());
        }
    }

    @Component
    @ConfigurationProperties(prefix = "settings.in-memory-cache")
    @ConditionalOnProperty(prefix = "settings.in-memory-cache", name = {"ttl-seconds", "cache-size"})
    @Validated
    @Data
    @NoArgsConstructor
    private static class ApplicationSettingsCacheProperties {

        @NotNull
        @Min(1)
        private Integer ttlSeconds;
        @NotNull
        @Min(1)
        private Integer cacheSize;
    }
}
