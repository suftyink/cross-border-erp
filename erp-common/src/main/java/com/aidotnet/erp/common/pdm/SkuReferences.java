package com.aidotnet.erp.common.pdm;

import java.util.Collection;

/** 在来源记录的同一事务内保留商品身份；这里不代替新业务的有效性校验。 */
public interface SkuReferences {
    void retain(String tenantId, Collection<String> sellerSkus);
    void retainIds(String tenantId, Collection<String> skuIds);

}
