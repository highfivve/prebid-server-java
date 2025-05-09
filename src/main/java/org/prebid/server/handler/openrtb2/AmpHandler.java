package org.prebid.server.handler.openrtb2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.analytics.model.AmpEvent;
import org.prebid.server.analytics.reporter.AnalyticsReporterDelegator;
import org.prebid.server.auction.AmpResponsePostProcessor;
import org.prebid.server.auction.AnalyticsTagsEnricher;
import org.prebid.server.auction.ExchangeService;
import org.prebid.server.auction.HookDebugInfoEnricher;
import org.prebid.server.auction.HooksMetricsService;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.Tuple2;
import org.prebid.server.auction.requestfactory.AmpRequestFactory;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.cookie.UidsCookie;
import org.prebid.server.exception.BlocklistedAccountException;
import org.prebid.server.exception.BlocklistedAppException;
import org.prebid.server.exception.InvalidAccountConfigException;
import org.prebid.server.exception.InvalidRequestException;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.exception.UnauthorizedAccountException;
import org.prebid.server.hooks.execution.HookStageExecutor;
import org.prebid.server.hooks.execution.model.HookStageExecutionResult;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.log.ConditionalLogger;
import org.prebid.server.log.HttpInteractionLogger;
import org.prebid.server.log.Logger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.metric.MetricName;
import org.prebid.server.metric.Metrics;
import org.prebid.server.model.Endpoint;
import org.prebid.server.model.HttpRequestContext;
import org.prebid.server.privacy.gdpr.model.TcfContext;
import org.prebid.server.privacy.model.PrivacyContext;
import org.prebid.server.proto.openrtb.ext.response.ExtBidPrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponsePrebid;
import org.prebid.server.proto.openrtb.ext.response.ExtBidderError;
import org.prebid.server.proto.openrtb.ext.response.ExtModules;
import org.prebid.server.proto.openrtb.ext.response.ExtResponseDebug;
import org.prebid.server.proto.response.AmpResponse;
import org.prebid.server.proto.response.ExtAmpVideoPrebid;
import org.prebid.server.proto.response.ExtAmpVideoResponse;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.version.PrebidVersionProvider;
import org.prebid.server.vertx.verticles.server.HttpEndpoint;
import org.prebid.server.vertx.verticles.server.application.ApplicationResource;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class AmpHandler implements ApplicationResource {

    private static final Logger logger = LoggerFactory.getLogger(AmpHandler.class);
    private static final ConditionalLogger conditionalLogger = new ConditionalLogger(logger);

    public static final String PREBID_EXT = "prebid";
    private static final MetricName REQUEST_TYPE_METRIC = MetricName.amp;

    private final AmpRequestFactory ampRequestFactory;
    private final ExchangeService exchangeService;
    private final AnalyticsReporterDelegator analyticsDelegator;
    private final Metrics metrics;
    private final HooksMetricsService hooksMetricsService;
    private final Clock clock;
    private final BidderCatalog bidderCatalog;
    private final Set<String> biddersSupportingCustomTargeting;
    private final AmpResponsePostProcessor ampResponsePostProcessor;
    private final HttpInteractionLogger httpInteractionLogger;
    private final PrebidVersionProvider prebidVersionProvider;
    private final HookStageExecutor hookStageExecutor;
    private final JacksonMapper mapper;
    private final double logSamplingRate;

    public AmpHandler(AmpRequestFactory ampRequestFactory,
                      ExchangeService exchangeService,
                      AnalyticsReporterDelegator analyticsDelegator,
                      Metrics metrics,
                      HooksMetricsService hooksMetricsService,
                      Clock clock,
                      BidderCatalog bidderCatalog,
                      Set<String> biddersSupportingCustomTargeting,
                      AmpResponsePostProcessor ampResponsePostProcessor,
                      HttpInteractionLogger httpInteractionLogger,
                      PrebidVersionProvider prebidVersionProvider,
                      HookStageExecutor hookStageExecutor,
                      JacksonMapper mapper,
                      double logSamplingRate) {

        this.ampRequestFactory = Objects.requireNonNull(ampRequestFactory);
        this.exchangeService = Objects.requireNonNull(exchangeService);
        this.analyticsDelegator = Objects.requireNonNull(analyticsDelegator);
        this.metrics = Objects.requireNonNull(metrics);
        this.hooksMetricsService = Objects.requireNonNull(hooksMetricsService);
        this.clock = Objects.requireNonNull(clock);
        this.bidderCatalog = Objects.requireNonNull(bidderCatalog);
        this.biddersSupportingCustomTargeting = Objects.requireNonNull(biddersSupportingCustomTargeting);
        this.ampResponsePostProcessor = Objects.requireNonNull(ampResponsePostProcessor);
        this.httpInteractionLogger = Objects.requireNonNull(httpInteractionLogger);
        this.prebidVersionProvider = Objects.requireNonNull(prebidVersionProvider);
        this.hookStageExecutor = Objects.requireNonNull(hookStageExecutor);
        this.mapper = Objects.requireNonNull(mapper);
        this.logSamplingRate = logSamplingRate;
    }

    @Override
    public List<HttpEndpoint> endpoints() {
        return Collections.singletonList(HttpEndpoint.of(HttpMethod.GET, Endpoint.openrtb2_amp.value()));
    }

    @Override
    public void handle(RoutingContext routingContext) {
        // Prebid Server interprets request.tmax to be the maximum amount of time that a caller is willing to wait
        // for bids. However, tmax may be defined in the Stored Request data.
        // If so, then the trip to the backend might use a significant amount of this time. We can respect timeouts
        // more accurately if we note the real start time, and use it to compute the auction timeout.
        final long startTime = clock.millis();

        final AmpEvent.AmpEventBuilder ampEventBuilder = AmpEvent.builder()
                .httpContext(HttpRequestContext.from(routingContext));

        ampRequestFactory.fromRequest(routingContext, startTime)
                .map(context -> addToEvent(context, ampEventBuilder::auctionContext, context))
                .map(this::updateAppAndNoCookieAndImpsMetrics)
                .compose(exchangeService::holdAuction)
                .map(context -> addContextAndBidResponseToEvent(context, ampEventBuilder, context))
                .compose(context -> prepareSuccessfulResponse(context, routingContext, ampEventBuilder))
                .compose(this::invokeExitpointHooks)
                .map(context -> addContextAndBidResponseToEvent(context.getAuctionContext(), ampEventBuilder, context))
                .onComplete(responseResult -> handleResult(responseResult, ampEventBuilder, routingContext, startTime));
    }

    private static <R> R addContextAndBidResponseToEvent(AuctionContext context,
                                                         AmpEvent.AmpEventBuilder ampEventBuilder,
                                                         R result) {

        ampEventBuilder.auctionContext(context);
        ampEventBuilder.bidResponse(context.getBidResponse());
        return result;
    }

    private static <T, R> R addToEvent(T field, Consumer<T> consumer, R result) {
        consumer.accept(field);
        return result;
    }

    private AuctionContext updateAppAndNoCookieAndImpsMetrics(AuctionContext context) {
        if (!context.isRequestRejected()) {
            final BidRequest bidRequest = context.getBidRequest();
            final UidsCookie uidsCookie = context.getUidsCookie();

            final List<Imp> imps = bidRequest.getImp();
            metrics.updateAppAndNoCookieAndImpsRequestedMetrics(bidRequest.getApp() != null, uidsCookie.hasLiveUids(),
                    imps.size());

            metrics.updateImpTypesMetrics(imps);
        }

        return context;
    }

    private Future<RawResponseContext> prepareSuccessfulResponse(AuctionContext auctionContext,
                                                                 RoutingContext routingContext,
                                                                 AmpEvent.AmpEventBuilder ampEventBuilder) {

        final String origin = originFrom(routingContext);
        final MultiMap responseHeaders = getCommonResponseHeaders(routingContext, origin)
                .add(HttpUtil.CONTENT_TYPE_HEADER, HttpHeaderValues.APPLICATION_JSON);

        return prepareAmpResponse(auctionContext, routingContext)
                .map(result -> addToEvent(result.getLeft().getTargeting(), ampEventBuilder::targeting, result))
                .map(result -> RawResponseContext.builder()
                        .responseBody(mapper.encodeToString(result.getLeft()))
                        .responseHeaders(responseHeaders)
                        .auctionContext(auctionContext)
                        .build());
    }

    private Future<RawResponseContext> invokeExitpointHooks(RawResponseContext rawResponseContext) {
        final AuctionContext auctionContext = rawResponseContext.getAuctionContext();
        return hookStageExecutor.executeExitpointStage(
                        rawResponseContext.getResponseHeaders(),
                        rawResponseContext.getResponseBody(),
                        auctionContext)
                .map(HookStageExecutionResult::getPayload)
                .compose(payload -> Future.succeededFuture(auctionContext)
                        .map(AnalyticsTagsEnricher::enrichWithAnalyticsTags)
                        .map(HookDebugInfoEnricher::enrichWithHooksDebugInfo)
                        .map(hooksMetricsService::updateHooksMetrics)
                        .map(context -> RawResponseContext.builder()
                                .auctionContext(context)
                                .responseHeaders(payload.responseHeaders())
                                .responseBody(payload.responseBody())
                                .build()));
    }

    private Future<Tuple2<AmpResponse, AuctionContext>> prepareAmpResponse(AuctionContext context,
                                                                           RoutingContext routingContext) {

        final BidRequest bidRequest = context.getBidRequest();
        final BidResponse bidResponse = context.getBidResponse();
        final AmpResponse ampResponse = toAmpResponse(bidResponse);
        return ampResponsePostProcessor.postProcess(bidRequest, bidResponse, ampResponse, routingContext)
                .map(resultAmpResponse -> Tuple2.of(resultAmpResponse, context));
    }

    private Map<String, String> targetingFrom(Bid bid, String bidder) {
        final ObjectNode bidExt = bid.getExt();
        if (bidExt == null || !bidExt.hasNonNull(PREBID_EXT)) {
            return Collections.emptyMap();
        }

        final ExtBidPrebid extBidPrebid;
        try {
            extBidPrebid = mapper.mapper().convertValue(bidExt.get(PREBID_EXT), ExtBidPrebid.class);
        } catch (IllegalArgumentException e) {
            throw new PreBidException(
                    "Critical error while unpacking AMP targets: " + e.getMessage(), e);
        }

        // Need to extract the targeting parameters from the response, as those are all that
        // go in the AMP response
        final Map<String, String> targeting = extBidPrebid != null ? extBidPrebid.getTargeting() : null;
        if (targeting != null && targeting.keySet().stream()
                .anyMatch(key -> key != null && key.contains("_cache_id"))) {

            return enrichWithCustomTargeting(targeting, bidExt, bidder);
        }

        return Collections.emptyMap();
    }

    private Map<String, String> enrichWithCustomTargeting(
            Map<String, String> targeting, ObjectNode bidExt, String bidder) {

        final Map<String, String> customTargeting = customTargetingFrom(bidExt, bidder);
        if (!customTargeting.isEmpty()) {
            final Map<String, String> enrichedTargeting = new HashMap<>(targeting);
            enrichedTargeting.putAll(customTargeting);
            return enrichedTargeting;
        }
        return targeting;
    }

    private Map<String, String> customTargetingFrom(ObjectNode extBidBidder, String bidder) {
        if (extBidBidder != null && biddersSupportingCustomTargeting.contains(bidder)
                && bidderCatalog.isValidName(bidder)) {

            return bidderCatalog.bidderByName(bidder).extractTargeting(extBidBidder);
        } else {
            return Collections.emptyMap();
        }
    }

    private AmpResponse toAmpResponse(BidResponse bidResponse) {
        // Fetch targeting information from response bids
        final List<SeatBid> seatBids = bidResponse.getSeatbid();

        final Map<String, JsonNode> targeting = new HashMap<>(seatBids == null
                ? Collections.emptyMap()
                : seatBids.stream()
                .filter(Objects::nonNull)
                .filter(seatBid -> seatBid.getBid() != null)
                .flatMap(seatBid -> seatBid.getBid().stream()
                        .filter(Objects::nonNull)
                        .flatMap(bid -> targetingFrom(bid, seatBid.getSeat()).entrySet().stream()))
                .map(entry -> Tuple2.of(entry.getKey(), TextNode.valueOf(entry.getValue())))
                .collect(Collectors.toMap(Tuple2::getLeft, Tuple2::getRight, (value1, value2) -> value2)));

        final Map<String, JsonNode> additionalTargeting = extractAdditionalTargeting(bidResponse);
        targeting.putAll(additionalTargeting);

        return AmpResponse.of(targeting, extResponseFrom(bidResponse));
    }

    private Map<String, JsonNode> extractAdditionalTargeting(BidResponse bidResponse) {
        final ExtBidResponse extBidResponse = bidResponse.getExt();

        final ExtBidResponsePrebid prebid = extBidResponse != null ? extBidResponse.getPrebid() : null;

        final Map<String, JsonNode> targeting = prebid != null ? prebid.getTargeting() : null;

        return targeting != null ? targeting : Collections.emptyMap();
    }

    private static ExtAmpVideoResponse extResponseFrom(BidResponse bidResponse) {
        final ExtBidResponse ext = bidResponse.getExt();
        final ExtBidResponsePrebid extPrebid = ext != null ? ext.getPrebid() : null;

        final ExtResponseDebug extDebug = ext != null ? ext.getDebug() : null;

        final Map<String, List<ExtBidderError>> extErrors = ext != null ? ext.getErrors() : null;
        final Map<String, List<ExtBidderError>> extWarnings = ext != null ? ext.getWarnings() : null;

        final ExtModules extModules = extPrebid != null ? extPrebid.getModules() : null;
        final ExtAmpVideoPrebid extAmpVideoPrebid = extModules != null ? ExtAmpVideoPrebid.of(extModules) : null;

        return ObjectUtils.anyNotNull(extDebug, extErrors, extWarnings, extAmpVideoPrebid)
                ? ExtAmpVideoResponse.of(extDebug, extErrors, extWarnings, extAmpVideoPrebid)
                : null;
    }

    private void handleResult(AsyncResult<RawResponseContext> responseResult,
                              AmpEvent.AmpEventBuilder ampEventBuilder,
                              RoutingContext routingContext,
                              long startTime) {

        final boolean responseSucceeded = responseResult.succeeded();
        final RawResponseContext rawResponseContext = responseSucceeded ? responseResult.result() : null;

        final MetricName metricRequestStatus;
        final List<String> errorMessages;
        final HttpResponseStatus status;
        final String body;

        final String origin = originFrom(routingContext);
        ampEventBuilder.origin(origin);

        final HttpServerResponse response = routingContext.response();
        final MultiMap responseHeaders = response.headers();

        if (responseSucceeded) {
            metricRequestStatus = MetricName.ok;
            errorMessages = Collections.emptyList();
            status = HttpResponseStatus.OK;

            rawResponseContext.getResponseHeaders()
                    .forEach(header -> HttpUtil.addHeaderIfValueIsNotEmpty(
                            responseHeaders, header.getKey(), header.getValue()));
            body = rawResponseContext.getResponseBody();
        } else {
            getCommonResponseHeaders(routingContext, origin)
                    .forEach(header -> HttpUtil.addHeaderIfValueIsNotEmpty(
                            responseHeaders, header.getKey(), header.getValue()));

            final Throwable exception = responseResult.cause();
            if (exception instanceof InvalidRequestException invalidRequestException) {
                metricRequestStatus = MetricName.badinput;

                errorMessages = invalidRequestException.getMessages().stream()
                        .map(msg -> "Invalid request format: " + msg)
                        .toList();
                final String message = String.join("\n", errorMessages);

                conditionalLogger.info(
                        "%s, Referer: %s"
                                .formatted(message, routingContext.request().headers().get(HttpUtil.REFERER_HEADER)),
                        100);

                status = HttpResponseStatus.BAD_REQUEST;
                body = message;
            } else if (exception instanceof UnauthorizedAccountException) {
                metricRequestStatus = MetricName.badinput;
                final String message = exception.getMessage();
                conditionalLogger.info(message, 100);

                errorMessages = Collections.singletonList(message);

                status = HttpResponseStatus.UNAUTHORIZED;
                body = message;
            } else if (exception instanceof BlocklistedAppException
                    || exception instanceof BlocklistedAccountException) {
                metricRequestStatus = exception instanceof BlocklistedAccountException
                        ? MetricName.blocklisted_account
                        : MetricName.blocklisted_app;
                final String message = "Blocklisted: " + exception.getMessage();
                logger.debug(message);

                errorMessages = Collections.singletonList(message);
                status = HttpResponseStatus.FORBIDDEN;
                body = message;
            } else if (exception instanceof InvalidAccountConfigException) {
                metricRequestStatus = MetricName.bad_requests;
                final String message = exception.getMessage();
                conditionalLogger.error(message, logSamplingRate);

                errorMessages = Collections.singletonList(message);
                status = HttpResponseStatus.BAD_REQUEST;
                body = "Invalid account configuration: " + message;
            } else {
                final String message = exception.getMessage();

                metricRequestStatus = MetricName.err;
                errorMessages = Collections.singletonList(message);
                logger.error("Critical error while running the auction", exception);

                status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                body = "Critical error while running the auction: " + message;
            }
        }

        final int statusCode = status.code();
        final AmpEvent ampEvent = ampEventBuilder.status(statusCode).errors(errorMessages).build();
        final AuctionContext auctionContext = ampEvent.getAuctionContext();

        final PrivacyContext privacyContext = auctionContext != null ? auctionContext.getPrivacyContext() : null;
        final TcfContext tcfContext = privacyContext != null ? privacyContext.getTcfContext() : TcfContext.empty();
        respondWith(routingContext, status, body, startTime, metricRequestStatus, ampEvent, tcfContext);

        httpInteractionLogger.maybeLogOpenrtb2Amp(auctionContext, routingContext, statusCode, body);
    }

    private static String originFrom(RoutingContext routingContext) {
        String origin = null;
        final List<String> ampSourceOrigin = routingContext.queryParam("__amp_source_origin");
        if (CollectionUtils.isNotEmpty(ampSourceOrigin)) {
            origin = ampSourceOrigin.getFirst();
        }
        if (origin == null) {
            // Just to be safe
            origin = ObjectUtils.defaultIfNull(routingContext.request().headers().get("Origin"), StringUtils.EMPTY);
        }
        return origin;
    }

    private void respondWith(RoutingContext routingContext,
                             HttpResponseStatus status,
                             String body,
                             long startTime,
                             MetricName metricRequestStatus,
                             AmpEvent event,
                             TcfContext tcfContext) {

        final boolean responseSent = HttpUtil.executeSafely(routingContext, Endpoint.openrtb2_amp,
                response -> response
                        .exceptionHandler(this::handleResponseException)
                        .setStatusCode(status.code())
                        .end(body));

        if (responseSent) {
            metrics.updateRequestTimeMetric(MetricName.request_time, clock.millis() - startTime);
            metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, metricRequestStatus);
            analyticsDelegator.processEvent(event, tcfContext);
        } else {
            metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, MetricName.networkerr);
        }
    }

    private void handleResponseException(Throwable exception) {
        logger.warn("Failed to send amp response: {}", exception.getMessage());
        metrics.updateRequestTypeMetric(REQUEST_TYPE_METRIC, MetricName.networkerr);
    }

    private MultiMap getCommonResponseHeaders(RoutingContext routingContext, String origin) {
        final MultiMap responseHeaders = MultiMap.caseInsensitiveMultiMap();
        HttpUtil.addHeaderIfValueIsNotEmpty(
                responseHeaders, HttpUtil.X_PREBID_HEADER, prebidVersionProvider.getNameVersionRecord());

        final MultiMap requestHeaders = routingContext.request().headers();
        if (requestHeaders.contains(HttpUtil.SEC_BROWSING_TOPICS_HEADER)) {
            responseHeaders.add(HttpUtil.OBSERVE_BROWSING_TOPICS_HEADER, "?1");
        }

        // Add AMP headers
        responseHeaders.add("AMP-Access-Control-Allow-Source-Origin", origin)
                .add("Access-Control-Expose-Headers", "AMP-Access-Control-Allow-Source-Origin");

        return responseHeaders;
    }
}
