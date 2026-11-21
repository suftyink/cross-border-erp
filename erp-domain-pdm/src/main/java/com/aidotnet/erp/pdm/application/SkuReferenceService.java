package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.pdm.SkuReferences;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuIdentityStore;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuReferenceService implements SkuReferences {
    private final SkuIdentityStore identities;
    private final ProductStore products;

    public SkuReferenceService(SkuIdentityStore identities, ProductStore products) {
        this.identities = identities;
        this.products = products;
    }

    @Override
    @Transactional
    public void retain(String tenantId, Collection<String> sellerSkus) {
        for (String code : identities.lockCodes(tenantId, sellerSkus)) identities.reference(tenantId, code);
    }

    @Override
    @Transactional
    public void retainIds(String tenantId, Collection<String> skuIds) {
        record Target(String id, String code) {}
        java.util.ArrayList<Target> targets = new java.util.ArrayList<>();
        for (String id : skuIds.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList()) {
            var before = products.findSku(tenantId, id).orElse(null);
            if (before == null) {
                if (identities.deletedId(tenantId, id)) throw new BizException("SKU_REFERENCE_RETIRED", "商品已删除，请重新选择商品");
                continue; // 旧数据中的未知身份不在这里自动造商品；业务准入由有效 SKU 契约校验。
            }
            targets.add(new Target(id, before.sellerSku()));
        }
        identities.lockCodes(tenantId, targets.stream().map(Target::code).toList());
        for (Target target : targets.stream().sorted(java.util.Comparator.comparing(Target::code)).toList()) {
            var current = products.findSku(tenantId, target.id()).orElseThrow(() -> new BizException("SKU_REFERENCE_CHANGED", "商品身份已变化，请重新选择"));
            if (!current.sellerSku().equals(target.code())) throw new BizException("SKU_REFERENCE_CHANGED", "商品身份已变化，请重新选择");
            identities.reference(tenantId, current.sellerSku());
        }
    }
}
