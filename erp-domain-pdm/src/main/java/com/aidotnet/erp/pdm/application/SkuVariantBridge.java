package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.ProductVariant;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuIdentityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 旧变体入口使用同一个库存 SKU；不能单独维护另一套启用状态。 */
@Service
public class SkuVariantBridge {
    private final ProductStore products;
    private final SkuDraftService drafts;
    private final SkuIdentityPolicy policy;
    private final SkuIdentityStore identities;
    private final SkuLifecycleService lifecycle;

    public SkuVariantBridge(ProductStore products, SkuDraftService drafts, SkuIdentityPolicy policy,
                            SkuIdentityStore identities, SkuLifecycleService lifecycle) {
        this.products = products;
        this.drafts = drafts;
        this.policy = policy;
        this.identities = identities;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Sku bind(String tenant, String spuId, String code, String title, String unit) {
        products.lockSpu(tenant, spuId).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        String localCode = code == null || code.isBlank() ? SkuCode.generate() : SkuCode.normalize(code);
        Sku sku = products.findSkuBySellerSku(tenant, localCode).orElse(null);
        if (sku == null) {
            var created = drafts.create(tenant, new SkuDraftService.CreateDraft(spuId, localCode, title, unit, null));
            sku = products.findSku(tenant, created.skuId()).orElseThrow();
        }
        if (!sku.spuId().equals(spuId)) throw new BizException("SKU_SPU_MISMATCH", "本地编码已经属于其他 SPU");
        sku = policy.lock(tenant, sku.skuId(), localCode);
        if (!policy.editableIdentity(sku)) throw new BizException("SKU_IDENTITY_LOCKED", "已锁定的 SKU 不能增加另一套规格身份");
        if (identities.hasVariant(tenant, sku.skuId())) throw new BizException("VARIANT_DUPLICATED", "该 SKU 已有对应变体");
        if (unit != null && !unit.isBlank() && !unit.equals(sku.baseUnit())) {
            drafts.update(tenant, sku.skuId(), new SkuDraftService.UpdateDraft(null, unit, null, null));
            sku = products.findSku(tenant, sku.skuId()).orElseThrow();
        }
        return sku;
    }

    @Transactional
    public Sku activate(String tenant, ProductVariant variant) {
        if (variant.skuId() == null || identities.variantNeedsReview(tenant, variant.variantId())) throw new BizException("VARIANT_HISTORY_REVIEW_REQUIRED", "旧变体的库存身份需要核实");
        Sku sku = products.findSku(tenant, variant.skuId()).orElseThrow(() -> new BizException("VARIANT_HISTORY_REVIEW_REQUIRED", "旧变体关联的 SKU 不存在"));
        if (!sku.spuId().equals(variant.spuId()) || !sku.sellerSku().equals(variant.sellerSku())) {
            throw new BizException("VARIANT_HISTORY_REVIEW_REQUIRED", "旧变体与 SKU 身份不一致，需要核实");
        }
        return lifecycle.enable(tenant, sku.skuId());
    }
}
