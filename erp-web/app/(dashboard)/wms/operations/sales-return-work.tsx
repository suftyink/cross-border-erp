'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Card, Checkbox, Descriptions, Form, Input, InputNumber, Modal, Space, Table, Tag, Typography } from 'antd';
import Link from 'next/link';
import { useApi } from '@/lib/hooks';
import { salesReturnLabels } from '@/components/sales-return-types';
import type { SalesReturnOrder, SalesReturnReceipt } from '@/components/sales-return-types';
import { useReturnOperation } from '@/components/use-return-operation';
import { newOperationId } from './operation-id';

const endpoint = '/wms/api/in/v1/sales-returns';
export function SalesReturnWork({ returnId }: { returnId: string | null }) {
  const work = useApi<{ canReceive: boolean; orders: SalesReturnOrder[] }>(`${endpoint}/work`);
  const [selectedId, setSelectedId] = useState(returnId ?? undefined);
  const selected = work.data?.orders.find((o) => o.returnId === selectedId);
  const receipts = useApi<SalesReturnReceipt[]>(selected ? `${endpoint}/${selected.returnId}/receipts` : null);
  const [receiving, setReceiving] = useState(false);
  useEffect(() => { setSelectedId(returnId ?? undefined); }, [returnId]);
  const operation = useReturnOperation<SalesReturnReceipt>('warehouse', async (receipt) => {
    setReceiving(false); setSelectedId(receipt.returnId); await work.mutate(); await receipts.mutate();
  });
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="按销售退货单核对实物批次，可分次登记。实收进入待检，售后核实后更新召回进度。" />
    {work.error && <Alert type="error" message="销售退货待办加载失败" action={<Button onClick={() => void work.mutate()}>重试加载</Button>} />}
    {operation.error && <Alert type="error" showIcon message={operation.error} />}
    {operation.pending && <Alert type="warning" showIcon message={`${operation.pending.label}结果待确认`}
      action={<Button loading={operation.saving} disabled={!work.data?.canReceive} onClick={() => void operation.send(operation.pending!)}>重试原退货收货</Button>} />}
    {work.data?.canReceive === false && <Alert type="info" message="当前账号没有销售退货收货权限" />}
    <Card title="销售退货收货" extra={<Button onClick={() => { void work.mutate(); void receipts.mutate(); }}>刷新退货</Button>}>
      <Table<SalesReturnOrder> rowKey="returnId" loading={work.isLoading} dataSource={work.data?.orders ?? []} tableLayout="fixed" scroll={{ x: 1400 }} columns={[
        { title: '销售退货单', dataIndex: 'returnId', width: 220, ellipsis: true }, { title: 'SKU', dataIndex: 'sellerSku', width: 110 },
        { title: '目标批次', dataIndex: 'expectedBatchNo', width: 230, ellipsis: true },
        { title: '应退', dataIndex: 'quantity', width: 75 }, { title: '实收', dataIndex: 'receivedQuantity', width: 75 },
        { title: '已核实批次', dataIndex: 'confirmedQuantity', width: 100 }, { title: '批次不明实收', dataIndex: 'unidentifiedQuantity', width: 110 }, { title: '待收', dataIndex: 'pendingReceiptQuantity', width: 75 },
        { title: '状态', width: 160, render: (_, row) => <Tag>{salesReturnLabels[row.status]}</Tag> },
        { title: '操作', width: 210, fixed: 'right', render: (_, row) => <Space>
          <Button onClick={() => setSelectedId(row.returnId)}>收货记录</Button>
          {work.data?.canReceive && <Button disabled={operation.disabled || row.pendingReceiptQuantity <= 0} onClick={() => { setSelectedId(row.returnId); setReceiving(true); operation.setError(undefined); }}>登记退货实收</Button>}
        </Space> },
      ]} />
    </Card>
    {selected && <Card title="销售退货来源与实收记录">
      {selected.recallClosed && <Alert type="warning" showIcon message="原召回明细已异常结案；已有退货单的实物仍可继续登记，隔离及待检库存继续冻结。" />}
      <Descriptions size="small" column={{ xs: 1, md: 2 }} items={[
        { key: 'id', label: '退货单', children: selected.returnId }, { key: 'warehouse', label: '退回仓库（原发货仓）', children: selected.warehouseId },
        { key: 'batch', label: '目标批次', children: <Typography.Text copyable>{selected.expectedBatchNo}</Typography.Text> },
        { key: 'progress', label: '应退 / 实收 / 已核实', children: `${selected.quantity} / ${selected.receivedQuantity} / ${selected.confirmedQuantity}` },
      ]} />
      <Space wrap style={{ marginBottom: 16 }}>
        <Link href={`/crm/recalls?recallId=${selected.recallId}&returnId=${selected.returnId}`}>召回与售后核实</Link>
        <Link href={`/oms/orders?orderId=${selected.salesOrderId}`}>原销售订单</Link>
        <Link href={`/wms/operations?outboundId=${selected.outboundId}`}>原出库作业</Link>
        <Link href={`/wms/inventory?tab=movements&documentId=${selected.returnId}`}>退货收货流水</Link>
      </Space>
      {receipts.error && <Alert type="error" message="退货收货记录加载失败" />}
      <Table<SalesReturnReceipt> rowKey="receiptId" size="small" pagination={false} loading={receipts.isLoading} dataSource={receipts.data ?? []} scroll={{ x: 'max-content' }} columns={[
        { title: '本次收货编号', dataIndex: 'receiptId' }, { title: '原实收批次', render: (_, row) => row.batchNo ?? '批次不明' }, { title: '核实归属批次', dataIndex: 'identifiedBatchNo' },
        { title: '隔离库位', dataIndex: 'quarantineLocationCode' }, { title: '批次不明原因', dataIndex: 'unidentifiedReason' },
        { title: '实收件数', dataIndex: 'quantity' }, { title: '库存状态', render: (_, row) => row.unidentified ? '隔离待核实' : row.quarantineLocationCode ? '原隔离库位待检' : '待检区' },
        { title: '收货人', dataIndex: 'operatorId' }, { title: '时间', render: (_, row) => new Date(row.receivedAt).toLocaleString() },
      ]} />
    </Card>}
    {selected && receiving && <ReturnReceiptDialog key={selected.returnId} order={selected} operation={operation} onClose={() => setReceiving(false)} />}
  </Space>;
}

function ReturnReceiptDialog({ order, operation, onClose }: { order: SalesReturnOrder; operation: ReturnType<typeof useReturnOperation<SalesReturnReceipt>>; onClose: () => void }) {
  const [form] = Form.useForm();
  const unidentified = Form.useWatch('unidentified', form);
  return <Modal open title="登记销售退货实收" onCancel={onClose} maskClosable={false} closable={!operation.saving} keyboard={!operation.saving}
    cancelButtonProps={{ disabled: operation.saving }} confirmLoading={operation.saving} okText={operation.pending ? '重试原退货收货' : '确认退货实收'}
    onOk={() => operation.pending ? void operation.send(operation.pending) : form.submit()}>
    <Typography.Paragraph>{order.sellerSku} · 待收 {order.pendingReceiptQuantity} 件</Typography.Paragraph>
    <Typography.Paragraph copyable>{order.expectedBatchNo}</Typography.Paragraph>
    {operation.error && <Alert type="error" showIcon message={operation.error} />}
    <Form form={form} layout="vertical" disabled={operation.disabled} onFinish={(values) => void operation.send({ path: `${endpoint}/${order.returnId}/receipts`,
      body: { receiptId: newOperationId(), quantity: values.quantity, ...(values.unidentified ? { unidentified: true, quarantineLocationCode: values.quarantineLocationCode, unidentifiedReason: values.unidentifiedReason } : { batchNo: values.batchNo }) }, label: '退货收货', permission: 'receive' })}>
      <Form.Item name="unidentified" valuePropName="checked"><Checkbox>批次不明，先隔离待核实</Checkbox></Form.Item>
      {!unidentified && <Form.Item name="batchNo" label="实物批次码" rules={[{ required: true, whitespace: true }]}><Input aria-label="实物批次码" maxLength={64} autoFocus /></Form.Item>}
      {unidentified && <>
        <Form.Item name="quarantineLocationCode" label="实际隔离库位码" rules={[{ required: true, whitespace: true }]}><Input aria-label="实际隔离库位码" maxLength={128} /></Form.Item>
        <Form.Item name="unidentifiedReason" label="批次不明原因" rules={[{ required: true, whitespace: true }]}><Input.TextArea aria-label="批次不明原因" maxLength={500} rows={2} /></Form.Item>
      </>}
      <Form.Item name="quantity" label="本次退货实收件数" rules={[{ required: true }, { type: 'integer', min: 1, max: order.pendingReceiptQuantity }]}>
        <InputNumber aria-label="本次退货实收件数" min={1} max={order.pendingReceiptQuantity} precision={0} />
      </Form.Item>
    </Form>
  </Modal>;
}
