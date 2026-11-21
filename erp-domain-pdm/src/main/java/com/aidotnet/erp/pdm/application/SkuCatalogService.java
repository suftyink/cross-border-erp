package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.pdm.SkuCatalog;
import com.aidotnet.erp.pdm.domain.ProductStatus;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuIdentityStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuCatalogService implements SkuCatalog {
    private final ProductStore products;
    private final SkuLifecycleService lifecycle;
    private final SkuIdentityPolicy policy;
    private final SkuIdentityStore identities;

    public SkuCatalogService(ProductStore products, SkuLifecycleService lifecycle, SkuIdentityPolicy policy, SkuIdentityStore identities) {
        this.products = products;
        this.lifecycle = lifecycle;
        this.policy = policy;
        this.identities = identities;
    }

    @Override
    public List<Item> findActive(String tenantId, String keyword) {
        requireTenant(tenantId);
        return products.querySkus(tenantId, keyword == null || keyword.isBlank() ? null : keyword.strip(), null, "ACTIVE").stream()
                .filter(sku -> Boolean.TRUE.equals(sku.everEnabled()) && lifecycle.missingFields(sku).isEmpty()).limit(500)
                .map(this::item).toList();
    }

    @Override
    public List<Check> validate(String tenantId, Collection<Selection> selections) {
        requireTenant(tenantId);
        if (selections == null || selections.isEmpty() || selections.size() > 200) throw new BizException("SKU_SELECTION_INVALID", "一次请选择 1 至 200 个商品");
        List<Check> checks = new ArrayList<>();
        for (Selection selection : selections) {
            try {
                Sku sku = resolve(tenantId, selection);
                if (sku.status() != ProductStatus.ACTIVE) checks.add(failed(selection, "SKU_NOT_ACTIVE", "SKU 未启用"));
                else if (!Boolean.TRUE.equals(sku.everEnabled())) checks.add(failed(selection, "SKU_HISTORY_REVIEW_REQUIRED", "历史启用事实需要核实"));
                else {
                    List<String> missing = lifecycle.missingFields(sku);
                    checks.add(missing.isEmpty() ? new Check(selection, true, "VALID", "可使用", List.of(), item(sku))
                            : new Check(selection, false, "SKU_INCOMPLETE", "商品资料不完整", missing, null));
                }
            } catch (BizException error) { checks.add(failed(selection, error.getCode(), error.getMessage())); }
        }
        return List.copyOf(checks);
    }

    @Override
    @Transactional
    public List<Item> requireActive(String tenantId, Collection<Selection> selections) {
        List<Check> checks = validate(tenantId, selections);
        for (Check check : checks) if (!check.valid()) throw new BizException(check.code(), check.message());
        List<Item> items = checks.stream().map(Check::item).toList();
        for (String parent : items.stream().map(Item::spuId).distinct().sorted().toList()) {
            products.lockSpu(tenantId, parent).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        }
        identities.lockCodes(tenantId, items.stream().map(Item::sellerSku).toList());
        java.util.Map<String, Item> currentItems = new java.util.HashMap<>();
        for (Item observed : items.stream().sorted(Comparator.comparing(Item::sellerSku)).toList()) {
            Sku current = policy.lock(tenantId, observed.skuId(), observed.sellerSku());
            if (!Objects.equals(observed.spuId(), current.spuId()) || !Objects.equals(observed.sellerSku(), current.sellerSku())) {
                throw new BizException("SKU_CONCURRENT_CHANGE", "商品身份已变化，请重新选择");
            }
            if (current.status() != ProductStatus.ACTIVE || !Boolean.TRUE.equals(current.everEnabled()) || !lifecycle.missingFields(current).isEmpty()) {
                throw new BizException("SKU_UNAVAILABLE", "商品已不可用于新增业务，请重新选择");
            }
            currentItems.put(current.skuId(), item(current));
        }
        return items.stream().map(observed -> currentItems.get(observed.skuId())).toList();
    }

    private Sku resolve(String tenant, Selection selection) {
        if (selection == null) throw new BizException("SKU_SELECTION_INVALID", "商品选择不能为空");
        Sku sku;
        if (selection.skuId() != null && !selection.skuId().isBlank()) {
            sku = products.findSku(tenant, selection.skuId()).orElse(null);
            if (sku == null && identities.belongsToAnotherTenant(tenant, selection.skuId())) throw new BizException("SKU_CROSS_TENANT", "商品不属于当前租户");
        } else if (selection.sellerSku() != null && !selection.sellerSku().isBlank()) {
            sku = products.findSkuBySellerSku(tenant, SkuCode.normalize(selection.sellerSku())).orElse(null);
        } else throw new BizException("SKU_SELECTION_INVALID", "请提供本地 SKU ID 或编码");
        if (sku == null) throw new BizException("SKU_NOT_FOUND", "SKU 不存在");
        if (selection.sellerSku() != null && !selection.sellerSku().isBlank() && !SkuCode.normalize(selection.sellerSku()).equals(sku.sellerSku())) {
            throw new BizException("SKU_IDENTITY_MISMATCH", "商品 ID 与本地编码不一致");
        }
        return sku;
    }

    private Item item(Sku sku) {
        return new Item(sku.skuId(), sku.spuId(), sku.sellerSku(), sku.title(), sku.baseUnit(), sku.status().name(), sku.weightKg(), sku.declaredValue(), sku.currency());
    }

    private Check failed(Selection selection, String code, String message) { return new Check(selection, false, code, message, List.of(), null); }
    private void requireTenant(String tenant) { if (tenant == null || tenant.isBlank()) throw new BizException("TENANT_REQUIRED", "租户不能为空"); }
}
