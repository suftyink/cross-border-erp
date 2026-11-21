package com.aidotnet.erp.pdm.domain;

import java.time.Instant;
import java.util.List;

public final class SkuGeneration {
    private SkuGeneration() {}

    public record Existing(String skuId, String sellerSku, String status) {}
    public record Row(String combinationKey, List<Specification.Item> specifications, String sellerSku,
                      String title, String baseUnit, Existing existing) {}
    public record Preview(String previewId, String spuId, Instant expiresAt, List<Row> rows) {}
    public record RequestedRow(String combinationKey, String sellerSku, String title, String baseUnit) {}
}
