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

const receiptsApi = '/wms/api/in/v1/purchase-receipts';
type PendingReceipt = { orderId: string; request: PurchaseReceiptInput };

function createReceiptId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}

export default function WarehouseOperationsPage() {
  return <Suspense fallback={<Typography.Text>正在加载仓储作业…</Typography.Text>}><ReceiptWork /></Suspense>;
}

function ReceiptWork() {
  const purchaseOrderId = useSearchParams().get('purchaseOrderId');
  const [selectedOrder, setSelectedOrder] = useState<PurchaseOrder>();
  const [selectedReceipt, setSelectedReceipt] = useState<PurchaseReceipt>();
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

  useEffect(() => {
    const saved = sessionStorage.getItem(storageKey);
    setPending(saved ? JSON.parse(saved) as PendingReceipt : undefined);
  }, [storageKey]);

  const startReceipt = (order: PurchaseOrder) => {
    form.resetFields();
    setSelectedOrder(order);
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
      void sendReceipt({ orderId: selectedOrder.poId, request: { ...values, receiptId: createReceiptId() } });
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
      <Tabs items={[{ key: 'receiving', label: '收货', children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {work.data?.canReceive && <Card title="收货待办">
            <Table<PurchaseOrder> rowKey="poId" size="small" dataSource={purchaseOrders} columns={[
              { title: '采购单号', dataIndex: 'poNumber' },
              { title: '待收数量', render: (_, order) => order.lines.reduce((sum, line) => sum + line.quantity - line.receivedQuantity, 0) },
              { title: '操作', render: (_, order) => <Button disabled={!!pending} onClick={() => startReceipt(order)}>确认本次到货</Button> },
            ]} />
          </Card>}
          <Card title="收货记录">
            <Table<PurchaseReceipt> rowKey="receiptId" size="small" loading={receipts.isLoading} dataSource={receipts.data ?? []} columns={[
              { title: '收货编号', dataIndex: 'receiptId' },
              { title: '仓库', render: (_, receipt) => warehouses?.find((warehouse) => warehouse.warehouseId === receipt.warehouseId)?.name ?? receipt.warehouseId },
              { title: '实收数量', render: (_, receipt) => receipt.lines.reduce((sum, line) => sum + line.quantity, 0) },
              { title: '确认时间', render: (_, receipt) => new Date(receipt.receivedAt).toLocaleString() },
              { title: '操作', render: (_, receipt) => <Button onClick={() => setSelectedReceipt(receipt)}>批次与流水</Button> },
            ]} />
          </Card>
        </Space>
      ) }]} />

      <Modal title={`确认到货 ${selectedOrder?.poNumber ?? ''}`} open={!!selectedOrder}
        okText={pending ? '重试原确认' : '确认收货'}
        onOk={() => { if (pending) void sendReceipt(pending); else form.submit(); }} confirmLoading={saving}
        cancelButtonProps={{ disabled: saving }} closable={!saving}
        maskClosable={false} keyboard={!saving}
        onCancel={() => { if (!saving) setSelectedOrder(undefined); }} destroyOnClose width={760}>
        <Form form={form} layout="vertical" onFinish={confirmReceipt} disabled={saving || !!pending}>
          <Form.Item name="warehouseId" label="收货仓库" rules={[{ required: true, message: '请选择收货仓库' }]}>
            <Select options={(warehouses ?? []).map((warehouse) => ({ value: warehouse.warehouseId, label: warehouse.name }))} />
          </Form.Item>
          <Form.List name="receipts" initialValue={[{}]}>
            {(fields, { add, remove }) => <>
              {fields.map(({ key, name }) => <Space key={key} align="baseline" wrap>
                <Form.Item name={[name, 'lineId']} rules={[{ required: true, message: '请选择采购明细' }]}>
                  <Select placeholder="采购明细" style={{ width: 260 }} options={selectedOrder?.lines
                    .filter((line) => line.quantity > line.receivedQuantity)
                    .map((line) => ({ value: line.lineId, label: `${line.sellerSku}（待收 ${line.quantity - line.receivedQuantity}）` }))} />
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
          <Typography.Text>来源采购单 {selectedReceipt.purchaseOrderId}</Typography.Text>
          <ReceiptLabels receipt={selectedReceipt} />
          <Table rowKey="batchNo" size="small" pagination={false} dataSource={selectedReceipt.lines} columns={[
            { title: 'SKU', dataIndex: 'sellerSku' },
            { title: '采购明细', dataIndex: 'purchaseOrderLineId' },
            { title: '供应商批号', dataIndex: 'supplierBatchNo' },
            { title: '内部批次', dataIndex: 'batchNo' },
            { title: '实收', dataIndex: 'quantity' },
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
    </>
  );
}
