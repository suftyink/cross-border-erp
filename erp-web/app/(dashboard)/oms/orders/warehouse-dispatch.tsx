'use client';

import { useRef, useState } from 'react';
import { Alert, Button, Checkbox, Form, Modal, Select, Space, Typography } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import type { Warehouse } from '@/types';

export function WarehouseDispatch({ orderId, paid }: { orderId: string; paid: boolean }) {
  const access = useApi<{ canDispatch: boolean }>('/oms/api/in/v1/orders/warehouse-work-access');
  const warehouses = useApi<Warehouse[]>('/wms/api/in/v1/warehouses');
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [uncertain, setUncertain] = useState(false);
  const [done, setDone] = useState(false);
  const busy = useRef(false);
  const [form] = Form.useForm<{ warehouseId: string; allowBatchSubstitution: boolean }>();
  const submit = async ({ warehouseId, allowBatchSubstitution }: { warehouseId: string; allowBatchSubstitution: boolean }) => {
    if (busy.current) return;
    busy.current = true;
    setSaving(true);
    setError(undefined);
    try {
      await post(`/oms/api/in/v1/orders/${encodeURIComponent(orderId)}/warehouse-work`, { warehouseId, allowBatchSubstitution });
      setDone(true);
      setUncertain(false);
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      setUncertain(!response || response.status >= 500 || [401, 403, 408, 429].includes(response.status));
      setError(response?.data?.message ?? '下发结果未确认，请保留当前仓库重试。');
    } finally { setSaving(false); busy.current = false; }
  };
  return <>
    {paid && access.data?.canDispatch && <Button type="link" size="small" onClick={() => setOpen(true)}>下发仓储</Button>}
    <Link href={`/wms/operations?salesOrderId=${encodeURIComponent(orderId)}`}>仓储作业</Link>
    <Modal title="下发仓储作业" open={open} confirmLoading={saving} maskClosable={false} closable={!saving}
      cancelButtonProps={{ disabled: saving }} onCancel={() => setOpen(false)}
      okButtonProps={{ disabled: done || warehouses.isLoading || !!warehouses.error }} okText={uncertain ? '重试原下发' : '确认下发'} onOk={() => form.submit()}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text>按采购收货顺序分配所选仓库的已上架良品。库存不足时整单下发失败。</Typography.Text>
        {warehouses.error && <Alert type="error" showIcon message="仓库加载失败" />}
        {error && <Alert type="error" showIcon message={error} />}
        {done && <Alert type="success" showIcon message="已下发并完成批次预占"
          action={<Link href={`/wms/operations?salesOrderId=${encodeURIComponent(orderId)}`}>前往拣货作业</Link>} />}
        <Form form={form} layout="vertical" onFinish={submit} initialValues={{ allowBatchSubstitution: false }}>
          <Form.Item name="warehouseId" label="出库仓库" rules={[{ required: true, message: '请选择出库仓库' }]}>
            <Select aria-label="出库仓库" disabled={saving || uncertain || done} loading={warehouses.isLoading}
              options={(warehouses.data ?? []).map((w) => ({ label: `${w.code} · ${w.name}`, value: w.warehouseId }))} />
          </Form.Item>
          <Form.Item name="allowBatchSubstitution" valuePropName="checked">
            <Checkbox disabled={saving || uncertain || done}>允许同库位同 SKU 换批</Checkbox>
          </Form.Item>
        </Form>
      </Space>
    </Modal>
  </>;
}
