package com.aidotnet.erp.wms.domain;

import java.time.Instant;

public record PurchaseInspection(
        String inspectionId, int sampleChecked, int samplePassed, int sampleFailed,
        String sampleOperatorId, Instant sampledAt, RecheckMethod recheckMethod,
        Integer recheckChecked, Integer recheckPassed, Integer recheckFailed, String recheckOperatorId,
        Instant recheckedAt, Integer goodQuantity, Integer defectiveQuantity,
        String dispositionOperatorId, Instant disposedAt
) {
    public enum RecheckMethod { RECHECK, FULL }

    public record Sample(int checkedQuantity, int passedQuantity, int failedQuantity,
                         String operatorId, Instant checkedAt) {}

    public record Recheck(RecheckMethod method, int checkedQuantity, int passedQuantity, int failedQuantity,
                          String operatorId, Instant checkedAt) {}

    public Sample sample() {
        return new Sample(sampleChecked, samplePassed, sampleFailed, sampleOperatorId, sampledAt);
    }

    public Recheck recheck() {
        return recheckMethod == null ? null : new Recheck(recheckMethod, recheckChecked,
                recheckPassed, recheckFailed, recheckOperatorId, recheckedAt);
    }
}
