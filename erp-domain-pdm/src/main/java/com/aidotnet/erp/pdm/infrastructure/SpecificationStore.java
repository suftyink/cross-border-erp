package com.aidotnet.erp.pdm.infrastructure;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.Specification.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpecificationStore {
    private final JdbcTemplate jdbc;
    public SpecificationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 所有规格写操作先取租户锁，再锁 SPU、编码和 SKU，避免范围与组合并发失配。 */
    public void lock(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new BizException("TENANT_REQUIRED", "租户不能为空");
        }
        jdbc.update("INSERT INTO pdm_spec_lock(tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        jdbc.queryForObject("SELECT tenant_id FROM pdm_spec_lock WHERE tenant_id=? FOR UPDATE", String.class, tenant);
    }

    public List<Attribute> attributes(String tenant) {
        return jdbc.query("SELECT * FROM pdm_spec_attribute WHERE tenant_id=? ORDER BY code", (row, index) ->
                new Attribute(row.getString("attribute_id"),row.getString("code"),row.getString("name"),row.getString("description"),
                        values(tenant,row.getString("attribute_id"))), tenant);
    }

    public Attribute attribute(String tenant, String id) {
        return attributes(tenant).stream().filter(a -> a.attributeId().equals(id)).findFirst()
                .orElseThrow(() -> new BizException("SPEC_ATTRIBUTE_NOT_FOUND", "规格属性不存在或不属于本租户"));
    }

    public List<Value> values(String tenant, String attr) {
        return jdbc.query("SELECT * FROM pdm_spec_value WHERE tenant_id=? AND attribute_id=? ORDER BY code", (row,index) ->
                new Value(row.getString("value_id"),attr,row.getString("code"),row.getString("name"),row.getString("description")),tenant,attr);
    }

    public Attribute createAttribute(String tenant, String code, String name, String description) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pdm_spec_attribute(tenant_id,attribute_id,code,name,description) VALUES (?,?,?,?,?)",tenant,id,code,name,description);
        return attribute(tenant,id);
    }
    public void updateAttribute(String tenant, String id, String name, String description) {
        jdbc.update("UPDATE pdm_spec_attribute SET name=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND attribute_id=?",name,description,tenant,id);
    }
    public Value createValue(String tenant, String attr, String code, String name, String description) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pdm_spec_value(tenant_id,attribute_id,value_id,code,name,description) VALUES (?,?,?,?,?,?)",tenant,attr,id,code,name,description);
        return new Value(id,attr,code,name,description);
    }
    public void updateValue(String tenant, String id, String name, String description) {
        jdbc.update("UPDATE pdm_spec_value SET name=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND value_id=?",name,description,tenant,id);
    }
    public boolean used(String tenant, String attr, String value) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pdm_spu_spec_value WHERE tenant_id=? AND attribute_id=? AND (CAST(? AS VARCHAR) IS NULL OR value_id=?)) OR EXISTS(SELECT 1 FROM pdm_sku_spec_value WHERE tenant_id=? AND attribute_id=? AND (CAST(? AS VARCHAR) IS NULL OR value_id=?))", Boolean.class,tenant,attr,value,value,tenant,attr,value,value));
    }
    public void deleteValue(String tenant, String id) { jdbc.update("DELETE FROM pdm_spec_value WHERE tenant_id=? AND value_id=?",tenant,id); }
    public void deleteAttribute(String tenant, String id) {
        jdbc.update("DELETE FROM pdm_spec_value WHERE tenant_id=? AND attribute_id=?",tenant,id);
        jdbc.update("DELETE FROM pdm_spec_attribute WHERE tenant_id=? AND attribute_id=?",tenant,id);
    }

    public Options options(String tenant, String spu) {
        List<Dimension> dimensions = jdbc.query("SELECT DISTINCT a.attribute_id,a.code,a.name FROM pdm_spu_spec_value r JOIN pdm_spec_attribute a ON a.tenant_id=r.tenant_id AND a.attribute_id=r.attribute_id WHERE r.tenant_id=? AND r.spu_id=? ORDER BY a.code",(row,index) -> {
            String attr=row.getString("attribute_id");
            List<String> ids=jdbc.queryForList("SELECT value_id FROM pdm_spu_spec_value WHERE tenant_id=? AND spu_id=? AND attribute_id=?",String.class,tenant,spu,attr);
            return new Dimension(attr,row.getString("code"),row.getString("name"),values(tenant,attr).stream().filter(v -> ids.contains(v.valueId())).toList());
        },tenant,spu);
        return new Options(spu,dimensions);
    }
    public void setOptions(String tenant, String spu, List<Range> ranges) {
        jdbc.update("DELETE FROM pdm_spu_spec_value WHERE tenant_id=? AND spu_id=?", tenant, spu);
        for (Range range : ranges) {
            for (String value : range.valueIds()) {
                jdbc.update("INSERT INTO pdm_spu_spec_value(tenant_id,spu_id,attribute_id,value_id) VALUES (?,?,?,?)",
                        tenant, spu, range.attributeId(), value);
            }
        }
    }
    public List<Item> items(String tenant, String sku) {
        return jdbc.query("SELECT a.attribute_id,a.code AS ac,a.name AS an,v.value_id,v.code AS vc,v.name AS vn FROM pdm_sku_spec_value s JOIN pdm_spec_attribute a ON a.tenant_id=s.tenant_id AND a.attribute_id=s.attribute_id JOIN pdm_spec_value v ON v.tenant_id=s.tenant_id AND v.attribute_id=s.attribute_id AND v.value_id=s.value_id WHERE s.tenant_id=? AND s.sku_id=? ORDER BY a.code", (r,i) -> new Item(r.getString("attribute_id"),r.getString("ac"),r.getString("an"),r.getString("value_id"),r.getString("vc"),r.getString("vn")),tenant,sku);
    }
    public boolean needsReview(String tenant, String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT specification_review_required FROM pdm_sku WHERE tenant_id=? AND sku_id=?",Boolean.class,tenant,sku));
    }
    public String conflict(String tenant, String spu, String key, String exceptId) {
        if (key == null) {
            return null;
        }
        return jdbc.query("SELECT sku_id,seller_sku FROM pdm_sku WHERE tenant_id=? AND spu_id=? AND specification_key=? AND sku_id<>?",(r,i) -> r.getString("seller_sku")+" ("+r.getString("sku_id")+")",tenant,spu,key,exceptId).stream().findFirst().orElse(null);
    }
    public void setItems(String tenant, String sku, String key, List<Selection> items) {
        jdbc.update("UPDATE pdm_sku SET specification_key=? WHERE tenant_id=? AND sku_id=?",key,tenant,sku);
        jdbc.update("DELETE FROM pdm_sku_spec_value WHERE tenant_id=? AND sku_id=?",tenant,sku);
        for (Selection item : items) {
            jdbc.update("INSERT INTO pdm_sku_spec_value(tenant_id,sku_id,attribute_id,value_id) VALUES (?,?,?,?)",
                    tenant, sku, item.attributeId(), item.valueId());
        }
    }
    public void clearKey(String tenant,String sku) { jdbc.update("UPDATE pdm_sku SET specification_key=NULL WHERE tenant_id=? AND sku_id=?",tenant,sku); }
}
