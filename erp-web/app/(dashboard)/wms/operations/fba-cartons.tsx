'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Typography } from 'antd';
import Link from 'next/link';
import { isAxiosError } from 'axios';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import { post } from '@/lib/api/client';
import { newOperationId } from './operation-id';

interface Pick { requestId: string; sellerSku: string; batchNo: string; quantity: number; returnedQuantity: number }
interface Line { pickId: string; sellerSku: string; batchNo: string; quantity: number; receiptId: string; purchaseOrderId: string; locationCode: string }
export interface Carton { cartonId: string; requestId: string; cartonCode: string; outboundId: string; fbaShipmentId: string; status: string; operatorId: string; createdAt: string; lines: Line[]; fbaBoxNumber?: string; bindingActive: boolean; boundBy?: string; boundAt?: string }
interface Values { cartonCode?: string; lines: { pickId: string; sellerSku: string; batchNo: string; quantity: number }[] }
type Attempt = Values & { requestId: string; outboundId: string };
const endpoint = '/wms/api/in/v1/fba-cartons';

export function CartonTrace({ outboundId, batchNo, purchaseOrderId }: { outboundId?: string; batchNo?: string; purchaseOrderId?: string }) {
  const [code, setCode] = useState('');
  const [search, setSearch] = useState('');
  const params = new URLSearchParams();
  if (outboundId) params.set('outboundId', outboundId);
  if (batchNo) params.set('batchNo', batchNo);
  if (purchaseOrderId) params.set('purchaseOrderId', purchaseOrderId);
  if (search.trim()) params.set('cartonCode', search.trim());
  const query = useApi<{ cartons: Carton[] }>(`${endpoint}?${params}`);
  return <Card title="FBA 箱内追溯">
    <Input.Search aria-label="内部或 FBA 箱号" placeholder="输入完整内部箱号或 FBA 箱号查询" value={code} onChange={e => setCode(e.target.value)}
      onSearch={setSearch} enterButton="查询箱号" allowClear style={{ maxWidth: 480, marginBottom: 16 }} />
    {query.error && <Alert type="error" message="箱内记录加载失败" action={<Button onClick={() => void query.mutate()}>重试加载</Button>} />}
    <CartonTable data={query.data?.cartons ?? []} loading={query.isLoading} />
  </Card>;
}

function CartonTable({ data, loading }: { data: Carton[]; loading?: boolean }) {
  return <Table<Carton> rowKey="cartonId" dataSource={data} loading={loading} size="small" scroll={{ x: 'max-content' }}
    expandable={{ expandedRowRender: c => <Table<Line> rowKey="pickId" size="small" pagination={false} dataSource={c.lines} scroll={{ x: 'max-content' }} columns={[
      { title: 'SKU', dataIndex: 'sellerSku' }, { title: '收货批次', dataIndex: 'batchNo' }, { title: '实装件数', dataIndex: 'quantity' },
      { title: '实拣库位', dataIndex: 'locationCode' },
      { title: '原采购单', render: (_, l) => <Link href={`/scm/purchase-orders?poId=${encodeURIComponent(l.purchaseOrderId)}`}>{l.purchaseOrderId}</Link> },
      { title: '收货来源', render: (_, l) => <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(l.purchaseOrderId)}&batchNo=${encodeURIComponent(l.batchNo)}`}>{l.receiptId}</Link> },
    ]} /> }} columns={[
      { title: '内部箱号', dataIndex: 'cartonCode' },
      { title: 'FBA 箱号', render: (_, c) => c.fbaBoxNumber ?? '尚未绑定' },
      { title: '贴标人 / 时间', render: (_, c) => c.boundAt ? `${c.boundBy} · ${new Date(c.boundAt).toLocaleString()}` : '尚未确认贴标' },
      { title: '实装总件数', render: (_, c) => c.lines.reduce((n, l) => n + l.quantity, 0) },
      { title: '状态', render: (_, c) => c.status === 'CANCELLED' ? '已因质检更正作废' : c.status === 'SHIPPED' ? '已出库' : '已装箱，待后续贴标发货' },
      { title: '备货单', render: (_, c) => <Link href={`/fba/shipments?shipmentId=${c.fbaShipmentId}`}>{c.fbaShipmentId}</Link> },
      { title: '仓储作业', render: (_, c) => <Link href={`/wms/operations?outboundId=${c.outboundId}`}>查看拣货作业</Link> },
      { title: '装箱人', dataIndex: 'operatorId' }, { title: '装箱时间', render: (_, c) => new Date(c.createdAt).toLocaleString() },
    ]} />;
}

export function FbaCartons({ work }: { work: { outboundId: string; status: string; picks: Pick[] } }) {
  const tenant = useAuthStore(s => s.tenantId);
  const key = `wms:pending-fba-carton:${tenant}`;
  const query = useApi<{ canPack: boolean; cartons: Carton[] }>(`${endpoint}?outboundId=${work.outboundId}`);
  const [form] = Form.useForm<Values>();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState<Attempt>();
  const submitting = useRef(false);
  useEffect(() => {
    try { const saved = sessionStorage.getItem(key); setPending(saved ? JSON.parse(saved) as Attempt : undefined); }
    catch { setError('待确认装箱记录无法读取，请核对箱号及箱内实物。'); }
  }, [key]);
  const submit = async (attempt: Attempt) => {
    if (submitting.current) return;
    submitting.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(key, JSON.stringify(attempt)); setPending(attempt);
      const { outboundId, ...body } = attempt;
      await post(`${endpoint}/${outboundId}`, body);
      sessionStorage.removeItem(key); setPending(undefined); setOpen(false); await query.mutate();
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(key); setPending(undefined);
      }
      setError(response?.data?.message ?? '装箱结果未确认，请重试原装箱；原操作不会重复分配货物。');
    } finally { submitting.current = false; setSaving(false); }
  };
  const remaining = (p: Pick) => p.quantity - p.returnedQuantity - (query.data?.cartons ?? []).filter(c => c.status !== 'CANCELLED')
    .flatMap(c => c.lines).filter(l => l.pickId === p.requestId).reduce((n, l) => n + l.quantity, 0);
  const candidates = work.picks.filter(p => remaining(p) > 0);
  return <Card title="FBA 按箱装箱" extra={query.data?.canPack && <Button type="primary" disabled={!!pending || !candidates.length || work.status === 'QUALITY_HOLD'}
    onClick={() => { form.resetFields(); setError(undefined); setOpen(true); }}>登记一箱实装</Button>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="每次确认一箱完整的实装明细，可混装多个 SKU 和批次。使用已有内部箱号，或留空生成新箱号。装箱不会扣减在手或释放预占。" />
      {query.data?.canPack === false && <Alert type="info" message="当前账号仅可查看箱内追溯，没有装箱权限" />}
      {query.error && <Alert type="error" message="箱内记录加载失败" action={<Button onClick={() => void query.mutate()}>重试加载</Button>} />}
      {error && <Alert type="error" showIcon message={error} />}
      {pending && <Alert type="warning" message="有一笔装箱结果待确认" action={<Button loading={saving} disabled={!query.data?.canPack} onClick={() => void submit(pending)}>重试原装箱</Button>} />}
      <CartonTable data={query.data?.cartons ?? []} loading={query.isLoading} />
    </Space>
    <Modal title="登记 FBA 箱内实装" open={open} width={820} maskClosable={false} onCancel={() => !saving && setOpen(false)}
      closable={!saving} keyboard={!saving} cancelButtonProps={{ disabled: saving }} confirmLoading={saving}
      okText={pending ? '重试原装箱' : '确认这一箱'} onOk={() => pending ? void submit(pending) : form.submit()}>
      {error && <Alert type="error" showIcon message={error} />}
      <Form form={form} layout="vertical" initialValues={{ lines: [{}] }} disabled={saving || !!pending}
        onFinish={values => void submit({ ...values, outboundId: work.outboundId, requestId: newOperationId() })}>
        <Form.Item label="内部箱号（留空由系统生成）" name="cartonCode" rules={[{ max: 64 }, { pattern: /^[A-Za-z0-9._-]*$/, message: '使用字母、数字、点、横线或下划线' }]}><Input aria-label="内部箱号（留空由系统生成）" /></Form.Item>
        <Form.List name="lines" rules={[{ validator: async (_, lines) => { if (!lines?.length) throw new Error('请登记至少一条实装明细'); } }]}>
          {(fields, { add, remove }, { errors }) => <>
            {fields.map(field => <Card size="small" key={field.key} title={`箱内明细 ${field.name + 1}`} style={{ marginBottom: 12 }} extra={<Button onClick={() => remove(field.name)}>移除此行</Button>}>
              <Form.Item label="对应实拣记录" name={[field.name, 'pickId']} rules={[{ required: true, message: '选择当前任务的实拣记录' }]}>
                <Select aria-label="对应实拣记录" options={candidates.map(p => ({ value: p.requestId, label: `${p.sellerSku} · ${p.batchNo} · 可装 ${remaining(p)} 件` }))} />
              </Form.Item>
              <Form.Item label="核对实物 SKU" name={[field.name, 'sellerSku']} rules={[{ required: true, whitespace: true }]}><Input aria-label="核对实物 SKU" /></Form.Item>
              <Form.Item label="核对实物批次码" name={[field.name, 'batchNo']} rules={[{ required: true, whitespace: true }]}><Input aria-label="核对实物批次码" /></Form.Item>
              <Form.Item label="实际装入件数" name={[field.name, 'quantity']} rules={[{ required: true }]}><InputNumber aria-label="实际装入件数" min={1} precision={0} /></Form.Item>
            </Card>)}
            <Button onClick={() => add()}>增加 SKU / 批次明细</Button><Form.ErrorList errors={errors} />
          </>}
        </Form.List>
      </Form>
    </Modal>
  </Card>;
}
