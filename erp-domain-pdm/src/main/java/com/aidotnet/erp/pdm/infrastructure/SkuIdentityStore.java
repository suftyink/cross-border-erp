package com.aidotnet.erp.pdm.infrastructure;

import com.aidotnet.erp.common.exception.BizException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 本域的编码互斥记录，序列化改码/删除与新引用，并保留删除后的旧编码边界。 */
@Repository
public class SkuIdentityStore {
    private final JdbcTemplate jdbc;

    public SkuIdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<String> lockCodes(String tenant, Collection<String> codes) {
        if (tenant == null || tenant.isBlank()) throw new BizException("TENANT_REQUIRED", "租户不能为空");
        List<String> ordered = codes.stream().filter(Objects::nonNull).map(SkuIdentityStore::normalize)
                .filter(code -> !code.isEmpty()).distinct().sorted().toList();
        for (String code : ordered) {
            jdbc.update("INSERT INTO pdm_sku_code_guard(tenant_id,normalized_code) VALUES (?,?) ON CONFLICT DO NOTHING", tenant, code);
            jdbc.queryForObject("SELECT normalized_code FROM pdm_sku_code_guard WHERE tenant_id=? AND normalized_code=? FOR UPDATE", String.class, tenant, code);
        }
        return ordered;
    }

    public boolean referenced(String tenant, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COALESCE(bool_or(referenced),FALSE) FROM pdm_sku_code_guard WHERE tenant_id=? AND normalized_code=?", Boolean.class, tenant, normalize(code)));
    }

    public void claim(String tenant, String code, String skuId) {
        jdbc.update("UPDATE pdm_sku_code_guard SET sku_id=?,retired=FALSE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND normalized_code=?", skuId, tenant, normalize(code));
    }

    public void reference(String tenant, String code) {
        Boolean retired = jdbc.queryForObject("SELECT retired FROM pdm_sku_code_guard WHERE tenant_id=? AND normalized_code=?", Boolean.class, tenant, normalize(code));
        if (Boolean.TRUE.equals(retired)) throw new BizException("SKU_REFERENCE_RETIRED", "商品编码已变更或删除，请重新选择商品");
        jdbc.update("UPDATE pdm_sku_code_guard SET referenced=TRUE,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND normalized_code=?", tenant, normalize(code));
        jdbc.update("UPDATE pdm_sku SET identity_locked=TRUE WHERE tenant_id=? AND UPPER(TRIM(seller_sku))=?", tenant, normalize(code));
    }

    public void retire(String tenant, String code, String skuId) {
        jdbc.update("UPDATE pdm_sku_code_guard SET retired=TRUE,sku_id=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND normalized_code=?", skuId, tenant, normalize(code));
    }

    public boolean deletedId(String tenant, String skuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_sku_deleted_identity WHERE tenant_id=? AND sku_id=?)", Boolean.class, tenant, skuId));
    }

    public boolean belongsToAnotherTenant(String tenant, String skuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_sku WHERE sku_id=? AND tenant_id<>?)", Boolean.class, skuId, tenant));
    }

    public void deleted(String tenant, String skuId, String code) {
        jdbc.update("INSERT INTO pdm_sku_deleted_identity(tenant_id,sku_id,last_code) VALUES (?,?,?)", tenant, skuId, code);
    }

    public void syncVariant(com.aidotnet.erp.pdm.domain.Sku sku) {
        jdbc.update("UPDATE pdm_product_variant SET spu_id=?,seller_sku=?,status=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND canonical_sku_id=?",
                sku.spuId(), sku.sellerSku(), sku.status().name(), sku.status()==com.aidotnet.erp.pdm.domain.ProductStatus.ACTIVE, sku.tenantId(), sku.skuId());
    }

    public boolean variantNeedsReview(String tenant, String variantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_product_variant v LEFT JOIN pdm_sku s ON s.tenant_id=v.tenant_id AND s.sku_id=v.canonical_sku_id WHERE v.tenant_id=? AND v.variant_id=? AND (s.sku_id IS NULL OR v.spu_id IS DISTINCT FROM s.spu_id OR (NULLIF(TRIM(v.seller_sku),'') IS NOT NULL AND v.seller_sku IS DISTINCT FROM s.seller_sku) OR (NULLIF(TRIM(v.sku_id),'') IS NOT NULL AND v.sku_id IS DISTINCT FROM s.sku_id)))", Boolean.class, tenant, variantId));
    }

    public boolean hasVariant(String tenant, String skuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_product_variant WHERE tenant_id=? AND canonical_sku_id=?)", Boolean.class, tenant, skuId));
    }

    public void deleteDraftVariant(String tenant, String skuId) {
        jdbc.update("DELETE FROM pdm_product_variant WHERE tenant_id=? AND canonical_sku_id=?", tenant, skuId);
    }

    private static String normalize(String value) { return value.strip().toUpperCase(Locale.ROOT); }
}
