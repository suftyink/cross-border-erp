package com.aidotnet.erp.common.pdm;

import java.util.Collection;
import java.util.List;

/** 各来源域负责解释自己的历史引用，包含关闭单据和零余额。 */
public interface SkuUsageProbe {
    List<Usage> find(String tenantId, Collection<Identity> identities);

    record Identity(String skuId, String sellerSku) {}
    record Usage(String skuId, String domain, String kind, String sourceId) {}
}
