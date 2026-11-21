CREATE TABLE IF NOT EXISTS pdm_sku_details (
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    details_json JSONB NOT NULL DEFAULT '{}',
    updated_by VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,sku_id),
    FOREIGN KEY(tenant_id,sku_id) REFERENCES pdm_sku(tenant_id,sku_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS pdm_image_asset (
    tenant_id VARCHAR(64) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes>0),
    width_px INT NOT NULL CHECK (width_px>0),
    height_px INT NOT NULL CHECK (height_px>0),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,asset_id)
);
