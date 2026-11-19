CREATE TABLE IF NOT EXISTS fba_box_label_plan (
    tenant_id VARCHAR(64) NOT NULL, fba_shipment_id VARCHAR(64) NOT NULL,
    amazon_shipment_id VARCHAR(128) NOT NULL, carton_count INT NOT NULL CHECK (carton_count > 0),
    registered_by VARCHAR(64) NOT NULL, registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, fba_shipment_id), UNIQUE (tenant_id, amazon_shipment_id)
);
CREATE TABLE IF NOT EXISTS fba_registered_box (
    tenant_id VARCHAR(64) NOT NULL, box_number VARCHAR(128) NOT NULL, fba_shipment_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, box_number), FOREIGN KEY (tenant_id, fba_shipment_id) REFERENCES fba_box_label_plan(tenant_id, fba_shipment_id)
);
CREATE TABLE IF NOT EXISTS wms_fba_box_binding (
    tenant_id VARCHAR(64) NOT NULL, carton_id VARCHAR(64) NOT NULL, fba_box_number VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE, bound_by VARCHAR(64) NOT NULL, bound_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, carton_id), FOREIGN KEY (tenant_id, carton_id) REFERENCES wms_fba_carton(tenant_id, carton_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wms_fba_box_binding_active ON wms_fba_box_binding(tenant_id, fba_box_number) WHERE active;

CREATE TABLE IF NOT EXISTS wms_fba_shipment (
    tenant_id VARCHAR(64) NOT NULL, outbound_id VARCHAR(64) NOT NULL, fba_shipment_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL, amazon_shipment_id VARCHAR(128) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0), carton_count INT NOT NULL CHECK (carton_count > 0),
    carrier_code VARCHAR(64) NOT NULL, tracking_no VARCHAR(128) NOT NULL, operator_id VARCHAR(64) NOT NULL,
    shipped_at TIMESTAMP WITH TIME ZONE NOT NULL, sync_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (sync_status IN ('PENDING', 'SYNCED')),
    sync_error VARCHAR(256), sync_attempts INT NOT NULL DEFAULT 0, synced_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (tenant_id, outbound_id), UNIQUE (tenant_id, fba_shipment_id),
    FOREIGN KEY (tenant_id, outbound_id) REFERENCES wms_sales_pick_work(tenant_id, outbound_id)
);
CREATE INDEX IF NOT EXISTS idx_wms_fba_shipment_pending ON wms_fba_shipment(shipped_at) WHERE sync_status = 'PENDING';
CREATE TABLE IF NOT EXISTS fba_warehouse_shipment (
    tenant_id VARCHAR(64) NOT NULL, outbound_id VARCHAR(64) NOT NULL, fba_shipment_id VARCHAR(64) NOT NULL,
    warehouse_id VARCHAR(64) NOT NULL, amazon_shipment_id VARCHAR(128) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0), carton_count INT NOT NULL CHECK (carton_count > 0),
    carrier_code VARCHAR(64) NOT NULL, tracking_no VARCHAR(128) NOT NULL, operator_id VARCHAR(64) NOT NULL,
    shipped_at TIMESTAMP WITH TIME ZONE NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, outbound_id), UNIQUE (tenant_id, fba_shipment_id)
);

-- 内部码与外部码共用唯一查询入口，避免两个标识指向不同箱子。
CREATE TABLE IF NOT EXISTS wms_fba_box_identifier (
    tenant_id VARCHAR(64) NOT NULL, box_code VARCHAR(128) NOT NULL, carton_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('INTERNAL', 'FBA')),
    PRIMARY KEY (tenant_id, box_code), FOREIGN KEY (tenant_id, carton_id) REFERENCES wms_fba_carton(tenant_id, carton_id)
);
INSERT INTO wms_fba_box_identifier(tenant_id, box_code, carton_id, kind)
SELECT tenant_id, carton_code, carton_id, 'INTERNAL' FROM wms_fba_carton WHERE TRUE ON CONFLICT DO NOTHING;
INSERT INTO wms_fba_box_identifier(tenant_id, box_code, carton_id, kind)
SELECT tenant_id, fba_box_number, carton_id, 'FBA' FROM wms_fba_box_binding ORDER BY active DESC, bound_at DESC ON CONFLICT DO NOTHING;
