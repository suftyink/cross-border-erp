package com.aidotnet.erp.som.infrastructure;

import com.aidotnet.erp.common.pdm.SqlSkuUsageProbe;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本域解释的商品历史引用；不按单据状态或余额过滤。 */
@Component
public class SomSkuUsageProbe extends SqlSkuUsageProbe {
    public SomSkuUsageProbe(JdbcTemplate jdbc) {
        super(jdbc, "som",
                new Source("渠道配对", false, "SELECT UPPER(TRIM(t.product_sku)) AS product_key, CAST(t.channel_sku_id AS VARCHAR) AS reference_id FROM som_channel_sku t WHERE t.tenant_id=:tenantId AND UPPER(TRIM(t.product_sku)) IN (:keys)"));
    }
}
