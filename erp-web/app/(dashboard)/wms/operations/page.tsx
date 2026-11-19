'use client';

import { Suspense, useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, message, Modal, Select, Space, Table, Tabs, Typography } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import type { PurchaseOrder, PurchaseReceipt, PurchaseReceiptInput, PurchaseReceiptMovement, Warehouse } from '@/types';
import { ReceiptLabels } from './receipt-labels';
import { InspectionDialog, InspectionWork } from './purchase-inspection';
import { PurchasePutaway } from './purchase-putaway';
import { PurchaseReturns, ReceiptReturnAction } from './purchase-returns';
import { newOperationId } from './operation-id';
import { SalesPicking } from './sales-picking';

const receiptsApi = '/wms/api/in/v1/purchase-receipts';
type PendingReceipt = { orderId: string; request: PurchaseReceiptInput };

export default function WarehouseOperationsPage() {
  return <Suspense fallback={<Typography.Text>正在加载仓储作业…</Typography.Text>}><ReceiptWork /></Suspense>;
}

function ReceiptWork() {
  const search = useSearchParams();
  const purchaseOrderId = search.get('purchaseOrderId');
  const sourceBatchNo = search.get('batchNo');
  const returnId = search.get('returnId');
  const salesOrderId = search.get('salesOrderId');
  const outboundId = search.get('outboundId');
  const [activeTab, setActiveTab] = useState(salesOrderId || outboundId ? 'picking' : returnId ? 'returns' : 'receiving');
  useEffect(() => {
    setActiveTab(salesOrderId || outboundId ? 'picking' : returnId ? 'returns' : 'receiving');
  }, [salesOrderId, outboundId, returnId, purchaseOrderId, sourceBatchNo]);
  const [selectedOrder, setSelectedOrder] = useState<PurchaseOrder>();
  const [replacement, setReplacement] = useState(false);
  const [selectedReceipt, setSelectedReceipt] = useState<PurchaseReceipt>();
  const [inspectionBatch, setInspectionBatch] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [pending, setPending] = useState<PendingReceipt>();
  const [form] = Form.useForm<Omit<PurchaseReceiptInput, 'receiptId'>>();
  const tenantId = useAuthStore((state) => state.tenantId);
  const storageKey = `wms:pending-receipt:${tenantId}`;
  const work = useApi<{ canReceive: boolean; purchaseOrders: PurchaseOrder[] }>(`${receiptsApi}/work`);
  const receipts = useApi<PurchaseReceipt[]>(purchaseOrderId
    ? `${receiptsApi}?purchaseOrderId=${encodeURIComponent(purchaseOrderId)}` : receiptsApi);
  const { data: warehouses } = useApi<Warehouse[]>('/wms/api/in/v1/warehouses');
  const movements = useApi<PurchaseReceiptMovement[]>(selectedReceipt
    ? `${receiptsApi}/${encodeURIComponent(selectedReceipt.receiptId)}/movements` : null);
  const sourceOrder = useApi<{ poNumber: string; receiptTolerancePercent: number; overReceivedQuantity: number }>(selectedReceipt
    ? `${receiptsApi}/${encodeURIComponent(selectedReceipt.receiptId)}/purchase-order` : null);

  useEffect(() => {
    const saved = sessionStorage.getItem(storageKey);
    setPending(saved ? JSON.parse(saved) as PendingReceipt : undefined);
  }, [storageKey]);

  const startReceipt = (order: PurchaseOrder, replacement = false) => {
    form.resetFields();
    setSelectedOrder(order);
    setReplacement(replacement);
  };

  const sendReceipt = async (attempt: PendingReceipt) => {
    if (saving) return;
    setSaving(true);
    setPending(attempt);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt));
      const { orderId, request } = attempt;
      const receipt = await post<PurchaseReceipt>(`${receiptsApi}/${encodeURIComponent(orderId)}`, request);
      sessionStorage.removeItem(storageKey);
      setPending(undefined);
      setSelectedOrder(undefined);
      setSelectedReceipt(receipt);
      message.success('收货已确认，货物进入待检');
      void Promise.all([work.mutate(), receipts.mutate()]);
    } catch (error) {
      const response = isAxiosError<{ message?: string }>(error) ? error.response : undefined;
      if (response && response.status < 500) {
        sessionStorage.removeItem(storageKey);
        setPending(undefined);
      }
      message.error(response?.data?.message ?? '收货结果未确认，请用原明细重试');
    } finally {
      setSaving(false);
    }
  };

  const confirmReceipt = (values: Omit<PurchaseReceiptInput, 'receiptId'>) => {
    if (selectedOrder) {
      void sendReceipt({ orderId: selectedOrder.poId, request: { ...values, receiptId: newOperationId(), replacement } });
    }
  };

  const purchaseOrders = (work.data?.purchaseOrders ?? []).filter((order) => !purchaseOrderId || order.poId === purchaseOrderId);
  return (
    <>
      <Typography.Title level={4}>仓储作业</Typography.Title>
      {purchaseOrderId && <Link href="/wms/operations">查看全部收货</Link>}
      {(work.error || receipts.error) && <Alert showIcon type="error" message="收货数据加载失败，请刷新重试" />}
      {pending && !selectedOrder && work.data?.canReceive && <Alert type="warning" showIcon
        message={`待确认收货 ${pending.request.receiptId}`}
        action={<Button loading={saving} onClick={() => void sendReceipt(pending)}>重试原确认</Button>} />}
      <Tabs activeKey={activeTab} onChange={setActiveTab} items={[{ key: 'picking', label: '拣货与出库', children: (
        <SalesPicking salesOrderId={salesOrderId} outboundId={outboundId} warehouses={warehouses ?? []} />
      ) }, { key: 'receiving', label: '收货', children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {work.data?.canReceive && <Card title="收货待办">
            <Table<PurchaseOrder> rowKey="poId" size="small" dataSource={purchaseOrders} columns={[
              { title: '采购单号', dataIndex: 'poNumber' },
              { title: '待收数量', render: (_, order) => order.lines.reduce((sum, line) => sum + line.pendingQuantity, 0) },
              { title: '其中待补', render: (_, order) => order.lines.reduce((sum, line) => sum + line.replacementPendingQuantity, 0) },
              { title: '超收数量', render: (_, order) => order.lines.reduce((sum, line) => sum + line.overReceivedQuantity, 0) },
              { title: '审批锁定容差', render: (_, order) => `${order.receiptTolerancePercent}%` },
              { title: '操作', render: (_, order) => <Space wrap>
                {order.lines.some((line) => line.normalPendingQuantity > 0)
                  && <Button disabled={!!pending} onClick={() => startReceipt(order)}>确认本次到货</Button>}
                {order.lines.some((line) => line.replacementPendingQuantity > 0)
                  && <Button disabled={!!pending} onClick={() => startReceipt(order, true)}>补发收货</Button>}
              </Space> },
            ]} />
          </Card>}
          <Card title="收货记录">
            <Table<PurchaseReceipt> rowKey="receiptId" size="small" loading={receipts.isLoading}
              dataSource={(receipts.data ?? []).filter((receipt) => !sourceBatchNo || receipt.lines.some((line) => line.batchNo === sourceBatchNo))} columns={[
              { title: '收货编号', dataIndex: 'receiptId' },
              { title: '类型', render: (_, receipt) => receipt.replacement ? '补发收货' : '常规收货' },
              { title: '仓库', render: (_, receipt) => warehouses?.find((warehouse) => warehouse.warehouseId === receipt.warehouseId)?.name ?? receipt.warehouseId },
              { title: '实收数量', render: (_, receipt) => receipt.lines.reduce((sum, line) => sum + line.quantity, 0) },
              { title: '确认时间', render: (_, receipt) => new Date(receipt.receivedAt).toLocaleString() },
              { title: '操作', render: (_, receipt) => <Button onClick={() => setSelectedReceipt(receipt)}>批次与流水</Button> },
            ]} />
          </Card>
        </Space>
      ) }, { key: 'inspection', label: '质检', children: (
        <InspectionWork purchaseOrderId={purchaseOrderId} warehouses={warehouses ?? []} />
      ) }, { key: 'putaway', label: '上架', children: (
        <PurchasePutaway purchaseOrderId={purchaseOrderId} warehouses={warehouses ?? []} />
      ) }, { key: 'returns', label: '隔离与退供', children: (
        <PurchaseReturns purchaseOrderId={purchaseOrderId} returnId={returnId} warehouses={warehouses ?? []} />
      ) }]} />

      <Modal title={`${replacement ? '补发收货' : '确认到货'} ${selectedOrder?.poNumber ?? ''}`} open={!!selectedOrder}
        okText={pending ? '重试原确认' : '确认收货'}
        onOk={() => { if (pending) void sendReceipt(pending); else form.submit(); }} confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }} closable={!saving}
        maskClosable={false} keyboard={!saving}
        onCancel={() => { if (!saving) setSelectedOrder(undefined); }} destroyOnClose width={760}>
        {selectedOrder && <Typography.Paragraph>
          审批锁定容差 {selectedOrder.receiptTolerancePercent}%
          {selectedOrder.lines.some((line) => line.originalQuantity !== line.quantity)
            && ' · 变更后的补送按采购员确认的待收数量办理'}
        </Typography.Paragraph>}
        <Form form={form} layout="vertical" onFinish={confirmReceipt} disabled={saving || !!pending}>
          <Form.Item name="warehouseId" label="收货仓库" rules={[{ required: true, message: '请选择收货仓库' }]}>
            <Select options={(warehouses ?? []).map((warehouse) => ({ value: warehouse.warehouseId, label: warehouse.name }))} />
          </Form.Item>
          <Form.List name="receipts" initialValue={[{}]}>
            {(fields, { add, remove }) => <>
              {fields.map(({ key, name }) => <Space key={key} align="baseline" wrap>
                <Form.Item name={[name, 'lineId']} rules={[{ required: true, message: '请选择采购明细' }]}>
                  <Select aria-label="采购明细" placeholder="采购明细" style={{ width: 285 }} options={selectedOrder?.lines
                    .filter((line) => (replacement ? line.replacementReceivableQuantity : line.normalReceivableQuantity) > 0)
                    .map((line) => ({ value: line.lineId,
                      label: replacement
                        ? `${line.sellerSku}（待补 ${line.replacementPendingQuantity}，最多可收 ${line.replacementReceivableQuantity}）`
                        : `${line.sellerSku}（待收 ${line.normalPendingQuantity}，最多可收 ${line.normalReceivableQuantity}）`,
                    }))} />
                </Form.Item>
                <Form.Item name={[name, 'supplierBatchNo']}><Input maxLength={128} placeholder="供应商批号（选填）" /></Form.Item>
                <Form.Item name={[name, 'quantity']} rules={[{ required: true, message: '请填写实收件数' }]}>
                  <InputNumber min={1} max={2147483647} precision={0} placeholder="实收件数" />
                </Form.Item>
                <Button disabled={fields.length === 1} onClick={() => remove(name)}>移除</Button>
              </Space>)}
              <Button onClick={() => add()}>添加收货明细</Button>
            </>}
          </Form.List>
        </Form>
      </Modal>

      <Modal title="收货批次与流水" open={!!selectedReceipt} onCancel={() => setSelectedReceipt(undefined)}
        footer={null} width={900}>
        {selectedReceipt && <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Text>来源采购单 {sourceOrder.data?.poNumber ?? selectedReceipt.purchaseOrderId}</Typography.Text>
          {selectedReceipt.replacement && <Typography.Text>补发收货</Typography.Text>}
          {sourceOrder.error && <Alert type="error" message="采购单容差加载失败" />}
          {sourceOrder.data && <Typography.Text>
            审批锁定容差 {sourceOrder.data.receiptTolerancePercent}% · 采购单累计超收 {sourceOrder.data.overReceivedQuantity} 件
          </Typography.Text>}
          <ReceiptLabels receipt={selectedReceipt} />
          <ReceiptReturnAction key={selectedReceipt.receiptId} receipt={selectedReceipt} />
          <Table rowKey="batchNo" size="small" pagination={false} dataSource={selectedReceipt.lines} columns={[
            { title: 'SKU', dataIndex: 'sellerSku' },
            { title: '采购明细', dataIndex: 'purchaseOrderLineId' },
            { title: '供应商批号', dataIndex: 'supplierBatchNo' },
            { title: '内部批次', dataIndex: 'batchNo' },
            { title: '实收', dataIndex: 'quantity' },
            { title: '质检', render: (_, row) => <Button size="small" onClick={() => setInspectionBatch(row.batchNo)}>质检记录</Button> },
            { title: '库存', render: (_, row) => <Link href={`/wms/inventory?batchNo=${encodeURIComponent(row.batchNo)}`}>批次结存</Link> },
          ]} />
          {movements.error && <Alert type="error" message="库存流水加载失败" />}
          <Table<PurchaseReceiptMovement> rowKey="batchNo" size="small" pagination={false} loading={movements.isLoading}
            dataSource={movements.data ?? []} columns={[
              { title: '内部批次', dataIndex: 'batchNo' },
              { title: '入库数量', dataIndex: 'quantity' },
              { title: '转入状态', dataIndex: 'toStatus', render: (value: string) => value === 'PENDING_INSPECTION' ? '待检' : value },
              { title: '操作人', dataIndex: 'operatorId' },
              { title: '确认时间', render: (_, row) => new Date(row.receivedAt).toLocaleString() },
            ]} />
        </Space>}
      </Modal>
      {inspectionBatch && <InspectionDialog batchNo={inspectionBatch} onClose={() => setInspectionBatch(undefined)} />}
    </>
  );
}
