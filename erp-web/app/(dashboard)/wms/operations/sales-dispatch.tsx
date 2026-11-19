'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import { WarehouseShipmentLines } from '@/components/warehouse-shipment-lines';
import type { WarehouseShipment } from '@/types/warehouse-shipment';
import { newOperationId } from './operation-id';

interface Pick { requestId: string; sellerSku: string; locationCode: string; batchNo: string; quantity: number; returnedQuantity: number }
type Action = { kind: 'pack' } | { kind: 'weight' | 'ship'; shipment: WarehouseShipment };
type Attempt = { path: string; body: unknown; label: string };
const endpoint = '/wms/api/in/v1/sales-dispatch';
const labels: Record<string, string> = { PACKED: '已打包待称重', WEIGHED: '已称重待发货', SHIPPED: '已实际发货', CANCELLED: '已作废（质检撤拣）' };

export function SalesDispatch({ outboundId, picks, canShip, onChange }: {
  outboundId: string; picks: Pick[]; canShip: boolean; onChange: () => Promise<unknown>;
}) {
  const tenant = useAuthStore((s) => s.tenantId);
  const key = `wms:pending-sales-dispatch:${tenant}:${outboundId}`;
  const packages = useApi<WarehouseShipment[]>(`${endpoint}/outbounds/${outboundId}/packages`);
  const [action, setAction] = useState<Action>();
  const [pending, setPending] = useState<Attempt>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const busy = useRef(false);
  useEffect(() => {
    try { const stored = sessionStorage.getItem(key); setPending(stored ? JSON.parse(stored) as Attempt : undefined); }
    catch { setError('待确认作业无法读取，请联系管理员核对。'); }
    setAction(undefined);
  }, [key]);
  const send = async (attempt: Attempt) => {
    if (busy.current) return;
    busy.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(key, JSON.stringify(attempt)); setPending(attempt);
      await post<WarehouseShipment>(attempt.path, attempt.body);
      sessionStorage.removeItem(key); setPending(undefined); setAction(undefined);
      await packages.mutate(); await onChange();
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(key); setPending(undefined);
      }
      setError(response?.data?.message ?? '作业结果未确认，请重试原作业。');
    } finally { busy.current = false; setSaving(false); }
  };
  const available = picks.map((pick) => ({ ...pick, remaining: pick.quantity - pick.returnedQuantity - (packages.data ?? []).filter((p) => p.status !== 'CANCELLED')
    .flatMap((p) => p.lines).filter((l) => l.pickId === pick.requestId).reduce((sum, l) => sum + l.quantity, 0) }));
  return <Card title="包裹打包与实际发货" extra={canShip && <Button type="primary" disabled={!!pending || !available.some((p) => p.remaining > 0)}
    onClick={() => { setError(undefined); setAction({ kind: 'pack' }); }}>新建包裹</Button>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text>按实拣记录装箱，称重后确认实际发货。发货时扣减暂存库存并回写订单。</Typography.Text>
      {packages.error && <Alert type="error" showIcon message="包裹加载失败" action={<Button onClick={() => void packages.mutate()}>刷新包裹</Button>} />}
      {error && <Alert type="error" showIcon message={error} />}
      {pending && <Alert type="warning" showIcon message={`${pending.label}结果待确认`}
        action={<Button loading={saving} disabled={!canShip} onClick={() => void send(pending)}>重试原作业</Button>} />}
      <Table<WarehouseShipment> rowKey="packageId" loading={packages.isLoading} dataSource={packages.data ?? []} scroll={{ x: 'max-content' }}
        expandable={{ expandedRowRender: (row) => <WarehouseShipmentLines lines={row.lines} /> }} columns={[
          { title: '包裹编号', dataIndex: 'packageId' },
          { title: '件数', render: (_, row) => row.lines.reduce((n, l) => n + l.quantity, 0) },
          { title: '状态', render: (_, row) => <Tag>{labels[row.status] ?? row.status}</Tag> },
          { title: '重量 kg', dataIndex: 'weightKg' }, { title: '承运商', dataIndex: 'carrierCode' }, { title: '运单号', dataIndex: 'trackingNo' },
          { title: '发货时间', render: (_, row) => row.shippedAt ? new Date(row.shippedAt).toLocaleString() : '—' },
          { title: 'OMS 回写', render: (_, row) => row.omsSyncStatus === 'SYNCED' ? '已回写' : row.status === 'SHIPPED' ? row.syncError ?? '待回写' : '—' },
          { title: '操作', render: (_, row) => <Space wrap>
            {canShip && row.status !== 'SHIPPED' && row.status !== 'CANCELLED' && <Button disabled={!!pending} onClick={() => { setError(undefined); setAction({ kind: 'weight', shipment: row }); }}>称重</Button>}
            {canShip && row.status === 'WEIGHED' && <Button disabled={!!pending} onClick={() => { setError(undefined); setAction({ kind: 'ship', shipment: row }); }}>确认实际发货</Button>}
            {canShip && row.status === 'SHIPPED' && row.omsSyncStatus !== 'SYNCED' && <Button disabled={!!pending || saving}
              onClick={() => void send({ path: `${endpoint}/packages/${row.packageId}/sync`, body: {}, label: '订单回写' })}>重试订单回写</Button>}
            <Link href={`/oms/orders?orderId=${encodeURIComponent(row.salesOrderId)}&tab=warehouse`}>查看订单履约</Link>
            <Link href={`/wms/inventory?tab=shipments&packageId=${encodeURIComponent(row.packageId)}`}>出库追溯</Link>
          </Space> },
        ]} />
    </Space>
    {action && <DispatchDialog key={action.kind + ('shipment' in action ? action.shipment.packageId : '')} action={action}
      available={available} saving={saving} pending={pending} error={error} onClose={() => setAction(undefined)} onSubmit={(values) => {
        if (pending) { void send(pending); return; }
        const attempt = action.kind === 'pack'
          ? { path: `${endpoint}/outbounds/${outboundId}/packages`, body: { packageId: newOperationId(), ...values }, label: '装箱' }
          : { path: `${endpoint}/packages/${action.shipment.packageId}/${action.kind === 'weight' ? 'weight' : 'ship'}`, body: values, label: action.kind === 'weight' ? '称重' : '实际发货' };
        void send(attempt);
      }} />}
  </Card>;
}

function DispatchDialog({ action, available, saving, pending, error, onClose, onSubmit }: {
  action: Action; available: (Pick & { remaining: number })[]; saving: boolean; pending?: Attempt; error?: string;
  onClose: () => void; onSubmit: (values: Record<string, unknown>) => void;
}) {
  const [form] = Form.useForm();
  const title = action.kind === 'pack' ? '新建销售包裹' : action.kind === 'weight' ? '包裹称重' : '确认实际发货';
  return <Modal open title={title} onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={pending ? '重试原作业' : title}
    onOk={() => pending ? onSubmit({}) : form.submit()} width={720}>
    {error && <Alert type="error" showIcon message={error} />}
    {'shipment' in action && <Typography.Paragraph>包裹 {action.shipment.packageId} · {action.shipment.lines.reduce((n, l) => n + l.quantity, 0)} 件</Typography.Paragraph>}
    <Form form={form} layout="vertical" onFinish={onSubmit} disabled={saving || !!pending}
      initialValues={action.kind === 'pack' ? { lines: [{}] } : action.kind === 'weight' ? { weightKg: action.shipment.weightKg } : undefined}>
      {action.kind === 'pack' && <Form.List name="lines">{(fields, { add, remove }) => <>
        {fields.map((field) => <div key={field.key}>
          <Form.Item name={[field.name, 'pickId']} label="装入的实拣记录" rules={[{ required: true, message: '请选择已完成的实拣记录' }]}>
            <Select aria-label={`实拣记录 ${field.name + 1}`} options={available.filter((p) => p.remaining > 0).map((p) => ({ value: p.requestId,
              label: `${p.sellerSku} · ${p.locationCode} · ${p.batchNo} · 未装 ${p.remaining} 件` }))} />
          </Form.Item>
          <Form.Item name={[field.name, 'quantity']} label="装入件数" rules={[{ required: true }, { type: 'integer', min: 1 }]}>
            <InputNumber aria-label={`装入件数 ${field.name + 1}`} min={1} precision={0} />
          </Form.Item>
          {fields.length > 1 && <Button onClick={() => remove(field.name)}>移除明细</Button>}
        </div>)}
        <Button onClick={() => add()}>添加实拣明细</Button>
      </>}</Form.List>}
      {action.kind === 'weight' && <Form.Item name="weightKg" label="实际重量 kg" rules={[{ required: true }, { type: 'number', min: 0.001, max: 9999999.999 }]}>
        <InputNumber aria-label="实际重量 kg" min={0.001} max={9999999.999} precision={3} />
      </Form.Item>}
      {action.kind === 'ship' && <>
        <Alert type="warning" showIcon message="确认货物已实际交运后提交，系统将按包裹实拣明细扣减库存。" />
        <Form.Item name="carrierCode" label="承运商编码" rules={[{ required: true, whitespace: true }]}><Input aria-label="承运商编码" maxLength={64} /></Form.Item>
        <Form.Item name="trackingNo" label="实际运单号" rules={[{ required: true, whitespace: true }]}><Input aria-label="实际运单号" maxLength={128} /></Form.Item>
      </>}
    </Form>
  </Modal>;
}
