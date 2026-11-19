'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Checkbox, Descriptions, Form, Input, Modal, Space, Table, Typography } from 'antd';
import { isAxiosError } from 'axios';
import { useApi } from '@/lib/hooks';
import { post } from '@/lib/api/client';
import { useAuthStore } from '@/lib/store';
import type { Carton } from './fba-cartons';

interface Shipment { status: string; quantity: number; cartonCount: number; carrierCode: string; trackingNo: string; shippedAt: string; operatorId: string; syncStatus: string; syncError?: string }
interface Preparation { workStatus: string; requiredQuantity: number; packedQuantity: number; plannedCartonCount: number; packedCartonCount: number; missingLabelCartonCodes: string[]; readyToShip: boolean; shipment?: Shipment }
interface BindValues { cartonCode: string; fbaBoxNumber: string; labelApplied: boolean }
interface ShipValues { carrierCode: string; trackingNo: string; confirmDispatch: boolean }
type Action = { outboundId: string } & ({ kind: 'bindings'; body: BindValues } | { kind: 'ship'; body: ShipValues } | { kind: 'sync'; body?: never });
const endpoint = '/wms/api/in/v1/fba-dispatch';

export function FbaDispatch({ outboundId, onChange }: { outboundId: string; onChange: () => Promise<unknown> }) {
  const tenant = useAuthStore(s => s.tenantId);
  const storageKey = `wms:pending-fba-dispatch:${tenant}`;
  const work = useApi<{ canBind: boolean; canShip: boolean; preparation: Preparation }>(`${endpoint}/${outboundId}`);
  const cartons = useApi<{ cartons: Carton[] }>(`/wms/api/in/v1/fba-cartons?outboundId=${outboundId}`);
  const [selected, setSelected] = useState<Carton>();
  const [shipping, setShipping] = useState(false);
  const [pending, setPending] = useState<Action>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const submitting = useRef(false);
  const preparation = work.data?.preparation;
  const shipment = preparation?.shipment;
  useEffect(() => {
    try { const value = sessionStorage.getItem(storageKey); setPending(value ? JSON.parse(value) as Action : undefined); }
    catch { setError('待确认的贴标或出库记录无法读取，请核对原作业。'); }
  }, [storageKey]);
  const submit = async (attempt: Action) => {
    if (submitting.current) return;
    submitting.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt)); setPending(attempt);
      await post(`${endpoint}/${attempt.outboundId}/${attempt.kind}`, attempt.body);
      sessionStorage.removeItem(storageKey); setPending(undefined); setSelected(undefined); setShipping(false);
      await Promise.all([work.mutate(), cartons.mutate(), onChange()]);
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(storageKey); setPending(undefined);
      }
      setError(response?.data?.message ?? '操作结果未确认，请重试原确认；出库不会重复扣减库存。');
    } finally { submitting.current = false; setSaving(false); }
  };
  return <Card title="FBA 箱标与整单出库">
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {(work.error || cartons.error) && <Alert type="error" showIcon message="FBA 出库作业加载失败" action={<Button onClick={() => { void work.mutate(); void cartons.mutate(); }}>重试加载</Button>} />}
      {error && <Alert type="error" showIcon message={error} />}
      {pending && <Alert type="warning" showIcon message="有一笔贴标或出库结果待确认" action={<Button loading={saving}
        disabled={pending.kind === 'bindings' ? !work.data?.canBind : !work.data?.canShip} onClick={() => void submit(pending)}>重试原确认</Button>} />}
      {preparation && <>
        <Descriptions size="small" items={[
          { key: 'plan', label: 'Seller Central 计划箱数', children: preparation.plannedCartonCount || '尚未登记' },
          { key: 'packed', label: '有效实装箱数', children: preparation.packedCartonCount },
          { key: 'quantity', label: '实装 / 应发件数', children: `${preparation.packedQuantity} / ${preparation.requiredQuantity}` },
        ]} />
        {!shipment && preparation.missingLabelCartonCodes.length > 0 && <Alert type="warning" showIcon
          message={`尚未完成贴标绑定的内部箱号：${preparation.missingLabelCartonCodes.join('、')}`} />}
        {!shipment && !preparation.readyToShip && <Alert type="info" showIcon message="整单保持待发货。须完成计划箱数、实拣与实装核对，并给每个有效外箱贴标绑定；质检暂停时不能出库。" />}
      </>}
      <Table<Carton> rowKey="cartonId" dataSource={cartons.data?.cartons ?? []} loading={cartons.isLoading} size="small" scroll={{ x: 'max-content' }} columns={[
        { title: '内部箱号', dataIndex: 'cartonCode' }, { title: 'FBA 箱号', render: (_, c) => c.fbaBoxNumber ?? '尚未绑定' },
        { title: '贴标确认', render: (_, c) => c.status === 'CANCELLED' ? '原箱已作废' : c.bindingActive ? `${c.boundBy} · ${new Date(c.boundAt!).toLocaleString()}` : '尚未确认' },
        { title: '操作', render: (_, c) => work.data?.canBind && !c.bindingActive && !['CANCELLED', 'SHIPPED'].includes(c.status)
          ? <Button disabled={!!pending || saving} onClick={() => { setSelected(c); setError(undefined); }}>核对箱号并确认贴标</Button> : null },
      ]} />
      {shipment ? <>
        <Alert type={shipment.syncStatus === 'SYNCED' ? 'success' : 'warning'} showIcon
          message={`仓库已整单出库 ${shipment.cartonCount} 箱 / ${shipment.quantity} 件 · ${shipment.syncStatus === 'SYNCED' ? 'FBA 已收到出库结果' : 'FBA 回写待重试'}`} />
        <Typography.Text>承运商 {shipment.carrierCode} · 运单 {shipment.trackingNo} · {shipment.operatorId} · {new Date(shipment.shippedAt).toLocaleString()}</Typography.Text>
        {shipment.syncStatus !== 'SYNCED' && work.data?.canShip && <Button disabled={!!pending} loading={saving}
          onClick={() => void submit({ kind: 'sync', outboundId })}>重试 FBA 回写</Button>}
      </> : work.data?.canShip && <Button type="primary" disabled={!preparation?.readyToShip || !!pending || saving}
        onClick={() => { setShipping(true); setError(undefined); }}>确认整单实际出库</Button>}
    </Space>
    {selected && <BindDialog carton={selected} saving={saving} pending={!!pending} error={error} onClose={() => setSelected(undefined)}
      onSubmit={values => void submit(pending ?? { kind: 'bindings', outboundId, body: values })} />}
    {shipping && <ShipDialog preparation={preparation!} saving={saving} pending={!!pending} error={error} onClose={() => setShipping(false)}
      onSubmit={values => void submit(pending ?? { kind: 'ship', outboundId, body: values })} />}
  </Card>;
}

function BindDialog({ carton, saving, pending, error, onClose, onSubmit }: {
  carton: Carton; saving: boolean; pending: boolean; error?: string; onClose: () => void; onSubmit: (values: BindValues) => void;
}) {
  const [form] = Form.useForm<BindValues>();
  return <Modal open title="核对箱号并确认贴标" onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={pending ? '重试原确认' : '确认贴标绑定'} onOk={() => form.submit()}>
    <Typography.Paragraph>目标内部箱号：{carton.cartonCode}</Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} />}
    <Form form={form} layout="vertical" disabled={saving || pending} onFinish={onSubmit}>
      <Form.Item label="核对内部箱号" name="cartonCode" rules={[{ required: true, whitespace: true }, { validator: async (_, value) => { if (value !== carton.cartonCode) throw new Error('请核对当前实物外箱'); } }]}><Input aria-label="核对内部箱号" maxLength={64} autoFocus /></Form.Item>
      <Form.Item label="核对 FBA 箱号" name="fbaBoxNumber" rules={[{ required: true, whitespace: true }]}><Input aria-label="核对 FBA 箱号" maxLength={128} /></Form.Item>
      <Form.Item name="labelApplied" valuePropName="checked" rules={[{ validator: async (_, value) => { if (value !== true) throw new Error('请确认对应箱标已实际贴到该箱'); } }]}>
        <Checkbox>已将对应 FBA 箱标实际贴到该内部箱</Checkbox>
      </Form.Item>
    </Form>
  </Modal>;
}

function ShipDialog({ preparation, saving, pending, error, onClose, onSubmit }: {
  preparation: Preparation; saving: boolean; pending: boolean; error?: string; onClose: () => void; onSubmit: (values: ShipValues) => void;
}) {
  const [form] = Form.useForm<ShipValues>();
  return <Modal open title="确认 FBA 整单实际出库" onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={pending ? '重试原确认' : '确认整单出库'} onOk={() => form.submit()}>
    <Typography.Paragraph>本次整单出库 {preparation.packedCartonCount} 箱 / {preparation.packedQuantity} 件，按箱内实际批次扣减库存。</Typography.Paragraph>
    {error && <Alert type="error" showIcon message={error} />}
    <Form form={form} layout="vertical" disabled={saving || pending} onFinish={onSubmit}>
      <Form.Item label="承运商" name="carrierCode" rules={[{ required: true, whitespace: true }]}><Input aria-label="承运商" maxLength={64} /></Form.Item>
      <Form.Item label="运单号" name="trackingNo" rules={[{ required: true, whitespace: true }]}><Input aria-label="运单号" maxLength={128} /></Form.Item>
      <Form.Item name="confirmDispatch" valuePropName="checked" rules={[{ validator: async (_, value) => { if (value !== true) throw new Error('请确认整单已实际交运'); } }]}>
        <Checkbox>整单货物已实际交给承运商</Checkbox>
      </Form.Item>
    </Form>
  </Modal>;
}
