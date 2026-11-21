package com.aidotnet.erp.scm.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class ScmSkuUsageProbe extends SqlSkuUsageProbe {
    public ScmSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "scm",
                new Source("采购记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.po_id AS VARCHAR) AS reference_id FROM scm_purchase_order t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("采购单", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM scm_purchase_order_line t JOIN scm_purchase_order p ON p.po_id=t.po_id WHERE p.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("采购计划", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.line_id AS VARCHAR) AS reference_id FROM scm_purchase_plan_line t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("采购记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.suggestion_id AS VARCHAR) AS reference_id FROM scm_replenishment_suggestion t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("供应商报价", true, "SELECT t.sku_id AS product_key, CAST(t.quote_id AS VARCHAR) AS reference_id FROM scm_quote t WHERE t.tenant_id=:tenantId AND t.sku_id IN (:keys)"),
                new Source("供应商报价", false, "SELECT UPPER(TRIM(COALESCE(NULLIF(to_jsonb(t)->>'seller_sku',''),t.sku_id))) AS product_key, CAST(t.quote_id AS VARCHAR) AS reference_id FROM scm_quote t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(COALESCE(NULLIF(to_jsonb(t)->>'seller_sku',''),t.sku_id))) IN (:keys)"),
                new Source("加工产出", false, "SELECT UPPER(TRIM(to_jsonb(t)->>'output_sku')) AS product_key, COALESCE(to_jsonb(t)->>'process_id',to_jsonb(t)->>'processing_id') AS reference_id FROM scm_processing_order t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(to_jsonb(t)->>'output_sku')) IN (:keys)"),
                new Source("加工原料", false, "SELECT UPPER(TRIM(m->>'sellerSku')) AS product_key, COALESCE(to_jsonb(t)->>'process_id',to_jsonb(t)->>'processing_id') AS reference_id FROM scm_processing_order t CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(COALESCE(NULLIF(to_jsonb(t)->>'raw_materials',''),'[]')::jsonb)='array' THEN COALESCE(NULLIF(to_jsonb(t)->>'raw_materials',''),'[]')::jsonb ELSE '[]'::jsonb END) m WHERE t.tenant_id=:tenantId AND UPPER(TRIM(m->>'sellerSku')) IN (:keys)"),
                new Source("采购记录", true, "SELECT t.product_sku_id AS product_key, CAST(t.processing_id AS VARCHAR) AS reference_id FROM scm_processing_order t WHERE t.tenant_id=:tenantId AND t.product_sku_id IN (:keys)"),
                new Source("采购记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.tracking_id AS VARCHAR) AS reference_id FROM scm_purchase_tracking t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"),
                new Source("采购记录", false, "SELECT UPPER(TRIM(t.seller_sku)) AS product_key, CAST(t.exception_id AS VARCHAR) AS reference_id FROM scm_purchase_exception t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.seller_sku)) IN (:keys)"));
    }
}
