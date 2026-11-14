'use client';

import { useState } from 'react';
import { Alert, Button, Form, Input, Modal, Radio, Space, Switch, Table, Tag } from 'antd';
import { isAxiosError } from 'axios';
import { patch, post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import type { Warehouse, WarehouseLocation } from '@/types';

type LocationInput = { locationCode: string; purpose: WarehouseLocation['purpose']; enabled: boolean };

export function WarehouseLocations({ warehouse, onClose }: { warehouse: Warehouse; onClose: () => void }) {
  const path = `/wms/api/in/v1/warehouses/${encodeURIComponent(warehouse.warehouseId)}/locations`;
  const locations = useApi<WarehouseLocation[]>(path);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [form] = Form.useForm<LocationInput>();

  const apply = async (operation: () => Promise<unknown>) => {
    setError(undefined);
    setSaving(true);
    try {
      await operation();
      await locations.mutate();
    } catch (failure) {
      setError(isAxiosError(failure) ? failure.response?.data?.message ?? '库位保存失败' : '库位保存失败');
    } finally {
      setSaving(false);
    }
  };

  return <Modal title={`库位管理 · ${warehouse.name}`} open footer={null} onCancel={onClose}
    closable={!saving} maskClosable={false} width={760}>
    {(error || locations.error) && <Alert showIcon type="error" message={error ?? '库位加载失败'} />}
    <Form form={form} layout="vertical" disabled={saving} initialValues={{ purpose: 'GOOD', enabled: true }}
      onFinish={(values) => void apply(async () => { await post(path, values); form.resetFields(); })}>
      <Space wrap align="baseline">
        <Form.Item name="locationCode" label="库位码" rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={128} aria-label="库位码" />
        </Form.Item>
        <Form.Item name="purpose" label="用途">
          <Radio.Group options={[{ value: 'GOOD', label: '良品' }, { value: 'QUARANTINE', label: '隔离' }]} />
        </Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch aria-label="启用" /></Form.Item>
        <Button type="primary" htmlType="submit" loading={saving}>新增库位</Button>
      </Space>
    </Form>
    <Table<WarehouseLocation> size="small" rowKey="locationId" loading={locations.isLoading}
      dataSource={locations.data ?? []} columns={[
        { title: '库位码', dataIndex: 'locationCode', render: (code) => code ?? '—' },
        { title: '用途', render: (_, item) => item.purpose === 'GOOD' ? '良品' : '隔离' },
        { title: '状态', render: (_, item) => <Tag color={item.enabled ? 'green' : 'default'}>{item.enabled ? '启用' : '禁用'}</Tag> },
        { title: '操作', render: (_, item) => <Button disabled={saving} onClick={() => void apply(() =>
          patch(`${path}/${encodeURIComponent(item.locationId)}/enabled`, { enabled: !item.enabled }))}>
          {item.enabled ? '禁用库位' : '启用库位'}
        </Button> },
      ]} />
  </Modal>;
}
