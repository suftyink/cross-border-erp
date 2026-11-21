-- 保留现有编码和历史引用；归一化唯一约束先拒绝冲突，不自动合并商品。
ALTER TABLE pdm_sku ADD COLUMN IF NOT EXISTS base_unit VARCHAR(32);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pdm_sku_normalized_code ON pdm_sku(tenant_id, UPPER(TRIM(seller_sku)));
