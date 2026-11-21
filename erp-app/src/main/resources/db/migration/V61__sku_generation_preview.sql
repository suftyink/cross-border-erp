-- 技术预览与确认回执，不代表已经创建商品。确认结果与 SKU 创建同事务写入。
CREATE TABLE IF NOT EXISTS pdm_sku_generation_preview (
    preview_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    spu_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    preview_json JSONB NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    request_hash VARCHAR(64),
    result_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at TIMESTAMP,
    CHECK ((request_hash IS NULL) = (result_json IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_pdm_sku_generation_owner ON pdm_sku_generation_preview(tenant_id,actor_id,created_at);
