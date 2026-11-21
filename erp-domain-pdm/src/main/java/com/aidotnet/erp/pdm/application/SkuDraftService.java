package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.ProductStatus;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.domain.Spu;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
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

/** SKU 草稿维护用例；启停与完整业务引用校验由后续生命周期用例承接。 */
@Service
public class SkuDraftService {
    private final ProductStore store;
    private final ProductService products;

    public SkuDraftService(ProductStore store, ProductService products) {
        this.store = store;
        this.products = products;
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
                clean(command.title()), null, null, null, clean(command.baseUnit())));
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
        return store.querySkus(tenant, clean(keyword), clean(spuId), state).stream()
                .map(sku -> view(sku, parents.get(sku.spuId()))).toList();
    }

    public SkuDraftView get(String tenant, String skuId) {
        Sku sku = requireSku(tenant, skuId);
        return view(sku, products.getSpu(tenant, sku.spuId()));
    }

    @Transactional
    public SkuDraftView update(String tenant, String skuId, UpdateDraft command) {
        Sku sku = store.lockSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
        if (sku.status() != ProductStatus.DRAFT) {
            throw new BizException("SKU_NOT_EDITABLE", "本入口仅允许编辑草稿 SKU");
        }
        // 本切片尚未交付全域引用证明，因此不开放编码、父级或既有单位口径变更。
        if ((command.sellerSku() != null && !SkuCode.normalize(command.sellerSku()).equals(SkuCode.normalize(sku.sellerSku())))
                || (command.spuId() != null && !command.spuId().equals(sku.spuId()))) {
            throw new BizException("SKU_IDENTITY_LOCKED", "本次编辑保留本地 SKU 编码与所属 SPU");
        }
        String unit = command.baseUnit() == null ? sku.baseUnit() : clean(command.baseUnit());
        if (clean(sku.baseUnit()) != null && !Objects.equals(sku.baseUnit(), unit)) {
            throw new BizException("SKU_IDENTITY_LOCKED", "基本库存单位已确定，不能直接改变库存计量口径");
        }
        Sku updated = new Sku(sku.skuId(), tenant, sku.spuId(), sku.sellerSku(),
                command.title() == null ? sku.title() : clean(command.title()), unit,
                sku.weightKg(), sku.declaredValue(), sku.currency(), sku.status(), sku.createdAt(), Instant.now());
        store.saveSku(updated);
        return view(updated, products.getSpu(tenant, sku.spuId()));
    }

    private Sku requireSku(String tenant, String skuId) {
        return store.findSku(tenant, skuId).orElseThrow(() -> new BizException("SKU_NOT_FOUND", "SKU 不存在"));
    }

    private SkuDraftView view(Sku sku, Spu spu) {
        List<String> missing = new ArrayList<>();
        if (clean(sku.title()) == null) missing.add("SKU 名称");
        if (spu == null || clean(spu.categoryId()) == null) missing.add("SPU 分类");
        if (clean(sku.baseUnit()) == null) missing.add("基本库存单位");
        return new SkuDraftView(sku.skuId(), sku.spuId(), sku.sellerSku(), sku.title(), sku.baseUnit(),
                sku.status(), spu == null ? null : spu.sku(), spu == null ? null : spu.title(),
                spu == null ? null : spu.categoryId(), spu == null ? null : spu.brandId(),
                sku.weightKg(), sku.declaredValue(), sku.currency(),
                sku.status() == ProductStatus.DRAFT, List.copyOf(missing), sku.createdAt(), sku.updatedAt());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record NewSpu(String sku, String title, String description, String categoryId, String brandId) {}
    public record CreateDraft(String spuId, String sellerSku, String title, String baseUnit, NewSpu newSpu) {}
    public record UpdateDraft(String title, String baseUnit, String sellerSku, String spuId) {}
    public record SkuDraftView(String skuId, String spuId, String sellerSku, String title, String baseUnit,
                               ProductStatus status, String spuCode, String spuTitle, String categoryId, String brandId,
                               BigDecimal weightKg, BigDecimal declaredValue, String currency,
                               boolean editable, List<String> missingFields, Instant createdAt, Instant updatedAt) {}
}
