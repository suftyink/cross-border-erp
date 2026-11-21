package com.aidotnet.erp.pdm.infrastructure;

import com.aidotnet.erp.pdm.domain.SkuGeneration.Existing;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SkuGenerationStore {
    private final JdbcTemplate jdbc;
    public SkuGenerationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Existing> existing(String tenant, String spu) {
        return jdbc.query("""
                SELECT specification_key,sku_id,seller_sku,status FROM pdm_sku
                WHERE tenant_id=? AND spu_id=? AND specification_key IS NOT NULL
                """, (row, index) -> Map.entry(row.getString("specification_key"),
                new Existing(row.getString("sku_id"), row.getString("seller_sku"), row.getString("status"))), tenant, spu)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void save(String tenant, String spu, String actor, String id, String fingerprint, String previewJson, Instant expires) {
        jdbc.update("""
                INSERT INTO pdm_sku_generation_preview(preview_id,tenant_id,spu_id,actor_id,fingerprint,preview_json,expires_at)
                VALUES (?,?,?,?,?,CAST(? AS JSONB),?)
                """, id, tenant, spu, actor, fingerprint, previewJson, Timestamp.from(expires));
    }

    public Optional<Stored> lock(String tenant, String spu, String actor, String id) {
        return jdbc.query("""
                SELECT * FROM pdm_sku_generation_preview
                WHERE tenant_id=? AND spu_id=? AND actor_id=? AND preview_id=? FOR UPDATE
                """, (row, index) -> new Stored(row.getString("fingerprint"), row.getString("preview_json"),
                row.getTimestamp("expires_at").toInstant(), row.getString("request_hash"), row.getString("result_json")),
                tenant, spu, actor, id).stream().findFirst();
    }

    public void complete(String tenant, String id, String hash, String resultJson) {
        jdbc.update("""
                UPDATE pdm_sku_generation_preview SET request_hash=?,result_json=CAST(? AS JSONB),committed_at=CURRENT_TIMESTAMP
                WHERE tenant_id=? AND preview_id=?
                """, hash, resultJson, tenant, id);
    }

    public record Stored(String fingerprint, String previewJson, Instant expiresAt, String requestHash, String resultJson) {}
}
