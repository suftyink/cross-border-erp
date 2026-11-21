package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.ProductStatus;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.domain.Spu;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuHistoryStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SKU 建档与资料维护；身份和启停规则由同一生命周期策略约束。 */
@Service
public class SkuDraftService {
    private final ProductStore store;
    private final ProductService products;
    private final SkuLifecycleService lifecycle;
    private final SkuIdentityPolicy identities;
    private final SkuHistoryStore history;
    private final SkuSpecificationService specifications;
    private final SkuDetailsService details;

    public SkuDraftService(ProductStore store, ProductService products, SkuLifecycleService lifecycle,
                           SkuIdentityPolicy identities, SkuHistoryStore history, SkuSpecificationService specifications, SkuDetailsService details) {
        this.store = store;
        this.products = products;
        this.lifecycle = lifecycle;
        this.identities = identities;
        this.history = history;
        this.specifications = specifications;
        this.details = details;
    }

    @Transactional
    public SkuDraftView create(String tenant, CreateDraft command) {
        String spuId = clean(command.spuId());
        if ((spuId == null) == (command.newSpu() == null)) {
            throw new BizException("SKU_SPU_REQUIRED", "请选择已有 SPU 或填写新 SPU，两者只能选一种");
        }
        if (command.newSpu() != null) {
            NewSpu parent = command.newSpu();
            String parentCode = clean(parent.sku());
            if (parentCode == null) {
                parentCode = "SPU-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            }
            spuId = products.createSpu(tenant, new ProductService.CreateSpuCommand(parentCode,
                    clean(parent.title()), clean(parent.description()), clean(parent.categoryId()), clean(parent.brandId()))).spuId();
        }
        String code = clean(command.sellerSku());
        if (code == null) code = SkuCode.generate();
        Sku sku = products.createSku(tenant, spuId, new ProductService.CreateSkuCommand(code,
                clean(command.title()), null, null, null, clean(command.baseUnit()), command.specifications()), false);
        history.appendCreate(tenant, sku.skuId(), sku, specifications.items(tenant, sku.skuId()));
        return view(sku, products.getSpu(tenant, spuId));
    }

    public List<SkuDraftView> list(String tenant, String keyword, String spuId, String status) {
        String state = clean(status);
        if (state != null) {
            try {
                state = ProductStatus.valueOf(state.toUpperCase(Locale.ROOT)).name();
            } catch (IllegalArgumentException ex) {
                throw new BizException("SKU_FILTER_INVALID", "SKU 状态筛选无效");
            }
        }
        Map<String, Spu> parents = store.listSpus(tenant).stream()
                .collect(Collectors.toMap(Spu::spuId, Function.identity()));
        List<Sku> skus = store.querySkus(tenant, clean(keyword), clean(spuId), state);
        var referenced = identities.referencedIds(tenant, skus);
        return skus.stream().map(sku -> view(sku, parents.get(sku.spuId()), referenced.contains(sku.skuId()))).toList();
    }

    public SkuDraftView get(String tenant, String skuId) {
        Sku sku = requireSku(tenant, skuId);
        return view(sku, products.getSpu(tenant, sku.spuId()));
    }

    @Transactional
    public SkuDraftView update(String tenant, String skuId, UpdateDraft command) {
        specifications.lock(tenant);
        Sku observed = requireSku(tenant, skuId);
        String parentId = command.spuId() == null ? observed.spuId() : clean(command.spuId());
        if (parentId == null) throw new BizException("SKU_SPU_REQUIRED", "所属 SPU 不能为空");
        for (String parent : java.util.stream.Stream.of(observed.spuId(), parentId).distinct().sorted().toList()) {
            store.lockSpu(tenant, parent).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        }
        String code = command.sellerSku() == null || command.sellerSku().equals(observed.sellerSku())
                ? observed.sellerSku() : SkuCode.normalize(command.sellerSku());
        Sku sku = identities.lock(tenant, skuId, code);
        if (!sku.spuId().equals(observed.spuId())) throw new BizException("SKU_CONCURRENT_CHANGE", "商品归属已变化，请刷新后重试");
        if (sku.status() != ProductStatus.DRAFT && sku.status() != ProductStatus.ACTIVE && sku.status() != ProductStatus.INACTIVE) {
            throw new BizException("SKU_HISTORY_REVIEW_REQUIRED", "历史 SKU 状态需要核实");
        }
        String unit = command.baseUnit() == null ? sku.baseUnit() : clean(command.baseUnit());
        if ((!Objects.equals(code, sku.sellerSku()) || !Objects.equals(parentId, sku.spuId()) || !Objects.equals(unit, sku.baseUnit()))
                && !identities.editableIdentity(sku)) {
            throw new BizException("SKU_IDENTITY_LOCKED", "商品已启用、被引用或历史待核实，身份字段永久锁定");
        }
        Sku updated = new Sku(sku.skuId(), tenant, parentId, code,
                command.title() == null ? sku.title() : clean(command.title()), unit,
                sku.weightKg(), sku.declaredValue(), sku.currency(), sku.status(), sku.createdAt(), Instant.now(), sku.everEnabled(), sku.identityLocked());
        boolean coreChanged = !Objects.equals(sku.spuId(), updated.spuId())
                || !Objects.equals(sku.sellerSku(), updated.sellerSku())
                || !Objects.equals(sku.title(), updated.title())
                || !Objects.equals(sku.baseUnit(), updated.baseUnit());
        identities.rename(sku, updated);
        if (!parentId.equals(sku.spuId())) {
            specifications.releaseKey(tenant, skuId);
        }
        store.saveSku(updated);
        if (coreChanged) {
            history.append(tenant, skuId, "UPDATE", sku, updated, null);
        }
        specifications.assign(updated, command.specifications(), true);
        identities.syncVariants(updated);
        return view(updated, products.getSpu(tenant, updated.spuId()));
    }

    private Sku requireSku(String tenant, String skuId) {
        return store.findSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
    }

    public SkuDraftView enable(String tenant, String skuId) {
        Sku sku = lifecycle.enable(tenant, skuId);
        return view(sku, products.getSpu(tenant, sku.spuId()));
    }

    public SkuDraftView disable(String tenant, String skuId) {
        Sku sku = lifecycle.disable(tenant, skuId);
        return view(sku, products.getSpu(tenant, sku.spuId()));
    }

    @Transactional
    public SkuDraftView createAndEnable(String tenant, CreateDraft command) {
        SkuDraftView created = create(tenant, command);
        return enable(tenant, created.skuId());
    }

    @Transactional
    public SkuDraftView updateAndEnable(String tenant, String skuId, UpdateDraft command) {
        update(tenant, skuId, command);
        return enable(tenant, skuId);
    }

    private SkuDraftView view(Sku sku, Spu spu) {
        return view(sku, spu, identities.editableIdentity(sku) && identities.referenced(sku));
    }

    private SkuDraftView view(Sku sku, Spu spu, boolean referenced) {
        List<String> missing = lifecycle.missingFields(sku);
        boolean identityEditable = identities.editableIdentity(sku) && !referenced;
        return new SkuDraftView(sku.skuId(), sku.spuId(), sku.sellerSku(), sku.title(), sku.baseUnit(),
                sku.status(), spu == null ? null : spu.sku(), spu == null ? null : spu.title(),
                spu == null ? null : spu.categoryId(), spu == null ? null : spu.brandId(),
                sku.weightKg(), sku.declaredValue(), sku.currency(),
                sku.status() == ProductStatus.DRAFT || sku.status() == ProductStatus.ACTIVE || sku.status() == ProductStatus.INACTIVE,
                List.copyOf(missing), sku.createdAt(), sku.updatedAt(), sku.everEnabled(), !identityEditable,
                identityEditable, identityEditable, sku.everEnabled() == null || specifications.needsReview(sku.tenantId(),sku.skuId()),
                specifications.items(sku.tenantId(),sku.skuId()), details.current(sku));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record NewSpu(String sku, String title, String description, String categoryId, String brandId) {}
    public record CreateDraft(String spuId, String sellerSku, String title, String baseUnit, NewSpu newSpu, List<com.aidotnet.erp.pdm.domain.Specification.Selection> specifications) {
        public CreateDraft(String spuId,String sellerSku,String title,String baseUnit,NewSpu newSpu) { this(spuId,sellerSku,title,baseUnit,newSpu,null); }
    }
    public record UpdateDraft(String title, String baseUnit, String sellerSku, String spuId, List<com.aidotnet.erp.pdm.domain.Specification.Selection> specifications) {
        public UpdateDraft(String title,String baseUnit,String sellerSku,String spuId) { this(title,baseUnit,sellerSku,spuId,null); }
    }
    public record SkuDraftView(String skuId, String spuId, String sellerSku, String title, String baseUnit,
                               ProductStatus status, String spuCode, String spuTitle, String categoryId, String brandId,
                               BigDecimal weightKg, BigDecimal declaredValue, String currency,
                               boolean editable, List<String> missingFields, Instant createdAt, Instant updatedAt,
                               Boolean everEnabled, boolean identityLocked, boolean identityEditable, boolean deletable,
                               boolean historyReviewRequired, List<com.aidotnet.erp.pdm.domain.Specification.Item> specifications, com.aidotnet.erp.common.pdm.ProductData details) {}
}
