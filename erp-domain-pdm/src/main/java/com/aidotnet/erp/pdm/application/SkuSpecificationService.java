package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import static com.aidotnet.erp.pdm.domain.Specification.key;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.Specification.Item;
import com.aidotnet.erp.pdm.domain.Specification.Selection;
import com.aidotnet.erp.pdm.infrastructure.SkuHistoryStore;
import com.aidotnet.erp.pdm.infrastructure.SpecificationStore;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuSpecificationService {
    private final SpecificationStore store;
    private final SkuIdentityPolicy identities;
    private final SkuHistoryStore history;

    public SkuSpecificationService(SpecificationStore store, SkuIdentityPolicy identities, SkuHistoryStore history) {
        this.store = store;
        this.identities = identities;
        this.history = history;
    }

    public void lock(String tenant) {
        store.lock(tenant);
    }

    public List<Item> items(String tenant, String sku) {
        return store.items(tenant, sku);
    }

    public boolean needsReview(String tenant, String sku) {
        return store.needsReview(tenant, sku);
    }

    public void releaseKey(String tenant, String sku) {
        store.clearKey(tenant, sku);
    }

    /** 调用方已按租户、父级、编码、SKU 的顺序锁定身份；所有修改与商品写入同一事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(Sku sku, List<Selection> requested) {
        assign(sku, requested, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assign(Sku sku, List<Selection> requested, boolean recordHistory) {
        List<Item> before = items(sku.tenantId(), sku.skuId());
        List<Selection> previous = before.stream().map(i -> new Selection(i.attributeId(), i.valueId())).toList();
        if (requested != null && (requested.size() > 20
                || requested.stream().anyMatch(i -> i == null || i.attributeId() == null || i.valueId() == null))) {
            throw new BizException("SPEC_SELECTION_INVALID", "规格选择无效");
        }
        List<Selection> desired = requested == null ? previous : List.copyOf(requested);
        String key = key(desired);
        boolean changed = !Objects.equals(key, key(previous));
        if (changed && !identities.editableIdentity(sku)) {
            throw new BizException("SKU_IDENTITY_LOCKED", "规格属于商品身份，启用、引用或历史待核实后不能更换");
        }
        if (!desired.isEmpty()) {
            var range = store.options(sku.tenantId(), sku.spuId()).dimensions();
            var attrs = new HashSet<String>();
            for (Selection item : desired) {
                if (!attrs.add(item.attributeId())) {
                    throw new BizException("SPEC_ATTRIBUTE_DUPLICATED", "同一规格属性不能重复");
                }
                boolean allowed = range.stream().anyMatch(d -> d.attributeId().equals(item.attributeId())
                        && d.values().stream().anyMatch(v -> v.valueId().equals(item.valueId())));
                if (!allowed) {
                    throw new BizException("SPEC_VALUE_NOT_ALLOWED", "规格属性或值不在本 SPU 允许范围内");
                }
            }
            if (desired.size() != range.size()) {
                throw new BizException("SPEC_COMBINATION_INCOMPLETE", "请选择 SPU 每个规格维度的一个值");
            }
        }
        String conflict = store.conflict(sku.tenantId(), sku.spuId(), key, sku.skuId());
        if (conflict != null) {
            throw new BizException("SPEC_COMBINATION_DUPLICATED", "组合已被 SKU 占用：" + conflict);
        }
        try {
            store.setItems(sku.tenantId(), sku.skuId(), key, desired);
        } catch (DuplicateKeyException error) {
            // PostgreSQL 唯一约束失败后当前事务已中止，不能再查询占用者。
            throw new BizException("SPEC_COMBINATION_DUPLICATED", "规格组合已被其他 SKU 占用，请刷新列表核对");
        }
        if (recordHistory && changed) {
            history.appendSpecification(sku.tenantId(), sku.skuId(),
                    Map.of("specifications", before),
                    Map.of("specifications", items(sku.tenantId(), sku.skuId())));
        }
    }

    public List<String> missing(Sku sku) {
        if (needsReview(sku.tenantId(), sku.skuId())) {
            return List.of("历史自由文本规格待核实");
        }
        var range = store.options(sku.tenantId(), sku.spuId()).dimensions();
        var selections = items(sku.tenantId(), sku.skuId());
        boolean valid = range.size() == selections.size() && selections.stream().allMatch(item -> range.stream().anyMatch(d ->
                d.attributeId().equals(item.attributeId()) && d.values().stream().anyMatch(v -> v.valueId().equals(item.valueId()))));
        return valid ? List.of() : List.of("完整且有效的规格组合");
    }

}
