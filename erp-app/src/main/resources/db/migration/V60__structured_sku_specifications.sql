CREATE TABLE IF NOT EXISTS pdm_spec_lock (
    tenant_id VARCHAR(64) PRIMARY KEY
);
CREATE TABLE IF NOT EXISTS pdm_spec_attribute (
    tenant_id VARCHAR(64) NOT NULL,
    attribute_id VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,attribute_id), UNIQUE(tenant_id,code)
);
CREATE TABLE IF NOT EXISTS pdm_spec_value (
    tenant_id VARCHAR(64) NOT NULL,
    attribute_id VARCHAR(64) NOT NULL,
    value_id VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id,value_id), UNIQUE(tenant_id,attribute_id,code), UNIQUE(tenant_id,attribute_id,value_id),
    FOREIGN KEY(tenant_id,attribute_id) REFERENCES pdm_spec_attribute(tenant_id,attribute_id)
);
CREATE TABLE IF NOT EXISTS pdm_spu_spec_value (
    tenant_id VARCHAR(64) NOT NULL,
    spu_id VARCHAR(64) NOT NULL,
    attribute_id VARCHAR(64) NOT NULL,
    value_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(tenant_id,spu_id,attribute_id,value_id),
    FOREIGN KEY(tenant_id,attribute_id,value_id) REFERENCES pdm_spec_value(tenant_id,attribute_id,value_id)
);
CREATE TABLE IF NOT EXISTS pdm_sku_spec_value (
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    attribute_id VARCHAR(64) NOT NULL,
    value_id VARCHAR(64) NOT NULL,
    PRIMARY KEY(tenant_id,sku_id,attribute_id),
    FOREIGN KEY(tenant_id,sku_id) REFERENCES pdm_sku(tenant_id,sku_id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,attribute_id,value_id) REFERENCES pdm_spec_value(tenant_id,attribute_id,value_id)
);
ALTER TABLE pdm_sku ADD COLUMN IF NOT EXISTS specification_key VARCHAR(4096);
ALTER TABLE pdm_sku ADD COLUMN IF NOT EXISTS specification_review_required BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uq_pdm_sku_specification ON pdm_sku(tenant_id,spu_id,specification_key) WHERE specification_key IS NOT NULL;

-- 空规格不是已声明的组合；普通商品和待补规格草稿保留原身份。
-- 旧自由文本不按名称猜测字典 ID，也不自动合并到任何结构化组合。
UPDATE pdm_sku s SET specification_review_required=TRUE,identity_locked=TRUE
WHERE EXISTS (SELECT 1 FROM pdm_product_variant v WHERE v.tenant_id=s.tenant_id
    AND (v.canonical_sku_id=s.sku_id OR v.sku_id=s.sku_id OR UPPER(TRIM(v.seller_sku))=UPPER(TRIM(s.seller_sku)))
    AND (LOWER(TRIM(COALESCE(v.variant_attributes,''))) NOT IN ('','{}','[]','null') OR LOWER(TRIM(COALESCE(v.variant_attrs,''))) NOT IN ('','{}','[]','null')));
