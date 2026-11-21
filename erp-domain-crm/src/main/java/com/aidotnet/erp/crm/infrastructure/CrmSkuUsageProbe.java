package com.aidotnet.erp.crm.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class CrmSkuUsageProbe extends SqlSkuUsageProbe {
    public CrmSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "crm",
                new Source("售后记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.return_id AS VARCHAR) AS reference_id FROM crm_return_refund t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("售后记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.analysis_id AS VARCHAR) AS reference_id FROM crm_review_analysis t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("售后记录", true, "SELECT t.sku_id AS product_key, CAST(t.review_id AS VARCHAR) AS reference_id FROM crm_review t WHERE t.tenant_id=:tenantId AND t.sku_id IN (:keys)"),
                new Source("售后记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM crm_batch_recall_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("售后记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.recall_id AS VARCHAR) AS reference_id FROM crm_batch_recall_source t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("售后记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.return_id AS VARCHAR) AS reference_id FROM crm_sales_return t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
