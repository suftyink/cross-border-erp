package com.aidotnet.erp.scm.domain;

import java.time.Instant;

public record PurchaseAmendmentLine(
        String amendmentId, String lineId, int previousQuantity, int revisedQuantity,
        String reason, String operatorId, Instant createdAt
) {}
