package org.prebid.server.bidder.lifestreet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.lifestreet.ExtImpLifestreet;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lifestreet {@link Bidder} implementation.
 */
public class LifestreetBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpLifestreet>> LIFESTREET_EXT_TYPE_REFERENCE = new
            TypeReference<ExtPrebid<?, ExtImpLifestreet>>() {
            };

    private final String endpointUrl;
    private final JacksonMapper mapper;

    public LifestreetBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {

        final List<BidderError> errors = new ArrayList<>();
        final List<HttpRequest<BidRequest>> httpRequests = new ArrayList<>();
        for (Imp imp : bidRequest.getImp()) {
            try {
                validateImp(imp);
                final BidRequest outgoingRequest = createRequest(imp, bidRequest);
                final String body = mapper.encode(outgoingRequest);
                httpRequests.add(HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(endpointUrl)
                        .body(body)
                        .headers(HttpUtil.headers())
                        .payload(outgoingRequest)
                        .build());
            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        return Result.of(httpRequests, errors);
    }

    private static void validateImp(Imp imp) {
        if (imp.getBanner() == null && imp.getVideo() == null) {
            throw new PreBidException(String.format("Invalid MediaType. Lifestreet supports only Banner and Video. "
                    + "Ignoring ImpID=%s", imp.getId()));
        }
    }

    private BidRequest createRequest(Imp imp, BidRequest bidRequest) {
        final ExtImpLifestreet extImpLifestreet = parseAndValidateImpExt(imp);
        final Imp.ImpBuilder impBuilder = imp.toBuilder();
        impBuilder.tagid(extImpLifestreet.getSlotTag());

        final Banner banner = imp.getBanner();
        if (banner != null) {
            final Banner.BannerBuilder bannerBuilder = banner.toBuilder();
            bannerBuilder.format(null);

            final List<Format> formats = banner.getFormat();
            if (CollectionUtils.isNotEmpty(formats)) {
                final Format firstFormat = formats.get(0);
                bannerBuilder.w(firstFormat.getW());
                bannerBuilder.h(firstFormat.getH());
            }
            impBuilder.banner(bannerBuilder.build());
        }

        return bidRequest.toBuilder()
                .imp(Collections.singletonList(impBuilder.build()))
                .build();
    }

    private ExtImpLifestreet parseAndValidateImpExt(Imp imp) {
        ExtImpLifestreet extImpLifestreet;
        try {
            extImpLifestreet = mapper.mapper().convertValue(imp.getExt(), LIFESTREET_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }

        final String slotTag = extImpLifestreet.getSlotTag();
        if (StringUtils.isEmpty(slotTag)) {
            throw new PreBidException("Missing slot_tag param");
        }
        if (slotTag.split("\\.").length != 2) {
            throw new PreBidException(String.format("Invalid slot_tag param '%s'", slotTag));
        }
        return extImpLifestreet;
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return Result.withValues(extractBids(httpCall.getRequest().getPayload(), bidResponse));
        } catch (DecodeException | PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private static List<BidderBid> extractBids(BidRequest bidRequest, BidResponse bidResponse) {
        return bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())
                ? Collections.emptyList()
                : bidsFromResponse(bidRequest, bidResponse);
    }

    private static List<BidderBid> bidsFromResponse(BidRequest bidRequest, BidResponse bidResponse) {
        return bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> BidderBid.of(bid, getBidType(bid.getImpid(), bidRequest.getImp()), bidResponse.getCur()))
                // one bid per request/response
                .limit(1)
                .collect(Collectors.toList());
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId) && imp.getVideo() != null) {
                return BidType.video;
            }
        }
        return BidType.banner;
    }
}
