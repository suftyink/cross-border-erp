'use client';

import { Alert, Button, Space, Table } from 'antd';
import Link from 'next/link';
import { useApi } from '@/lib/hooks';
import type { ShipmentTrace } from '@/types/warehouse-shipment';

export function SalesBatchTrace({ purchaseOrderId, batchNo, packageId, warehouseId, locationId, sellerSku }: {
  purchaseOrderId: string | null; batchNo: string; packageId: string | null;
  warehouseId?: string; locationId?: string; sellerSku: string;
}) {
  const filters = new URLSearchParams();
  if (purchaseOrderId) filters.set('purchaseOrderId', purchaseOrderId);
  if (batchNo.trim()) filters.set('batchNo', batchNo.trim());
  if (packageId) filters.set('packageId', packageId);
  if (warehouseId) filters.set('warehouseId', warehouseId);
  if (locationId) filters.set('locationId', locationId);
  if (sellerSku.trim()) filters.set('sellerSku', sellerSku.trim());
  const work = useApi<ShipmentTrace[]>(`/wms/api/in/v1/sales-dispatch/trace?${filters}`);
  return <Space direction="vertical" style={{ width: '100%' }}>
    {work.error && <Alert type="error" showIcon message="出库追溯加载失败" action={<Button onClick={() => void work.mutate()}>重新加载</Button>} />}
    <Table<ShipmentTrace> size="small" rowKey={(row) => `${row.packageId}/${row.pickId}`} loading={work.isLoading}
      dataSource={work.data ?? []} scroll={{ x: 'max-content' }} columns={[
        { title: '实际发货时间', render: (_, row) => new Date(row.shippedAt).toLocaleString() },
        { title: '销售订单', render: (_, row) => <Link href={`/oms/orders?orderId=${encodeURIComponent(row.salesOrderId)}&tab=warehouse`}>{row.salesOrderId}</Link> },
        { title: '出库单', render: (_, row) => <Link href={`/wms/operations?outboundId=${encodeURIComponent(row.outboundId)}`}>{row.outboundId}</Link> },
        { title: '包裹', dataIndex: 'packageId' }, { title: 'SKU', dataIndex: 'sellerSku' }, { title: '原库位', dataIndex: 'locationCode' },
        { title: '实拣批次', dataIndex: 'batchNo' }, { title: '实际发货数量', dataIndex: 'quantity' },
        { title: '收货记录', render: (_, row) => <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(row.purchaseOrderId)}&batchNo=${encodeURIComponent(row.batchNo)}`}>{row.receiptId}</Link> },
        { title: '采购单', render: (_, row) => <Link href={`/scm/purchase-orders?poId=${encodeURIComponent(row.purchaseOrderId)}`}>{row.purchaseOrderId}</Link> },
        { title: '发货人', dataIndex: 'operatorId' },
      ]} />
  </Space>;
}
