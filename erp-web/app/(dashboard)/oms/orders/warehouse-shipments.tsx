'use client';

import { useState } from 'react';
import { Alert, Button, Descriptions, Space, Table, Tag } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { WarehouseShipmentLines } from '@/components/warehouse-shipment-lines';
import type { WarehouseShipment } from '@/types/warehouse-shipment';

interface Fulfillment { orderStatus: string; fulfillmentStatus: string; shippedQuantity: number; packages: WarehouseShipment[] }
const labels: Record<string, string> = { PACKED: '已打包', WEIGHED: '已称重待发货', SHIPPED: '已发货', PARTIALLY_SHIPPED: '部分已发货', UNFULFILLED: '待发货' };

export function OrderWarehouseShipments({ orderId, onChange }: { orderId: string; onChange: () => void }) {
  const path = `/oms/api/in/v1/orders/${encodeURIComponent(orderId)}/warehouse-shipments`;
  const work = useApi<Fulfillment>(path);
  const access = useApi<{ canDispatch: boolean }>('/oms/api/in/v1/orders/warehouse-work-access');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const refresh = async () => {
    setSaving(true); setError(undefined);
    try { await post(`${path}/refresh`); await work.mutate(); onChange(); }
    catch (failure) { setError(isAxiosError(failure) ? failure.response?.data?.message ?? '读取发货结果失败，可以重试。' : '读取发货结果失败，可以重试。'); }
    finally { setSaving(false); }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="订单读取仓库实际发货结果更新履约。重复读取保持原发货数量。" />
    {(work.error || error) && <Alert type="error" showIcon message={error ?? '仓储履约加载失败'} />}
    <Space>
      {access.data?.canDispatch && <Button loading={saving} onClick={() => void refresh()}>重读仓库发货结果</Button>}
      <Link href={`/wms/operations?salesOrderId=${encodeURIComponent(orderId)}`}>查看仓储作业</Link>
    </Space>
    <Descriptions size="small" items={[
      { key: 'status', label: '履约状态', children: labels[work.data?.fulfillmentStatus ?? ''] ?? work.data?.fulfillmentStatus ?? '—' },
      { key: 'quantity', label: '已回写发货件数', children: work.data?.shippedQuantity ?? '—' },
    ]} />
    <Table<WarehouseShipment> rowKey="packageId" loading={work.isLoading} dataSource={work.data?.packages ?? []} pagination={false}
      expandable={{ expandedRowRender: (row) => <WarehouseShipmentLines lines={row.lines} /> }} scroll={{ x: 'max-content' }} columns={[
        { title: '包裹编号', dataIndex: 'packageId' }, { title: '状态', render: (_, row) => <Tag>{labels[row.status] ?? row.status}</Tag> },
        { title: '发货件数', render: (_, row) => row.status === 'SHIPPED' ? row.lines.reduce((n, l) => n + l.quantity, 0) : 0 },
        { title: '承运商', dataIndex: 'carrierCode' }, { title: '运单号', dataIndex: 'trackingNo' },
        { title: '发货时间', render: (_, row) => row.shippedAt ? new Date(row.shippedAt).toLocaleString() : '—' },
        { title: '来源与去向', render: (_, row) => <Space>
          <Link href={`/wms/operations?outboundId=${encodeURIComponent(row.outboundId)}`}>出库单</Link>
          <Link href={`/wms/inventory?tab=shipments&packageId=${encodeURIComponent(row.packageId)}`}>批次追溯</Link>
        </Space> },
      ]} />
  </Space>;
}
