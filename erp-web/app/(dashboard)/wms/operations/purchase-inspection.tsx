'use client';

import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, InputNumber, Modal, Radio, Space, Spin, Table, Tag } from 'antd';
import { isAxiosError } from 'axios';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import type { Warehouse } from '@/types';

const endpoint = '/wms/api/in/v1/purchase-inspections';
const labels = {
  PENDING_SAMPLE: '待抽检',
  PENDING_RECHECK: '待复检',
  PENDING_DISPOSITION: '待整批判定',
  DISPOSED: '已判定',
};

interface Batch {
  batchNo: string;
  supplierBatchNo?: string;
  warehouseId: string;
  sellerSku: string;
  receivedQuantity: number;
  receivedAt: string;
  status: keyof typeof labels;
}

interface InspectionCounts {
  checkedQuantity: number;
  passedQuantity: number;
  failedQuantity: number;
  operatorId: string;
  checkedAt: string;
}

interface Detail {
  batch: Batch;
  inspectionId?: string;
  sample?: InspectionCounts;
  recheck?: InspectionCounts & { method: 'RECHECK' | 'FULL' };
  pendingInspection: number;
  pendingPutaway: number;
  quarantined: number;
  dispositionOperatorId?: string;
  disposedAt?: string;
}

type QuantityField = 'checkedQuantity' | 'passedQuantity' | 'failedQuantity' | 'goodQuantity' | 'defectiveQuantity';
type FormValues = Partial<Record<QuantityField, number>> & { method?: 'RECHECK' | 'FULL' };

export function InspectionWork({ purchaseOrderId, warehouses }: { purchaseOrderId: string | null; warehouses: Warehouse[] }) {
  const work = useApi<{ canInspect: boolean; batches: Batch[] }>(purchaseOrderId
    ? `${endpoint}/work?purchaseOrderId=${encodeURIComponent(purchaseOrderId)}` : `${endpoint}/work`);
  const [batchNo, setBatchNo] = useState<string>();
  return (
    <Card title="质检待办">
      {work.error && <Alert showIcon type="error" message="质检待办加载失败" />}
      {work.data?.canInspect === false && <Alert showIcon type="info" message="当前账号没有采购质检权限" />}
      {(work.isLoading || work.data?.canInspect) && <Table<Batch> size="small" rowKey="batchNo" loading={work.isLoading}
        dataSource={work.data?.batches ?? []} scroll={{ x: 'max-content' }} columns={[
          { title: '内部批次', dataIndex: 'batchNo' },
          { title: 'SKU', dataIndex: 'sellerSku' },
          { title: '供应商批号', dataIndex: 'supplierBatchNo', render: (value) => value ?? '—' },
          { title: '仓库', render: (_, batch) => warehouses.find((warehouse) => warehouse.warehouseId === batch.warehouseId)?.name ?? batch.warehouseId },
          { title: '实收件数', dataIndex: 'receivedQuantity' },
          { title: '到货时间', render: (_, batch) => new Date(batch.receivedAt).toLocaleString() },
          { title: '状态', render: (_, batch) => <Tag>{labels[batch.status]}</Tag> },
          { title: '操作', render: (_, batch) => <Button onClick={() => setBatchNo(batch.batchNo)}>{labels[batch.status]}</Button> },
        ]} />}
      {batchNo && <InspectionDialog batchNo={batchNo} canInspect onClose={() => setBatchNo(undefined)}
        onSaved={() => { void work.mutate(); }} />}
    </Card>
  );
}

export function InspectionDialog({ batchNo, canInspect = false, onClose, onSaved }: {
  batchNo: string;
  canInspect?: boolean;
  onClose: () => void;
  onSaved?: () => void;
}) {
  const path = `${endpoint}/${encodeURIComponent(batchNo)}`;
  const detail = useApi<Detail>(path);
  const current = detail.data;
  return (
    <Modal title="采购批次质检" open footer={null} onCancel={onClose} maskClosable={false} width={720}>
      {detail.error && <Alert showIcon type="error" message="批次质检记录加载失败" />}
      {detail.isLoading && <Spin />}
      {current && <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }} items={[
          { key: 'batch', label: '收货批次', children: batchNo, span: 2 },
          { key: 'sku', label: 'SKU', children: current.batch.sellerSku },
          { key: 'status', label: '状态', children: labels[current.batch.status] },
          { key: 'received', label: '实收件数', children: current.batch.receivedQuantity },
          { key: 'supplier', label: '供应商批号', children: current.batch.supplierBatchNo ?? '—' },
          { key: 'sample', label: '抽检记录', children: current.sample && formatCounts(current.sample) },
          { key: 'recheck', label: '复检记录', children: current.recheck &&
            `${current.recheck.method === 'FULL' ? '全检' : '复检'} · ${formatCounts(current.recheck)}` },
          { key: 'inspection', label: '待检', children: current.pendingInspection },
          { key: 'putaway', label: '待上架', children: current.pendingPutaway },
          { key: 'quarantine', label: '隔离次品', children: current.quarantined },
          { key: 'operator', label: '判定人', children: current.dispositionOperatorId ?? '—' },
        ]} />
        {canInspect && current.batch.status !== 'DISPOSED' && <InspectionForm key={current.batch.status}
          detail={current} path={path} onSaved={(record) => {
            void detail.mutate(record, { revalidate: false });
            onSaved?.();
          }} />}
      </Space>}
    </Modal>
  );
}

function formatCounts(counts: InspectionCounts) {
  return `${counts.checkedQuantity} 件，合格 ${counts.passedQuantity}，不合格 ${counts.failedQuantity}`;
}

function InspectionForm({ detail, path, onSaved }: { detail: Detail; path: string; onSaved: (record: Detail) => void }) {
  const [form] = Form.useForm<FormValues>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const status = detail.batch.status;
  const isDisposition = status === 'PENDING_DISPOSITION';
  const isRecheck = status === 'PENDING_RECHECK';
  const total = detail.batch.receivedQuantity;
  const fields: { key: QuantityField; label: string; min: number }[] = isDisposition
    ? [{ key: 'goodQuantity', label: '整批良品', min: 0 }, { key: 'defectiveQuantity', label: '整批次品', min: 0 }]
    : [{ key: 'checkedQuantity', label: isRecheck ? '复检件数' : '抽检件数', min: 1 },
      { key: 'passedQuantity', label: '合格件数', min: 0 }, { key: 'failedQuantity', label: '不合格件数', min: 0 }];
  const defaultCounts = detail.recheck?.method === 'FULL'
    ? { goodQuantity: detail.recheck.passedQuantity, defectiveQuantity: detail.recheck.failedQuantity }
    : detail.sample?.failedQuantity === 0 ? { goodQuantity: total, defectiveQuantity: 0 } : {};

  const save = async (values: FormValues) => {
    if (saving) return;
    setSaving(true);
    setError(undefined);
    const stage = isDisposition ? 'disposition' : isRecheck ? 'recheck' : 'sample';
    try {
      onSaved(await post<Detail>(`${path}/${stage}`, values));
    } catch (failure) {
      setError(isAxiosError(failure) ? failure.response?.data?.message ?? '保存失败，可用相同数量重试。'
        : '保存失败，可用相同数量重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Form form={form} layout="vertical" onFinish={save} disabled={saving}
      initialValues={isDisposition ? defaultCounts : isRecheck ? { method: 'RECHECK' } : { failedQuantity: 0 }}>
      {error && <Alert showIcon type="error" message={error} />}
      {isRecheck && <Form.Item name="method" label="复检方式" rules={[{ required: true }]}>
        <Radio.Group options={[{ value: 'RECHECK', label: '复检' }, { value: 'FULL', label: '全检' }]} />
      </Form.Item>}
      <Space wrap align="start">
        {fields.map(({ key, label, min }) => (
          <Form.Item key={key} name={key} label={label}
            rules={[{ required: true }, { type: 'integer', min, max: total, message: `填写 ${min} 至 ${total} 件` }]}>
            <InputNumber aria-label={label} min={min} max={total} precision={0} style={{ width: 130 }} />
          </Form.Item>
        ))}
      </Space>
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={saving}>
          {isDisposition ? '确认整批判定' : isRecheck ? '保存复检结果' : '保存抽检结果'}
        </Button>
      </Form.Item>
    </Form>
  );
}
