'use client';

import { Suspense, useEffect, useState } from 'react';
import { Alert, Button, Card, Input, Select, Space, Table, Tabs, Tag, Typography } from 'antd';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useApi } from '@/lib/hooks';
import type { InventoryBalance, Warehouse, WarehouseLocation } from '@/types';
import { SalesBatchTrace } from './sales-batch-trace';

const wms = '/wms/api/in/v1';
const stockLabels: Record<string, string> = {
  PENDING_INSPECTION: '待检', PENDING_PUTAWAY: '待上架', QUARANTINED: '隔离', AVAILABLE: '可用', RESERVED: '已预占待拣', PICKED: '已拣（发货暂存）',
  PENDING_REVIEW: '待复核', QUALITY_HOLD: '质检更正暂停',
};
const movementLabels: Record<string, string> = {
  QUALITY_HOLD: '质检更正暂停', QUALITY_RELEASE: '主管取消预占', PUTAWAY_REVERSE: '撤销上架', SALES_UNPICK: '撤拣直接退待检', INSPECTION_WITHDRAW: '撤销质检判定', QUALITY_REINSPECT: '更正后重新全检',
  RECEIVE: '入库', RESERVE: '预占', RELEASE: '释放预占', DEDUCT: '出库', STOCK_ADJUST: '调整', SALES_PICK: '实拣转发货暂存',
  QUALITY_DISPOSITION: '质检判定', PURCHASE_PUTAWAY: '确认上架',
  PURCHASE_QUARANTINE: '隔离入位', PURCHASE_SUPPLIER_RETURN: '退供出库', SALES_SHIP: '销售实际发货',
  SHORT_PICK_REPORT: '缺拣转待复核', SHORT_PICK_REPLACE: '缺拣替代批次预占', STOCK_REVIEW: '主管确认差异复核',
};

interface BatchBalance {
  batchNo: string;
  receiptId: string;
  purchaseOrderId: string;
  warehouseId: string;
  sellerSku: string;
  supplierBatchNo?: string;
  locationId?: string;
  locationCode?: string;
  status: string;
  quantity: number;
}

interface Movement {
  transactionId: string;
  warehouseId: string;
  sellerSku: string;
  transactionType: string;
  referenceId?: string;
  referenceType?: string;
  batchNo?: string;
  operatorId?: string;
  quantity: number;
  createdAt: string;
  sourceLocationId?: string;
  targetLocationId?: string;
  beforeLocationQuantity?: number;
  afterLocationQuantity?: number;
  beforePendingPutaway?: number;
  afterPendingPutaway?: number;
  beforeOnHand: number;
  afterOnHand: number;
  beforeReserved: number;
  afterReserved: number;
  pendingInspectionDelta?: number;
  pendingPutawayDelta?: number;
  quarantinedDelta?: number;
  pendingReviewDelta?: number;
  availableDelta?: number;
  remark?: string;
}

export default function InventoryPage() {
  return <Suspense fallback={<Typography.Text>正在加载库存台账…</Typography.Text>}><InventoryLedger /></Suspense>;
}

function InventoryLedger() {
  const search = useSearchParams();
  const purchaseOrderId = search.get('purchaseOrderId');
  const packageId = search.get('packageId');
  const [warehouseId, setWarehouse] = useState(search.get('warehouseId') ?? undefined);
  const [locationId, setLocation] = useState<string>();
  const [sellerSku, setSku] = useState(search.get('sellerSku') ?? search.get('sku') ?? search.get('keyword') ?? '');
  const [batchNo, setBatch] = useState(search.get('batchNo') ?? '');
  const [documentId, setDocument] = useState(search.get('documentId') ?? '');
  const [tab, setTab] = useState(search.get('tab') === 'shipments' ? 'shipments' : search.get('tab') === 'movements' ? 'movements'
    : search.has('batchNo') || purchaseOrderId ? 'batch' : 'warehouse');
  useEffect(() => {
    setTab(search.get('tab') === 'shipments' ? 'shipments' : search.get('tab') === 'movements' ? 'movements'
      : search.has('batchNo') || search.has('purchaseOrderId') ? 'batch' : 'warehouse');
    setBatch(search.get('batchNo') ?? '');
    setWarehouse(search.get('warehouseId') ?? undefined);
    setLocation(undefined);
    setSku(search.get('sellerSku') ?? search.get('sku') ?? search.get('keyword') ?? '');
    setDocument(search.get('documentId') ?? '');
  }, [search]);
  const warehouses = useApi<Warehouse[]>(`${wms}/warehouses`);
  const activeWarehouse = warehouseId ?? warehouses.data?.[0]?.warehouseId;
  const locations = useApi<WarehouseLocation[]>(activeWarehouse ? `${wms}/warehouses/${activeWarehouse}/locations` : null);
  const filters = new URLSearchParams();
  if (warehouseId) filters.set('warehouseId', warehouseId);
  if (locationId) filters.set('locationId', locationId);
  if (sellerSku.trim()) filters.set('sellerSku', sellerSku.trim());
  if (batchNo.trim()) filters.set('batchNo', batchNo.trim());
  if (purchaseOrderId) filters.set('purchaseOrderId', purchaseOrderId);
  const query = filters.toString();
  if (documentId.trim()) filters.set('documentId', documentId.trim());
  const balances = useApi<BatchBalance[]>(tab === 'batch' ? `${wms}/inventory-ledger/balances?${query}` : null);
  const transactions = useApi<Movement[]>(tab === 'movements' ? `${wms}/inventory-ledger/transactions?${filters}` : null);
  const summary = useApi<InventoryBalance[]>(tab === 'warehouse' && activeWarehouse
    ? `${wms}/warehouses/${activeWarehouse}/inventory` : null);
  const warehouseLabel = (id: string) => warehouses.data?.find((item) => item.warehouseId === id)?.name ?? id;
  const locationLabel = (id?: string, code?: string) => id
    ? locations.data?.find((item) => item.locationId === id)?.locationCode ?? code ?? id : '未入位';

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Typography.Title level={4}>库存台账</Typography.Title>
    {purchaseOrderId && <Space wrap>
      <Typography.Text>采购单关联批次</Typography.Text>
      <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(purchaseOrderId)}`}>查看收货记录</Link>
      <Link href="/wms/inventory">查看全部库存</Link>
    </Space>}
    {packageId && <Space wrap>
      <Typography.Text>当前包裹 {packageId}</Typography.Text>
      <Link href="/wms/inventory?tab=shipments">查看全部出库</Link>
    </Space>}
    <Card>
      <Space wrap>
        <Select aria-label="仓库" placeholder={tab === 'warehouse' ? '选择仓库' : '全部仓库'} allowClear={tab !== 'warehouse'}
          value={tab === 'warehouse' ? activeWarehouse : warehouseId} style={{ width: 200 }}
          options={(warehouses.data ?? []).map((item) => ({ label: item.name, value: item.warehouseId }))}
          onChange={(value) => { setWarehouse(value); setLocation(undefined); }} />
        {tab !== 'warehouse' && <Select aria-label="库位" placeholder="全部库位" allowClear disabled={!warehouseId}
          value={locationId} style={{ width: 160 }} onChange={setLocation}
          options={(locations.data ?? []).filter((item) => item.locationCode)
            .map((item) => ({ label: item.locationCode, value: item.locationId }))} />}
        <Input aria-label="SKU" placeholder="SKU" value={sellerSku} allowClear onChange={(event) => setSku(event.target.value)} />
        {tab !== 'warehouse' && <Input aria-label="收货批次" placeholder="收货批次" value={batchNo} allowClear
          onChange={(event) => setBatch(event.target.value)} />}
        {tab === 'movements' && <Input aria-label="来源单据号" placeholder="来源单据号" value={documentId} allowClear
          onChange={(event) => setDocument(event.target.value)} />}
      </Space>
      {(warehouses.error || locations.error || summary.error || balances.error || transactions.error)
        && <Alert showIcon type="error" message="库存数据加载失败，请刷新重试" />}
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key: 'shipments', label: '出库追溯', children: tab === 'shipments'
          ? <SalesBatchTrace purchaseOrderId={purchaseOrderId} batchNo={batchNo} packageId={packageId}
            warehouseId={warehouseId} locationId={locationId} sellerSku={sellerSku} /> : null },
        { key: 'warehouse', label: '仓库汇总', children: <Table<InventoryBalance> rowKey="sellerSku" size="small"
          loading={summary.isLoading} dataSource={(summary.data ?? []).filter((item) => !sellerSku.trim() || item.sellerSku === sellerSku.trim())}
          columns={[
            { title: 'SKU', dataIndex: 'sellerSku' }, { title: '在手', dataIndex: 'onHand' },
            { title: '待检', dataIndex: 'pendingInspection' }, { title: '待上架', dataIndex: 'pendingPutaway' },
            { title: '隔离', dataIndex: 'quarantined' }, { title: '待复核', dataIndex: 'pendingReview' }, { title: '质检暂停', dataIndex: 'qualityHold' }, { title: '可用', dataIndex: 'available' },
            { title: '预占', dataIndex: 'reserved' }, { title: '冻结', dataIndex: 'frozen' }, { title: '在途', dataIndex: 'inTransit' },
          ]} /> },
        { key: 'batch', label: '库位与批次', children: <Table<BatchBalance> size="small" scroll={{ x: 'max-content' }}
          rowKey={(item) => `${item.batchNo}/${item.locationId ?? 'unplaced'}/${item.status}`}
          loading={balances.isLoading} dataSource={balances.data ?? []} columns={[
            { title: '仓库', render: (_, item) => warehouseLabel(item.warehouseId) },
            { title: '库位', render: (_, item) => item.status === 'PICKED'
              ? `发货暂存（原库位 ${locationLabel(item.locationId, item.locationCode)}）` : locationLabel(item.locationId, item.locationCode) },
            { title: 'SKU', dataIndex: 'sellerSku' }, { title: '收货批次', dataIndex: 'batchNo' },
            { title: '供应商批号', dataIndex: 'supplierBatchNo' },
            { title: '库存状态', render: (_, item) => <Tag>{stockLabels[item.status] ?? item.status}</Tag> },
            { title: '结存', dataIndex: 'quantity' },
            { title: '来源与去向', render: (_, item) => <Space>
              <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(item.purchaseOrderId)}`}>收货记录</Link>
              <Link href={`/wms/inventory?tab=shipments&batchNo=${encodeURIComponent(item.batchNo)}`}>出库去向</Link>
            </Space> },
          ]} /> },
        { key: 'movements', label: '库存流水', children: <Table<Movement> size="small" rowKey="transactionId"
          loading={transactions.isLoading} dataSource={transactions.data ?? []} scroll={{ x: 'max-content' }} columns={[
            { title: '时间', render: (_, item) => new Date(item.createdAt).toLocaleString() },
            { title: '作业', render: (_, item) => movementLabels[item.transactionType] ?? item.transactionType },
            { title: '来源单据号', render: (_, item) => item.referenceType === 'QUALITY_CORRECTION' && item.referenceId
              ? <Link href={`/wms/operations?correctionId=${encodeURIComponent(item.referenceId)}`}>{item.referenceId}</Link>
              : item.referenceType === 'SHORT_PICK' && item.referenceId
              ? <Link href={`/wms/operations?reviewId=${encodeURIComponent(item.referenceId)}`}>{item.referenceId}</Link>
              : item.referenceType === 'SALES_SHIPMENT' && item.referenceId
              ? <Link href={`/wms/inventory?tab=shipments&packageId=${encodeURIComponent(item.referenceId)}`}>{item.referenceId}</Link>
              : item.referenceType === 'SALES_BATCH_PICK' && item.referenceId
              ? <Link href={`/wms/operations?outboundId=${encodeURIComponent(item.referenceId)}`}>{item.referenceId}</Link>
              : item.transactionType === 'PURCHASE_SUPPLIER_RETURN' && item.referenceId
              ? <Link href={`/wms/operations?returnId=${encodeURIComponent(item.referenceId)}`}>{item.referenceId}</Link>
              : item.batchNo
              ? <Link href={`/wms/operations?batchNo=${encodeURIComponent(item.batchNo)}`}>{item.referenceId ?? item.batchNo}</Link>
              : item.referenceId ?? '—' },
            { title: '仓库', render: (_, item) => warehouseLabel(item.warehouseId) },
            { title: 'SKU', dataIndex: 'sellerSku' }, { title: '收货批次', render: (_, item) => item.batchNo
              ? <Button type="link" onClick={() => { setBatch(item.batchNo ?? ''); setTab('batch'); }}>{item.batchNo}</Button> : '—' },
            { title: '原库位', render: (_, item) => item.transactionType === 'SALES_SHIP'
              ? `发货暂存（原库位 ${locationLabel(item.sourceLocationId)}）`
              : item.sourceLocationId ? locationLabel(item.sourceLocationId,
              item.transactionType === 'PURCHASE_SUPPLIER_RETURN' ? item.remark : undefined)
              : item.transactionType === 'PURCHASE_PUTAWAY' ? '待上架区（未入位）'
                : item.transactionType === 'PURCHASE_QUARANTINE' ? '隔离次品（未入位）' : '—' },
            { title: '目标库位', render: (_, item) => item.targetLocationId
              ? locationLabel(item.targetLocationId, item.remark) : item.transactionType === 'SALES_PICK' ? '发货暂存区' : '—' },
            { title: '仓库在手', render: (_, item) => `${item.beforeOnHand} → ${item.afterOnHand}` },
            { title: '订单预占', render: (_, item) => `${item.beforeReserved} → ${item.afterReserved}` },
            { title: '库位结存', render: (_, item) => item.afterLocationQuantity == null ? '—'
              : `${item.beforeLocationQuantity} → ${item.afterLocationQuantity}` },
            { title: '批次待上架', render: (_, item) => item.afterPendingPutaway == null ? '—'
              : `${item.beforePendingPutaway} → ${item.afterPendingPutaway}` },
            { title: '状态变化', render: (_, item) => formatDeltas(item) },
            { title: '操作人', dataIndex: 'operatorId' },
          ]} /> },
      ]} />
    </Card>
  </Space>;
}


function formatDeltas(item: Movement) {
  const changes = [
    ['待检', item.pendingInspectionDelta], ['待上架', item.pendingPutawayDelta],
    ['隔离', item.quarantinedDelta], ['待复核', item.pendingReviewDelta], ['可用', item.availableDelta],
  ] as const;
  return changes.filter(([, quantity]) => quantity).map(([name, quantity]) =>
    `${name} ${quantity! > 0 ? '+' : ''}${quantity}`).join(' · ') || '—';
}
