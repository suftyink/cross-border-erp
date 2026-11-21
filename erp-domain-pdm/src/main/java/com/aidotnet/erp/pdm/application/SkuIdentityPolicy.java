package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.pdm.SkuUsageProbe;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuIdentityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuIdentityPolicy {
    private final ProductStore store;
    private final SkuIdentityStore identities;
    private final List<SkuUsageProbe> probes;

    public SkuIdentityPolicy(ProductStore store, SkuIdentityStore identities, List<SkuUsageProbe> probes) {
        this.store = store;
        this.identities = identities;
        this.probes = probes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimNew(String tenant, String skuId, String code) {
        identities.lockCodes(tenant, List.of(code));
        if (store.findSkuBySellerSku(tenant, code).isPresent()) throw new BizException("SKU_DUPLICATED", "SKU已存在");
        identities.claim(tenant, code, skuId);
        boolean referenced = identities.referenced(tenant, code) || !usages(tenant, skuId, code).isEmpty();
        if (referenced) identities.reference(tenant, code);
        return referenced;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Sku lock(String tenant, String skuId, String candidateCode) {
        Sku before = store.findSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
        List<String> codes = new ArrayList<>(List.of(before.sellerSku()));
        if (candidateCode != null) codes.add(candidateCode);
        identities.lockCodes(tenant, codes);
        Sku current = store.lockSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
        if (!Objects.equals(current.sellerSku(), before.sellerSku())) throw new BizException("SKU_CONCURRENT_CHANGE", "商品身份已变化，请刷新后重试");
        boolean referenced = identities.referenced(tenant, current.sellerSku())
                || !usages(tenant, current.skuId(), current.sellerSku()).isEmpty();
        if (referenced) identities.reference(tenant, current.sellerSku());
        if (referenced && !current.identityLocked()) {
            return new Sku(current.skuId(), tenant, current.spuId(), current.sellerSku(), current.title(), current.baseUnit(),
                    current.weightKg(), current.declaredValue(), current.currency(), current.status(), current.createdAt(), current.updatedAt(),
                    current.everEnabled(), true);
        }
        return current;
    }

    public List<SkuUsageProbe.Usage> usages(String tenant, String skuId, String code) {
        List<SkuUsageProbe.Identity> targets = List.of(new SkuUsageProbe.Identity(skuId, code));
        return probes.stream().flatMap(probe -> probe.find(tenant, targets).stream()).toList();
    }

    public boolean referenced(Sku sku) {
        return identities.referenced(sku.tenantId(), sku.sellerSku()) || !usages(sku.tenantId(), sku.skuId(), sku.sellerSku()).isEmpty();
    }

    public java.util.Set<String> referencedIds(String tenant, List<Sku> skus) {
        List<Sku> candidates = skus.stream().filter(this::editableIdentity).toList();
        if (candidates.isEmpty()) return java.util.Set.of();
        java.util.Set<String> found = candidates.stream().filter(sku -> identities.referenced(tenant, sku.sellerSku()))
                .map(Sku::skuId).collect(java.util.stream.Collectors.toSet());
        List<SkuUsageProbe.Identity> targets = candidates.stream().map(sku -> new SkuUsageProbe.Identity(sku.skuId(), sku.sellerSku())).toList();
        for (SkuUsageProbe probe : probes) probe.find(tenant, targets).forEach(usage -> found.add(usage.skuId()));
        return found;
    }

    public boolean editableIdentity(Sku sku) {
        return sku.status() == com.aidotnet.erp.pdm.domain.ProductStatus.DRAFT
                && Boolean.FALSE.equals(sku.everEnabled()) && !sku.identityLocked();
    }

    public void rename(Sku before, Sku after) {
        if (!before.sellerSku().equals(after.sellerSku())) {
            if (identities.referenced(after.tenantId(), after.sellerSku()) || !usages(after.tenantId(), after.skuId(), after.sellerSku()).isEmpty()) {
                throw new BizException("SKU_CODE_REFERENCED", "新编码已有业务引用，需要先核实对应商品");
            }
            identities.retire(before.tenantId(), before.sellerSku(), before.skuId());
            if (store.findSkuBySellerSku(after.tenantId(), after.sellerSku()).filter(existing -> !existing.skuId().equals(after.skuId())).isPresent()) {
                throw new BizException("SKU_DUPLICATED", "SKU已存在");
            }
            identities.claim(after.tenantId(), after.sellerSku(), after.skuId());
        }
    }

    public void retire(Sku sku) {
        identities.deleteDraftVariant(sku.tenantId(), sku.skuId());
        identities.retire(sku.tenantId(), sku.sellerSku(), sku.skuId());
        identities.deleted(sku.tenantId(), sku.skuId(), sku.sellerSku());
    }

    public void syncVariants(Sku sku) { identities.syncVariant(sku); }
}
