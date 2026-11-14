'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, message, Modal, Space, Table, Typography } from 'antd';
import { isAxiosError } from 'axios';
import { get, post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import type { Warehouse } from '@/types';
import { newOperationId } from './operation-id';

interface PendingBatch {
  batchNo: string;
  sellerSku: string;
  supplierBatchNo?: string;
  warehouseId: string;
  pendingPutaway: number;
}

type PutawayValues = { locationCode: string; quantity: number };
type Attempt = PutawayValues & { requestId: string; batchNo: string };
const endpoint = '/wms/api/in/v1/purchase-putaways';
const recommendationPath = (batchNo: string) => `${endpoint}/${batchNo}/recommendation`;

export function PurchasePutaway({ purchaseOrderId, warehouses }: { purchaseOrderId: string | null; warehouses: Warehouse[] }) {
  const tenantId = useAuthStore((s) => s.tenantId);
  const storageKey = `wms:pending-putaway:${tenantId}`;
  const work = useApi<{ canPutaway: boolean; batches: PendingBatch[] }>(purchaseOrderId
    ? `${endpoint}/work?purchaseOrderId=${encodeURIComponent(purchaseOrderId)}` : `${endpoint}/work`);
  const [batch, setBatch] = useState<PendingBatch>();
  const [pending, setPending] = useState<Attempt>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => {
    const stored = sessionStorage.getItem(storageKey);
    setPending(stored ? JSON.parse(stored) as Attempt : undefined);
  }, [storageKey]);

  const submit = async (attempt: Attempt) => {
    if (saving) return;
    setSaving(true);
    setError(undefined);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt));
      setPending(attempt);
      const { batchNo, ...body } = attempt;
      await post(`${endpoint}/${encodeURIComponent(batchNo)}`, body);
      sessionStorage.removeItem(storageKey);
      setPending(undefined);
      setBatch(undefined);
      void work.mutate();
      message.success('上架已确认');
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status < 500) {
        sessionStorage.removeItem(storageKey);
        setPending(undefined);
      }
      setError(response?.data?.message ?? '上架结果未确认，请重试原确认。');
    } finally {
      setSaving(false);
    }
  };

  return <Card title="上架待办">
    {work.error && <Alert showIcon type="error" message="上架待办加载失败" />}
    {work.data?.canPutaway === false && <Alert showIcon type="info" message="当前账号没有确认上架权限" />}
    {!batch && error && <Alert showIcon type="error" message={error} />}
    {!batch && pending && work.data?.canPutaway && <Alert showIcon type="warning"
      message={`待确认上架 · ${pending.locationCode} · ${pending.quantity} 件`}
      action={<Button loading={saving} onClick={() => void submit(pending)}>重试原上架</Button>} />}
    {(work.isLoading || work.data?.canPutaway) && <Table<PendingBatch> size="small" rowKey="batchNo"
      loading={work.isLoading} dataSource={work.data?.batches ?? []} scroll={{ x: 'max-content' }} columns={[
        { title: '收货批次', dataIndex: 'batchNo' },
        { title: 'SKU', dataIndex: 'sellerSku' },
        { title: '供应商批号', dataIndex: 'supplierBatchNo', render: (value) => value ?? '—' },
        { title: '仓库', render: (_, row) => warehouses.find((warehouse) => warehouse.warehouseId === row.warehouseId)?.name ?? row.warehouseId },
        { title: '待上架', dataIndex: 'pendingPutaway' },
        { title: '操作', render: (_, row) => <Button disabled={!!pending} onClick={() => {
          setError(undefined);
          setBatch(row);
        }}>确认上架</Button> },
      ]} />}
    {batch && <PutawayDialog batch={batch} saving={saving} pending={pending} error={error}
      onClose={() => setBatch(undefined)}
      onSubmit={(values) => void submit(pending ?? { ...values, batchNo: batch.batchNo, requestId: newOperationId() })} />}
  </Card>;
}

function PutawayDialog({ batch, saving, pending, error, onClose, onSubmit }: {
  batch: PendingBatch;
  saving: boolean;
  pending?: Attempt;
  error?: string;
  onClose: () => void;
  onSubmit: (values: PutawayValues) => void;
}) {
  const [form] = Form.useForm<PutawayValues>();
  const [location, setLocation] = useState<string | null>(null);
  const [recommendationFailed, setRecommendationFailed] = useState(false);
  useEffect(() => {
    let mounted = true;
    get<{ locationCode: string | null }>(recommendationPath(batch.batchNo))
      .then((recommendation) => { if (mounted) setLocation(recommendation.locationCode); })
      .catch(() => { if (mounted) setRecommendationFailed(true); });
    return () => { mounted = false; };
  }, [batch.batchNo]);
  return <Modal title="确认良品上架" open closable={!saving} keyboard={!saving} maskClosable={false}
    cancelButtonProps={{ disabled: saving }} onCancel={onClose} confirmLoading={saving}
    okText={pending ? '重试原上架' : '确认上架'}
    onOk={() => pending ? onSubmit(pending) : form.submit()}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text>{batch.sellerSku} · 待上架 {batch.pendingPutaway} 件</Typography.Text>
      {error && <Alert showIcon type="error" message={error} />}
      {recommendationFailed && <Alert showIcon type="warning" message="推荐库位加载失败，可以输入实际库位码" />}
      {location && <Space wrap>
        <Typography.Text>最近使用库位 {location}</Typography.Text>
        <Button disabled={!!pending || saving} onClick={() => form.setFieldValue('locationCode', location)}>使用推荐库位</Button>
      </Space>}
      <Form form={form} layout="vertical" onFinish={onSubmit} disabled={saving || !!pending}>
        <Form.Item name="locationCode" label="实际库位码"
          rules={[{ required: true, whitespace: true, message: '请输入实物库位码' }]}>
          <Input maxLength={128} aria-label="实际库位码" />
        </Form.Item>
        <Form.Item name="quantity" label="本次上架件数"
          rules={[{ required: true }, { type: 'integer', min: 1, max: batch.pendingPutaway }]}>
          <InputNumber aria-label="本次上架件数" min={1} max={batch.pendingPutaway} precision={0} />
        </Form.Item>
      </Form>
    </Space>
  </Modal>;
}
