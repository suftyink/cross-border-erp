'use client';

import { Table } from 'antd';
import Link from 'next/link';
import type { ShipmentLine } from '@/types/warehouse-shipment';

export function WarehouseShipmentLines({ lines }: { lines: ShipmentLine[] }) {
  return <Table<ShipmentLine> size="small" rowKey="pickId" dataSource={lines} pagination={false} scroll={{ x: 'max-content' }} columns={[
    { title: 'SKU', dataIndex: 'sellerSku' }, { title: '原库位', dataIndex: 'locationCode' },
    { title: '实拣批次', dataIndex: 'batchNo' }, { title: '装入数量', dataIndex: 'quantity' },
    { title: '收货记录', render: (_, row) => <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(row.purchaseOrderId)}&batchNo=${encodeURIComponent(row.batchNo)}`}>{row.receiptId}</Link> },
    { title: '采购单', render: (_, row) => <Link href={`/scm/purchase-orders?poId=${encodeURIComponent(row.purchaseOrderId)}`}>{row.purchaseOrderId}</Link> },
  ]} />;
}
