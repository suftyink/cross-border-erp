package com.aidotnet.erp.common.pdm;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/** 本地 SKU 公共契约，不解析平台 MSKU；写业务在自身事务内调用 requireActive 保持校验与引用一致。 */
public interface SkuCatalog {
    List<Item> findActive(String tenantId, String keyword);
    List<Check> validate(String tenantId, Collection<Selection> selections);
    List<Item> requireActive(String tenantId, Collection<Selection> selections);

    record Selection(String skuId, String sellerSku) {}
    record Item(String skuId, String spuId, String sellerSku, String title, String baseUnit, String status,
                BigDecimal weightKg, BigDecimal declaredValue, String currency, List<Specification> specifications) {}
    record Specification(String attributeId,String attributeCode,String attributeName,String valueId,String valueCode,String valueName) {}
    record Check(Selection selection, boolean valid, String code, String message, List<String> missingFields, Item item) {}
}
