package com.aidotnet.erp.pdm.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只列出有证据的历史问题，不把未知状态猜成从未启用。 */
@Repository
public class SkuMigrationIssueStore {
    private final JdbcTemplate jdbc;
    public SkuMigrationIssueStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Issue> list(String tenant) {
        String sql = """
                SELECT 'SKU' AS kind,sku_id AS id,seller_sku AS code,
                  CASE WHEN status NOT IN ('DRAFT','ACTIVE','INACTIVE') THEN '历史状态需核实'
                       WHEN ever_enabled IS NULL THEN '缺少历史启用和引用证据，身份保持锁定'
                       WHEN seller_sku !~ '^[A-Z0-9_-]+$' THEN '历史本地编码未规范化，需核实关联单据'
                       WHEN ever_enabled IS TRUE AND (base_unit IS NULL OR TRIM(base_unit)='') THEN '已使用商品的基本单位未知，需核实原计量口径'
                       WHEN status='ACTIVE' AND ever_enabled IS NOT TRUE THEN '启用状态与历史事实不一致'
                       ELSE '历史身份需核实' END AS message
                FROM pdm_sku WHERE tenant_id=? AND (status NOT IN ('DRAFT','ACTIVE','INACTIVE') OR ever_enabled IS NULL
                  OR seller_sku !~ '^[A-Z0-9_-]+$' OR (ever_enabled IS TRUE AND (base_unit IS NULL OR TRIM(base_unit)=''))
                  OR (status='ACTIVE' AND ever_enabled IS NOT TRUE))
                UNION ALL
                SELECT 'VARIANT',v.variant_id,v.seller_sku,
                  CASE WHEN v.canonical_sku_id IS NULL THEN '旧变体无法证明唯一对应的库存 SKU'
                       WHEN s.sku_id IS NULL THEN '旧变体关联的库存 SKU 不存在或不属于本租户'
                       WHEN v.spu_id<>s.spu_id THEN '旧变体与 SKU 的所属 SPU 不一致'
                       ELSE '旧变体状态或编码与库存 SKU 不一致；实际使用以库存 SKU 为准' END
                FROM pdm_product_variant v LEFT JOIN pdm_sku s ON s.tenant_id=v.tenant_id AND s.sku_id=v.canonical_sku_id
                WHERE v.tenant_id=? AND (v.canonical_sku_id IS NULL OR s.sku_id IS NULL OR v.spu_id<>s.spu_id
                  OR v.status IS DISTINCT FROM s.status OR v.seller_sku IS DISTINCT FROM s.seller_sku)
                ORDER BY kind,id
                """;
        return jdbc.query(sql, (row, index) -> new Issue(row.getString("kind"), row.getString("id"), row.getString("code"), row.getString("message")), tenant, tenant);
    }

    public record Issue(String kind, String id, String code, String message) {}
}
