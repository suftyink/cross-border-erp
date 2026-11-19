-- 保留既有列名以兼容历史作业，通过来源类型区分销售订单与 FBA 备货单。
ALTER TABLE wms_sales_pick_work ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'SALES_ORDER';
ALTER TABLE wms_sales_pick_work DROP CONSTRAINT IF EXISTS wms_sales_pick_work_tenant_id_sales_order_id_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_pick_source ON wms_sales_pick_work(tenant_id, source_type, sales_order_id);

CREATE TABLE IF NOT EXISTS wms_fba_carton (
    tenant_id VARCHAR(64) NOT NULL,
    carton_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    carton_code VARCHAR(64) NOT NULL,
    requested_code VARCHAR(64),
    outbound_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, carton_id),
    UNIQUE (tenant_id, request_id),
    UNIQUE (tenant_id, carton_code),
    FOREIGN KEY (tenant_id, outbound_id) REFERENCES wms_sales_pick_work(tenant_id, outbound_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_fba_carton_outbound ON wms_fba_carton(tenant_id, outbound_id);
