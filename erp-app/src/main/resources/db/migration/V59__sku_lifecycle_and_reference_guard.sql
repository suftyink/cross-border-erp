ALTER TABLE pdm_sku ADD COLUMN IF NOT EXISTS ever_enabled BOOLEAN;
ALTER TABLE pdm_sku ADD COLUMN IF NOT EXISTS identity_locked BOOLEAN NOT NULL DEFAULT FALSE;

-- 当前 ACTIVE 能证明已启用；旧停用/归档/审核状态不能证明从未启用。
UPDATE pdm_sku SET ever_enabled = TRUE, identity_locked = TRUE WHERE status = 'ACTIVE';
UPDATE pdm_sku SET identity_locked = TRUE WHERE ever_enabled IS NULL;
ALTER TABLE pdm_sku ALTER COLUMN ever_enabled SET DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS pdm_sku_code_guard (
    tenant_id VARCHAR(64) NOT NULL,
    normalized_code VARCHAR(128) NOT NULL,
    sku_id VARCHAR(64),
    referenced BOOLEAN NOT NULL DEFAULT FALSE,
    retired BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id, normalized_code)
);

CREATE TABLE IF NOT EXISTS pdm_sku_deleted_identity (
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    last_code VARCHAR(128) NOT NULL,
    deleted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_id, sku_id)
);

CREATE TABLE IF NOT EXISTS pdm_sku_history (
    event_id VARCHAR(64) PRIMARY KEY,
    event_order BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id VARCHAR(64) NOT NULL,
    sku_id VARCHAR(64) NOT NULL,
    action VARCHAR(40) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    reason VARCHAR(512),
    before_json JSONB,
    after_json JSONB,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE pdm_sku_history ADD COLUMN IF NOT EXISTS event_order BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE INDEX IF NOT EXISTS idx_pdm_sku_history ON pdm_sku_history(tenant_id, sku_id, occurred_at);

INSERT INTO pdm_sku_code_guard(tenant_id,normalized_code,sku_id)
SELECT tenant_id,UPPER(TRIM(seller_sku)),sku_id FROM pdm_sku
ON CONFLICT(tenant_id,normalized_code) DO UPDATE SET sku_id=EXCLUDED.sku_id;

-- 旧 sku_id 原值保留为证据，只建立可证明的一对一库存身份关联。
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS canonical_sku_id VARCHAR(64);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS sku_id VARCHAR(64);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS variant_attrs TEXT;
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS enabled BOOLEAN;
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS variant_attributes TEXT;
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS purchase_cost DECIMAL(18,4);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS selling_price DECIMAL(18,4);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS price_adjustment DECIMAL(18,4);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS seller_sku VARCHAR(128);
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS images TEXT;
ALTER TABLE pdm_product_variant ADD COLUMN IF NOT EXISTS status VARCHAR(32);

UPDATE pdm_product_variant SET variant_attributes=variant_attrs
WHERE variant_attributes IS NULL AND variant_attrs IS NOT NULL;

WITH candidates AS (
    SELECT v.variant_id,s.sku_id,
           COUNT(*) OVER(PARTITION BY v.tenant_id,v.variant_id) AS candidate_count,
           COUNT(*) OVER(PARTITION BY s.tenant_id,s.sku_id) AS variant_count
    FROM pdm_product_variant v JOIN pdm_sku s ON s.tenant_id=v.tenant_id AND s.spu_id=v.spu_id
    AND (s.sku_id=v.sku_id OR (v.seller_sku IS NOT NULL AND UPPER(TRIM(v.seller_sku))=UPPER(TRIM(s.seller_sku))))
    AND (NULLIF(TRIM(v.sku_id),'') IS NULL OR v.sku_id=s.sku_id)
    AND (NULLIF(TRIM(v.seller_sku),'') IS NULL OR UPPER(TRIM(v.seller_sku))=UPPER(TRIM(s.seller_sku)))
    WHERE NOT EXISTS (SELECT 1 FROM pdm_product_variant bound WHERE bound.tenant_id=s.tenant_id AND bound.canonical_sku_id=s.sku_id)
)
UPDATE pdm_product_variant v SET canonical_sku_id=c.sku_id
FROM candidates c WHERE c.variant_id=v.variant_id AND v.canonical_sku_id IS NULL AND c.candidate_count=1 AND c.variant_count=1;

UPDATE pdm_sku s SET ever_enabled=TRUE,identity_locked=TRUE
WHERE EXISTS(SELECT 1 FROM pdm_product_variant v WHERE v.tenant_id=s.tenant_id AND v.canonical_sku_id=s.sku_id AND v.status='ACTIVE');
CREATE UNIQUE INDEX IF NOT EXISTS uq_pdm_sku_tenant_id ON pdm_sku(tenant_id,sku_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pdm_variant_canonical ON pdm_product_variant(tenant_id,canonical_sku_id) WHERE canonical_sku_id IS NOT NULL;

INSERT INTO pdm_sku_history(event_id,tenant_id,sku_id,action,actor_id,reason,after_json)
SELECT md5('V59:'||tenant_id||':'||sku_id),tenant_id,sku_id,'MIGRATE_EVIDENCE','migration:V59',
       CASE WHEN ever_enabled IS TRUE THEN '保留已启用证据' ELSE '历史不足，身份待核实并保守锁定' END,
       jsonb_build_object('status',status,'everEnabled',ever_enabled,'identityLocked',identity_locked)
FROM pdm_sku WHERE identity_locked IS TRUE
ON CONFLICT(event_id) DO NOTHING;
