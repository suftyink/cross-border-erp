package com.aidotnet.erp.fba.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class FbaSkuUsageProbe extends SqlSkuUsageProbe {
    public FbaSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "fba",
                new Source("FBA记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.label_id AS VARCHAR) AS reference_id FROM fba_carton_label t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("FBA记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.plan_id AS VARCHAR) AS reference_id FROM fba_replenishment_plan t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("FBA记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.plan_id AS VARCHAR) AS reference_id FROM fba_inbound_plan t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("FBA货件", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.item_id AS VARCHAR) AS reference_id FROM fba_shipment_item t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("FBA记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.inventory_id AS VARCHAR) AS reference_id FROM fba_inventory t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
