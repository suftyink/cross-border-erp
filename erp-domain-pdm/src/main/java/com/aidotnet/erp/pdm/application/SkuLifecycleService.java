package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.ProductStatus;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuHistoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuLifecycleService {
    private final ProductStore store;
    private final SkuIdentityPolicy identities;
    private final SkuHistoryStore history;
    private final SkuSpecificationService specifications;

    public SkuLifecycleService(ProductStore store, SkuIdentityPolicy identities, SkuHistoryStore history, SkuSpecificationService specifications) {
        this.store = store;
        this.identities = identities;
        this.history = history;
        this.specifications = specifications;
    }

    @Transactional
    public Sku enable(String tenant, String skuId) {
        return enable(tenant, skuId, null);
    }

    @Transactional
    public Sku enable(String tenant, String skuId, String reason) {
        specifications.lock(tenant);
        Sku observed = store.findSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
        store.lockSpu(tenant, observed.spuId()).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        Sku sku = identities.lock(tenant, skuId, null);
        if (!sku.spuId().equals(observed.spuId())) throw new BizException("SKU_CONCURRENT_CHANGE", "商品归属已变化，请刷新后重试");
        if (sku.status() != ProductStatus.DRAFT && sku.status() != ProductStatus.ACTIVE && sku.status() != ProductStatus.INACTIVE) {
            throw new BizException("SKU_HISTORY_REVIEW_REQUIRED", "历史 SKU 状态需要核实");
        }
        List<String> missing = missingFields(sku);
        if (!missing.isEmpty()) throw new BizException("SKU_INCOMPLETE", "启用前请补齐：" + String.join("、", missing));
        Sku active = new Sku(sku.skuId(), tenant, sku.spuId(), sku.sellerSku(), sku.title(), sku.baseUnit(),
                sku.weightKg(), sku.declaredValue(), sku.currency(), ProductStatus.ACTIVE, sku.createdAt(), Instant.now(), true, true);
        store.saveSku(active);
        identities.syncVariants(active);
        if (sku.status() != ProductStatus.ACTIVE) history.append(tenant, skuId, "ENABLE", sku, active, reason);
        return active;
    }

    @Transactional
    public Sku disable(String tenant, String skuId) {
        return disable(tenant, skuId, null);
    }

    @Transactional
    public Sku disable(String tenant, String skuId, String reason) {
        Sku sku = identities.lock(tenant, skuId, null);
        if (sku.status() != ProductStatus.ACTIVE && sku.status() != ProductStatus.INACTIVE) {
            throw new BizException("SKU_STATUS_INVALID", "只有启用或已停用 SKU 可执行停用");
        }
        Sku inactive = new Sku(sku.skuId(), tenant, sku.spuId(), sku.sellerSku(), sku.title(), sku.baseUnit(),
                sku.weightKg(), sku.declaredValue(), sku.currency(), ProductStatus.INACTIVE, sku.createdAt(), Instant.now(),
                sku.status() == ProductStatus.ACTIVE ? true : sku.everEnabled(), true);
        store.saveSku(inactive);
        identities.syncVariants(inactive);
        if (sku.status() != ProductStatus.INACTIVE) history.append(tenant, skuId, "DISABLE", sku, inactive, reason);
        return inactive;
    }

    public List<String> missingFields(Sku sku) {
        List<String> missing = new ArrayList<>();
        try {
            if (!SkuCode.normalize(sku.sellerSku()).equals(sku.sellerSku())) missing.add("规范化本地编码");
        } catch (BizException error) { missing.add("有效本地编码"); }
        if (sku.title() == null || sku.title().isBlank()) missing.add("SKU 名称");
        if (sku.baseUnit() == null || sku.baseUnit().isBlank()) missing.add("基本库存单位");
        var parent = store.findSpu(sku.tenantId(), sku.spuId()).orElse(null);
        if (parent == null) missing.add("所属 SPU");
        else if (parent.categoryId() == null || store.findCategory(sku.tenantId(), parent.categoryId()).isEmpty()) missing.add("有效 SPU 分类");
        if (parent != null && parent.brandId() != null && !parent.brandId().isBlank() && store.findBrand(sku.tenantId(), parent.brandId()).isEmpty()) missing.add("有效 SPU 品牌");
        missing.addAll(specifications.missing(sku));
        return List.copyOf(missing);
    }

    @Transactional
    public void delete(String tenant, String skuId) {
        specifications.lock(tenant);
        Sku sku = identities.lock(tenant, skuId, null);
        if (identities.referenced(sku)) throw new BizException("SKU_REFERENCED", "商品曾被业务引用，不能删除");
        if (!identities.editableIdentity(sku)) throw new BizException("SKU_DELETE_FORBIDDEN", "只有从未启用且无引用的草稿可以删除");
        identities.retire(sku);
        history.append(tenant, skuId, "DELETE", sku, null, null);
        store.deleteSku(tenant, skuId);
    }
}
