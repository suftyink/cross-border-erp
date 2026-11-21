package com.aidotnet.erp.pdm.infrastructure;

import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.domain.Specification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SkuHistoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SkuHistoryStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void append(String tenant, String skuId, String action, Sku before, Sku after, String reason) {
        appendObjects(tenant, skuId, action, before, after, reason);
    }

    public void appendCreate(String tenant, String skuId, Sku created, List<Specification.Item> specifications) {
        LinkedHashMap<String, Object> snapshot = json.convertValue(created, new TypeReference<>() {});
        if (specifications != null && !specifications.isEmpty()) {
            snapshot.put("specifications", specifications);
        }
        appendObjects(tenant, skuId, "CREATE", null, snapshot, null);
    }

    public void appendSpecification(String tenant, String skuId, Object before, Object after) {
        appendObjects(tenant, skuId, "SPECIFICATION", before, after, null);
    }

    private void appendObjects(String tenant,String skuId,String action,Object before,Object after,String reason) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String actor = authentication != null && authentication.getDetails() instanceof ErpUserDetails user ? user.userId() : "system";
        jdbc.update("INSERT INTO pdm_sku_history(event_id,tenant_id,sku_id,action,actor_id,reason,before_json,after_json) VALUES (?,?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB))",
                UUID.randomUUID().toString(), tenant, skuId, action, actor, reason, encode(before), encode(after));
    }

    public List<Entry> list(String tenant, String skuId) {
        return jdbc.query("SELECT * FROM pdm_sku_history WHERE tenant_id=? AND sku_id=? ORDER BY event_order", (row, index) ->
                new Entry(row.getString("event_id"), row.getString("action"), row.getString("actor_id"), row.getString("reason"),
                        decode(row.getString("before_json")), decode(row.getString("after_json")), row.getTimestamp("occurred_at").toInstant()), tenant, skuId);
    }

    private String encode(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException("无法记录商品变更", error); }
    }

    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return json.readTree(value); } catch (JsonProcessingException error) { throw new IllegalStateException("商品变更记录无效", error); }
    }

    public record Entry(String eventId, String action, String actorId, String reason, JsonNode before, JsonNode after, Instant occurredAt) {}
}
