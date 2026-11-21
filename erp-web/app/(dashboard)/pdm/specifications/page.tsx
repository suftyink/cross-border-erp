'use client';

import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Typography, message } from 'antd';
import { useApi } from '@/lib/hooks';
import { del, post, put } from '@/lib/api/client';
import { skuPath } from '@/lib/pdm-sku';
import { specificationError, specificationPath, type SpecificationAttribute, type SpecificationValue } from '@/lib/pdm-specification';
import { mutate } from 'swr';

type Fields = { code: string; name: string; description?: string | null };
type Editing = { kind: 'attribute' | 'value'; record?: SpecificationAttribute | SpecificationValue };

export default function SpecificationsPage() {
  const dictionary = useApi<SpecificationAttribute[]>(specificationPath);
  const access = useApi<{ canMaintain: boolean }>(`${skuPath}/access`);
  const canMaintain = access.data?.canMaintain === true;
  const [selected, setSelected] = useState<string>();
  const [editing, setEditing] = useState<Editing | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [form] = Form.useForm<Fields>();
  const rows = Array.isArray(dictionary.data) ? dictionary.data : [];
  const invalidResponse = dictionary.data !== undefined && !Array.isArray(dictionary.data);
  const attribute = rows.find(item => item.attributeId === selected);
  const open = (value: Editing) => { setEditing(value); setError(undefined); form.resetFields(); if (value.record) form.setFieldsValue(value.record); };
  const refresh = () => mutate((key: unknown) => typeof key === 'string' && key.startsWith('/pdm/api/in/v1/'));

  const submit = async () => {
    if (!editing || busy) return;
    let data: Fields;
    try { data = await form.validateFields(); } catch { return; }
    setBusy(true); setError(undefined);
    try {
      const path = editing.kind === 'attribute' ? specificationPath : `${specificationPath}/${selected}/values`;
      if (editing.record) {
        const id = 'valueId' in editing.record ? editing.record.valueId : editing.record.attributeId;
        await put(`${path}/${id}`, data);
      } else {
        const result = await post<SpecificationAttribute>(path, data);
        if (editing.kind === 'attribute') setSelected(result.attributeId);
      }
      setEditing(null); message.success('规格字典已保存');
      await refresh().catch(() => message.warning('已保存，请手动刷新列表'));
    } catch (failure) { setError(specificationError(failure)); } finally { setBusy(false); }
  };
  const remove = async (path: string, attrId?: string) => {
    setBusy(true);
    try {
      await del(path); if (attrId === selected) setSelected(undefined);
      await refresh(); message.success('已删除未引用的字典项');
    } catch (failure) { message.error(specificationError(failure)); } finally { setBusy(false); }
  };

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Typography.Title level={4} style={{ margin: 0 }}>规格字典</Typography.Title>
    <Alert showIcon type="info" message="属性和值在本租户内共享。编码与归属固定；显示名可作描述性更名，不能用于替换商品含义。已引用项不可删除。" />
    {(dictionary.error || access.error || invalidResponse) && <Alert showIcon type="error"
      message={invalidResponse ? '规格字典接口返回格式错误' : '规格或权限加载失败'}
      action={<Button onClick={() => { void dictionary.mutate(); void access.mutate(); }}>重试</Button>} />}
    <Card title="共享规格属性" extra={canMaintain && <Button type="primary" disabled={invalidResponse} onClick={() => open({ kind: 'attribute' })}>新建属性</Button>}>
      <Table<SpecificationAttribute> rowKey="attributeId" loading={dictionary.isLoading} dataSource={rows} scroll={{ x: 700 }} pagination={{ pageSize: 10 }}
        locale={{ emptyText: dictionary.error || invalidResponse ? '加载失败，请点击重试' : '暂无共享规格属性' }}
        rowClassName={(row) => row.attributeId === selected ? 'ant-table-row-selected' : ''} columns={[
          { title: '稳定编码', dataIndex: 'code' }, { title: '显示名', dataIndex: 'name' }, { title: '说明', dataIndex: 'description', render: (v: string | null) => v || '—' },
          { title: '规格值', render: (_, row) => row.values.length },
          { title: '操作', render: (_, row) => <Space>
            <Button type="link" onClick={() => setSelected(row.attributeId)}>管理规格值</Button>
            {canMaintain && <><Button type="link" onClick={() => open({ kind: 'attribute', record: row })}>编辑</Button>
              <Popconfirm title="确认删除属性及其未引用值？" onConfirm={() => remove(`${specificationPath}/${row.attributeId}`, row.attributeId)} okText="确认删除" cancelText="取消">
                <Button type="link" danger disabled={busy}>删除</Button></Popconfirm></>}
          </Space> },
        ]} />
    </Card>
    {attribute && <Card title={`${attribute.name} · 规格值`} extra={canMaintain && <Button onClick={() => open({ kind: 'value' })}>新建规格值</Button>}>
      <Table<SpecificationValue> rowKey="valueId" dataSource={attribute.values} pagination={{ pageSize: 10 }} scroll={{ x: 600 }}
        locale={{ emptyText: '暂无规格值，请点击右上角新建' }} columns={[
        { title: '稳定编码', dataIndex: 'code' }, { title: '显示名', dataIndex: 'name' }, { title: '说明', dataIndex: 'description', render: (v: string | null) => v || '—' },
        { title: '操作', render: (_, row) => canMaintain && <Space>
          <Button type="link" onClick={() => open({ kind: 'value', record: row })}>编辑</Button>
          <Popconfirm title="确认删除未引用规格值？" onConfirm={() => remove(`${specificationPath}/${attribute.attributeId}/values/${row.valueId}`)} okText="确认删除" cancelText="取消">
            <Button type="link" danger disabled={busy}>删除</Button></Popconfirm>
        </Space> },
      ]} />
    </Card>}
    <Modal title={`${editing?.record ? '编辑' : '新建'}${editing?.kind === 'value' ? '规格值' : '属性'}`} open={!!editing} onCancel={() => { if (!busy) setEditing(null); }}
      onOk={() => void submit()} okText="保存" cancelText="取消" confirmLoading={busy} maskClosable={!busy} destroyOnClose>
      {error && <Alert showIcon type="error" message={error} style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical" disabled={busy} validateTrigger="onBlur">
        <Form.Item name="code" label="稳定编码" extra="保存后不可修改，用于保持原含义。" rules={[
          { required: true, message: '请输入稳定编码' },
          { pattern: /^[A-Za-z0-9_-]{1,64}$/, transform: (value: string) => value?.trim(), message: '仅支持 1 至 64 位字母、数字、连字符和下划线' },
        ]}>
          <Input disabled={!!editing?.record} maxLength={64} /></Form.Item>
        <Form.Item name="name" label="显示名" rules={[{ required: true, whitespace: true, message: '请输入显示名' }, { max: 128 }]}><Input maxLength={128} /></Form.Item>
        <Form.Item name="description" label="说明" rules={[{ max: 512 }]}><Input.TextArea maxLength={512} rows={3} /></Form.Item>
      </Form>
    </Modal>
  </Space>;
}
