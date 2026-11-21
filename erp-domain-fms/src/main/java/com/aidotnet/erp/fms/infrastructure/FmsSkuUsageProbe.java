package com.aidotnet.erp.fms.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class FmsSkuUsageProbe extends SqlSkuUsageProbe {
    public FmsSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "fms",
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.cost_event_id AS VARCHAR) AS reference_id FROM fms_cost_event t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.summary_id AS VARCHAR) AS reference_id FROM fms_profit_summary t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.statement_id AS VARCHAR) AS reference_id FROM fms_profit_statement t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.bill_id AS VARCHAR) AS reference_id FROM fms_platform_bill t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.alert_id AS VARCHAR) AS reference_id FROM fms_profit_deviation_alert t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.anomaly_id AS VARCHAR) AS reference_id FROM fms_cost_anomaly t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("财务记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.result_id AS VARCHAR) AS reference_id FROM fms_profit_result t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
