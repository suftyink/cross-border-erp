'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Space, Table, Tag, Typography } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import type { Warehouse } from '@/types';
import { newOperationId } from './operation-id';
import { SalesDispatch } from './sales-dispatch';
import { ShortPickWork } from './short-pick-work';

interface Allocation {
  allocationId: string; lineId: string; salesLineId: string; sellerSku: string;
  locationId: string; locationCode: string; batchNo: string; receiptId: string; purchaseOrderId: string;
  plannedQuantity: number; pickedQuantity: number; shortQuantity: number;
}
interface ActualPick extends PickValues {
  requestId: string; allocationId: string; receiptId: string; purchaseOrderId: string;
  sellerSku: string; operatorId: string; createdAt: string;
}
interface Work {
  outboundId: string; salesOrderId: string; warehouseId: string; status: string; requiredQuantity: number; allowBatchSubstitution: boolean;
  allocations: Allocation[]; picks: ActualPick[];
}
type PickValues = { locationCode: string; batchNo: string; quantity: number };
type Attempt = PickValues & { requestId: string; allocationId: string; outboundId: string };
const endpoint = '/wms/api/in/v1/sales-picking';
const labels: Record<string, string> = { RESERVED: '已预占待拣', PICKING: '拣货中', PICKED: '已拣齐待发货', SHORT_PICK: '缺拣待处理', PARTIALLY_SHIPPED: '部分已发货', SHIPPED: '已全部发货' };

export function SalesPicking({ salesOrderId, outboundId, warehouses }: { salesOrderId: string | null; outboundId: string | null; warehouses: Warehouse[] }) {
  const tenant = useAuthStore((s) => s.tenantId);
  const storageKey = `wms:pending-sales-pick:${tenant}`;
  const work = useApi<{ canPick: boolean; canShip: boolean; orders: Work[] }>(`${endpoint}/work${salesOrderId ? `?salesOrderId=${encodeURIComponent(salesOrderId)}` : ''}`);
  const [selectedId, setSelectedId] = useState<string | undefined>(outboundId ?? undefined);
  const selected = work.data?.orders.find((o) => o.outboundId === selectedId);
  const [allocation, setAllocation] = useState<Allocation>();
  const [pending, setPending] = useState<Attempt>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const submitting = useRef(false);

  useEffect(() => { setSelectedId(outboundId ?? undefined); }, [outboundId, salesOrderId]);

  useEffect(() => {
    const stored = sessionStorage.getItem(storageKey);
    try { setPending(stored ? JSON.parse(stored) as Attempt : undefined); }
    catch { setError('待确认拣货记录无法读取，请联系管理员核对。'); }
  }, [storageKey]);

  const submit = async (attempt: Attempt) => {
    if (submitting.current) return;
    submitting.current = true;
    setSaving(true);
    setError(undefined);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt));
      setPending(attempt);
      const { outboundId, ...body } = attempt;
      await post(`${endpoint}/${outboundId}/picks`, body);
      sessionStorage.removeItem(storageKey);
      setPending(undefined);
      setAllocation(undefined);
      setSelectedId(outboundId);
      await work.mutate();
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(storageKey);
        setPending(undefined);
      }
      setError(response?.data?.message ?? '实拣结果未确认，请重试原操作。');
    } finally {
      submitting.current = false;
      setSaving(false);
    }
  };

  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="按计划扫描实际库位与批次，可分多次登记。已拣未发货物在发货暂存区，计入在手并保留订单预占。" />
    {work.error && <Alert type="error" showIcon message="拣货待办加载失败" action={<Button onClick={() => void work.mutate()}>重试加载</Button>} />}
    {error && <Alert type="error" showIcon message={error} />}
    {work.data?.canPick === false && <Alert type="info" showIcon message="当前账号没有实拣登记权限" />}
    {pending && <Alert type="warning" showIcon message={`实拣结果待确认 · ${pending.locationCode} · ${pending.quantity} 件`}
      action={<Button loading={saving} disabled={!work.data?.canPick} onClick={() => void submit(pending)}>重试原实拣</Button>} />}
    {(work.isLoading || work.data?.canPick || work.data?.canShip) && <Card title="销售拣货待办">
      <Table<Work> rowKey="outboundId" loading={work.isLoading} dataSource={work.data?.orders ?? []} scroll={{ x: 'max-content' }} columns={[
        { title: '出库单', dataIndex: 'outboundId' },
        { title: '销售订单', render: (_, row) => <Link href={`/oms/orders?orderId=${encodeURIComponent(row.salesOrderId)}`}>{row.salesOrderId}</Link> },
        { title: '仓库', render: (_, row) => warehouses.find((w) => w.warehouseId === row.warehouseId)?.name ?? row.warehouseId },
        { title: '状态', render: (_, row) => <Tag>{labels[row.status] ?? row.status}</Tag> },
        { title: '应拣 / 已拣', render: (_, row) => `${row.requiredQuantity} / ${row.allocations.reduce((n, a) => n + a.pickedQuantity, 0)}` },
        { title: '操作', render: (_, row) => <Button onClick={() => setSelectedId(row.outboundId)}>查看拣货作业</Button> },
      ]} />
    </Card>}
    {selected && <Card title="计划分配与实拣记录">
      <Descriptions size="small" items={[
        { key: 'order', label: '销售订单', children: selected.salesOrderId },
        { key: 'status', label: '状态', children: labels[selected.status] },
        { key: 'staging', label: '实物去向', children: selected.status === 'SHIPPED' ? '已实际交运'
          : selected.status === 'PARTIALLY_SHIPPED' ? '部分已交运，其余发货暂存' : '发货暂存区' },
      ]} />
      <Table<Allocation> size="small" rowKey="allocationId" dataSource={selected.allocations} pagination={false} scroll={{ x: 'max-content' }} columns={[
        { title: '订单行', dataIndex: 'salesLineId' }, { title: 'SKU', dataIndex: 'sellerSku' },
        { title: '计划库位', dataIndex: 'locationCode' }, { title: '计划批次', dataIndex: 'batchNo' },
        { title: '计划', dataIndex: 'plannedQuantity' }, { title: '已拣', dataIndex: 'pickedQuantity' },
        { title: '缺拣', dataIndex: 'shortQuantity' },
        { title: '待拣', render: (_, row) => row.plannedQuantity - row.pickedQuantity - row.shortQuantity },
        { title: '采购来源', render: (_, row) => <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(row.purchaseOrderId)}&batchNo=${encodeURIComponent(row.batchNo)}`}>来源收货批次</Link> },
        { title: '操作', render: (_, row) => <Button disabled={!work.data?.canPick || !!pending || row.pickedQuantity + row.shortQuantity === row.plannedQuantity}
          onClick={() => { setAllocation(row); setError(undefined); }}>登记实拣</Button> },
      ]} />
      <Typography.Title level={5}>实际拣货记录</Typography.Title>
      <Table<ActualPick> rowKey="requestId" dataSource={selected.picks} size="small" pagination={false} scroll={{ x: 'max-content' }} columns={[
        { title: '实拣库位', dataIndex: 'locationCode' }, { title: '实拣批次', dataIndex: 'batchNo' },
        { title: 'SKU', dataIndex: 'sellerSku' }, { title: '件数', dataIndex: 'quantity' },
        { title: '操作人', dataIndex: 'operatorId' },
        { title: '确认时间', render: (_, row) => new Date(row.createdAt).toLocaleString() },
        { title: '来源', render: (_, row) => <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(row.purchaseOrderId)}&batchNo=${encodeURIComponent(row.batchNo)}`}>采购收货批次</Link> },
      ]} />
    </Card>}
    {selected && <ShortPickWork key={`review-${selected.outboundId}`} source={selected} />}
    {selected && <SalesDispatch key={selected.outboundId} outboundId={selected.outboundId} picks={selected.picks}
      canShip={!!work.data?.canShip} onChange={() => work.mutate()} />}
    {selected && allocation && <PickDialog allocation={allocation} saving={saving} pending={pending} error={error}
      onClose={() => setAllocation(undefined)} onSubmit={(values) => void submit(pending ?? {
        ...values, outboundId: selected.outboundId, allocationId: allocation.allocationId, requestId: newOperationId(),
      })} />}
  </Space>;
}

function PickDialog({ allocation, saving, pending, error, onClose, onSubmit }: {
  allocation: Allocation; saving: boolean; pending?: Attempt; error?: string;
  onClose: () => void; onSubmit: (values: PickValues) => void;
}) {
  const [form] = Form.useForm<PickValues>();
  const remaining = allocation.plannedQuantity - allocation.pickedQuantity - allocation.shortQuantity;
  return <Modal open title="登记实际拣货" onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={pending ? '重试原实拣' : '确认实拣'}
    onOk={() => pending ? onSubmit(pending) : form.submit()}>
    <Typography.Paragraph>{allocation.sellerSku} · 计划库位 {allocation.locationCode} · 待拣 {remaining} 件</Typography.Paragraph>
    <Typography.Paragraph copyable>{allocation.batchNo}</Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} />}
    <Form form={form} layout="vertical" disabled={saving || !!pending} onFinish={onSubmit}>
      <Form.Item name="locationCode" label="实际库位码" rules={[{ required: true, whitespace: true, message: '请扫描或输入实际库位码' }]}>
        <Input aria-label="实际库位码" maxLength={128} autoFocus />
      </Form.Item>
      <Form.Item name="batchNo" label="实物批次码" rules={[{ required: true, whitespace: true, message: '请扫描或输入实物批次码' }]}>
        <Input aria-label="实物批次码" maxLength={64} />
      </Form.Item>
      <Form.Item name="quantity" label="本次实拣件数" rules={[{ required: true }, { type: 'integer', min: 1, max: remaining }]}>
        <InputNumber aria-label="本次实拣件数" min={1} max={remaining} precision={0} />
      </Form.Item>
    </Form>
  </Modal>;
}
