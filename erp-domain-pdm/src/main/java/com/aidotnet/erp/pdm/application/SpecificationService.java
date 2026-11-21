package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.Specification.Attribute;
import com.aidotnet.erp.pdm.domain.Specification.Item;
import com.aidotnet.erp.pdm.domain.Specification.Options;
import com.aidotnet.erp.pdm.domain.Specification.Range;
import com.aidotnet.erp.pdm.domain.Specification.Value;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SpecificationStore;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecificationService {
    private final SpecificationStore store;
    private final ProductStore products;
    private final SkuIdentityPolicy identities;

    public SpecificationService(SpecificationStore store, ProductStore products, SkuIdentityPolicy identities) {
        this.store = store;
        this.products = products;
        this.identities = identities;
    }

    public List<Attribute> list(String tenant) {
        return store.attributes(tenant);
    }

    @Transactional
    public Attribute createAttribute(String tenant, Dictionary command) {
        store.lock(tenant);
        try {
            return store.createAttribute(tenant, code(command.code()), name(command.name()), description(command.description()));
        } catch (DuplicateKeyException error) {
            throw new BizException("SPEC_CODE_DUPLICATED", "规格属性编码已存在");
        }
    }

    @Transactional
    public Attribute updateAttribute(String tenant, String id, Dictionary command) {
        store.lock(tenant);
        Attribute old = store.attribute(tenant, id);
        immutable(old.code(), command.code());
        store.updateAttribute(tenant, id, name(command.name()), description(command.description()));
        return store.attribute(tenant, id);
    }

    @Transactional
    public Value createValue(String tenant, String attr, Dictionary command) {
        store.lock(tenant);
        store.attribute(tenant, attr);
        try {
            return store.createValue(tenant, attr, code(command.code()), name(command.name()), description(command.description()));
        } catch (DuplicateKeyException error) {
            throw new BizException("SPEC_CODE_DUPLICATED", "该属性下规格值编码已存在");
        }
    }

    @Transactional
    public Value updateValue(String tenant, String attr, String id, Dictionary command) {
        store.lock(tenant);
        Value old = value(tenant, attr, id);
        if (command.attributeId() != null && !attr.equals(command.attributeId())) {
            throw new BizException("SPEC_IDENTITY_LOCKED", "规格值不能改挂到其他属性");
        }
        immutable(old.code(), command.code());
        store.updateValue(tenant, id, name(command.name()), description(command.description()));
        return value(tenant, attr, id);
    }

    @Transactional
    public void deleteValue(String tenant, String attr, String id) {
        store.lock(tenant);
        value(tenant, attr, id);
        if (store.used(tenant, attr, id)) {
            throw new BizException("SPEC_REFERENCED", "规格值已被 SPU 范围或 SKU 引用，不能删除");
        }
        store.deleteValue(tenant, id);
    }

    @Transactional
    public void deleteAttribute(String tenant, String attr) {
        store.lock(tenant);
        store.attribute(tenant, attr);
        if (store.used(tenant, attr, null)) {
            throw new BizException("SPEC_REFERENCED", "规格属性已被 SPU 范围或 SKU 引用，不能删除");
        }
        store.deleteAttribute(tenant, attr);
    }

    public Options options(String tenant, String spu) {
        requireParent(tenant, spu);
        return store.options(tenant, spu);
    }

    @Transactional
    public Options configure(String tenant, String spu, List<Range> ranges) {
        store.lock(tenant);
        products.lockSpu(tenant, spu).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        var attrs = new HashSet<String>();
        for (Range range : ranges) {
            if (!attrs.add(range.attributeId())) {
                throw new BizException("SPEC_ATTRIBUTE_DUPLICATED", "同一属性只能选择一次");
            }
            store.attribute(tenant, range.attributeId());
            if (range.valueIds().isEmpty() || new HashSet<>(range.valueIds()).size() != range.valueIds().size()) {
                throw new BizException("SPEC_VALUES_INVALID", "规格范围必须选择值且不能重复");
            }
            for (String value : range.valueIds()) {
                value(tenant, range.attributeId(), value);
            }
        }
        // 先保护已声明组合；空的未使用草稿可以之后补规格，锁定的普通 SKU 不能被父级改成多规格。
        for (var observed : products.listSkus(tenant, spu).stream().sorted(Comparator.comparing(Sku::sellerSku)).toList()) {
            var sku = identities.lock(tenant, observed.skuId(), null);
            var items = store.items(tenant, sku.skuId());
            boolean locked = !identities.editableIdentity(sku);
            if ((!items.isEmpty() || locked) && !fits(items, ranges)) {
                throw new BizException("SPEC_RANGE_IN_USE", "规格范围变更会使现有 SKU 非法：" + sku.sellerSku() + " (" + sku.skuId() + ")");
            }
        }
        store.setOptions(tenant, spu, ranges);
        return store.options(tenant, spu);
    }

    private boolean fits(List<Item> items, List<Range> ranges) {
        return items.size() == ranges.size() && items.stream().allMatch(item -> ranges.stream().anyMatch(range ->
                range.attributeId().equals(item.attributeId()) && range.valueIds().contains(item.valueId())));
    }

    private Value value(String tenant, String attr, String id) {
        return store.attribute(tenant, attr).values().stream().filter(v -> v.valueId().equals(id)).findFirst()
                .orElseThrow(() -> new BizException("SPEC_VALUE_NOT_FOUND", "规格值不存在、不属于所选属性或不属于本租户"));
    }

    private void requireParent(String tenant, String id) {
        products.findSpu(tenant, id).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
    }

    private void immutable(String old, String candidate) {
        if (candidate != null && !Objects.equals(old, code(candidate))) {
            throw new BizException("SPEC_IDENTITY_LOCKED", "稳定编码不能变更；只能维护显示名和说明");
        }
    }

    private String code(String code) {
        String clean = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!clean.matches("[A-Z0-9_-]{1,64}")) {
            throw new BizException("SPEC_CODE_INVALID", "规格编码仅支持 1 至 64 位字母、数字、连字符和下划线");
        }
        return clean;
    }

    private String name(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("SPEC_NAME_REQUIRED", "显示名称不能为空");
        }
        return name.strip();
    }

    private String description(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record Dictionary(String code, String name, String description, String attributeId) {}
}
