package org.prebid.server.activity.infrastructure.privacy.usnat.reader;

import com.iab.gpp.encoder.GppModel;
import com.iab.gpp.encoder.section.UsUt;
import org.prebid.server.activity.infrastructure.privacy.uscustomlogic.USCustomLogicGppReader;
import org.prebid.server.activity.infrastructure.privacy.usnat.USNatGppReader;
import org.prebid.server.util.ObjectUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class USMappedUtahGppReader implements USNatGppReader, USCustomLogicGppReader {

    private static final List<Integer> DEFAULT_SENSITIVE_DATA_PROCESSING = Collections.nCopies(8, null);
    private static final List<Integer> CHILD_SENSITIVE_DATA = List.of(1, 1);
    private static final List<Integer> NON_CHILD_SENSITIVE_DATA = List.of(0, 0);

    private final UsUt consent;

    public USMappedUtahGppReader(GppModel gppModel) {
        consent = gppModel != null ? gppModel.getUsUtSection() : null;
    }

    @Override
    public Integer getVersion() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getVersion);
    }

    @Override
    public Boolean getGpc() {
        return null;
    }

    @Override
    public Boolean getGpcSegmentType() {
        return null;
    }

    @Override
    public Boolean getGpcSegmentIncluded() {
        return null;
    }

    @Override
    public Integer getSaleOptOut() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getSaleOptOut);
    }

    @Override
    public Integer getSaleOptOutNotice() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getSaleOptOutNotice);
    }

    @Override
    public Integer getSharingNotice() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getSharingNotice);
    }

    @Override
    public Integer getSharingOptOut() {
        return null;
    }

    @Override
    public Integer getSharingOptOutNotice() {
        return null;
    }

    @Override
    public Integer getTargetedAdvertisingOptOut() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getTargetedAdvertisingOptOut);
    }

    @Override
    public Integer getTargetedAdvertisingOptOutNotice() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getTargetedAdvertisingOptOutNotice);
    }

    @Override
    public Integer getSensitiveDataLimitUseNotice() {
        return null;
    }

    @Override
    public List<Integer> getSensitiveDataProcessing() {
        final List<Integer> originalData = Optional.ofNullable(consent)
                .map(UsUt::getSensitiveDataProcessing)
                .orElse(DEFAULT_SENSITIVE_DATA_PROCESSING);

        final List<Integer> data = new ArrayList<>(DEFAULT_SENSITIVE_DATA_PROCESSING);
        data.set(0, originalData.get(0));
        data.set(1, originalData.get(1));
        data.set(2, originalData.get(4));
        data.set(3, originalData.get(2));
        data.set(4, originalData.get(3));
        data.set(5, originalData.get(5));
        data.set(6, originalData.get(6));
        data.set(7, originalData.get(7));

        return Collections.unmodifiableList(data);
    }

    @Override
    public Integer getSensitiveDataProcessingOptOutNotice() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getSensitiveDataProcessingOptOutNotice);
    }

    @Override
    public List<Integer> getKnownChildSensitiveDataConsents() {
        final Integer originalData = consent != null ? consent.getKnownChildSensitiveDataConsents() : null;
        if (originalData == null) {
            return null;
        }

        return originalData == 1 || originalData == 2
                ? CHILD_SENSITIVE_DATA
                : NON_CHILD_SENSITIVE_DATA;
    }

    @Override
    public Integer getPersonalDataConsents() {
        return null;
    }

    @Override
    public Integer getMspaCoveredTransaction() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getMspaCoveredTransaction);
    }

    @Override
    public Integer getMspaServiceProviderMode() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getMspaServiceProviderMode);
    }

    @Override
    public Integer getMspaOptOutOptionMode() {
        return ObjectUtil.getIfNotNull(consent, UsUt::getMspaOptOutOptionMode);
    }
}
