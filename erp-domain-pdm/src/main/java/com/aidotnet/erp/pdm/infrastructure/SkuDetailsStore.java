package com.aidotnet.erp.pdm.infrastructure;

import com.aidotnet.erp.common.pdm.ProductData;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.pdm.domain.Sku;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SkuDetailsStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SkuDetailsStore(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public ProductData find(Sku sku) {
        ProductData extra=jdbc.query("SELECT details_json::text FROM pdm_sku_details WHERE tenant_id=? AND sku_id=?",(row,index) -> decode(row.getString(1)),sku.tenantId(),sku.skuId())
                .stream().findFirst().orElse(ProductData.empty());
        return extra.withCore(sku.weightKg(),sku.declaredValue(),sku.currency());
    }
    public void save(String tenant,String sku,ProductData details) {
        ObjectNode extra=json.valueToTree(details);
        extra.remove(List.of("weightKg","declaredValue","currency")); // 保留旧 pdm_sku 列作为这三个字段的唯一事实来源。
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        String actor=authentication!=null && authentication.getDetails() instanceof ErpUserDetails user?user.userId():"system";
        jdbc.update("""
                INSERT INTO pdm_sku_details(tenant_id,sku_id,details_json,updated_by) VALUES (?,?,CAST(? AS JSONB),?)
                ON CONFLICT(tenant_id,sku_id) DO UPDATE SET details_json=EXCLUDED.details_json,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """,tenant,sku,extra.toString(),actor);
    }
    public boolean ownsImage(String tenant,String asset) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_image_asset WHERE tenant_id=? AND asset_id=?)",Boolean.class,tenant,asset));
    }
    private ProductData decode(String value) {
        try { return json.readValue(value,ProductData.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("商品资料记录无法读取",error); }
    }
}
