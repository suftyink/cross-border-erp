'use client';

import { useState } from 'react';
import { Alert, Table, Button, Modal, Form, Input, InputNumber, Select, Tag, Space, message, Card, Typography } from 'antd';
import { PlusOutlined, EditOutlined } from '@ant-design/icons';
import { isAxiosError } from 'axios';
import { useApi } from '@/lib/hooks';
import { scmApi } from '@/lib/api';
import { patch } from '@/lib/api/client';
import type { PurchaseReceiptPermissions, Supplier } from '@/types';

const { Title } = Typography;

export default function SuppliersPage() {
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const [configuring, setConfiguring] = useState<Supplier>();
  const [savingTolerance, setSavingTolerance] = useState(false);
  const [toleranceError, setToleranceError] = useState<string>();
  const [form] = Form.useForm();
  const [toleranceForm] = Form.useForm<{ percent: number }>();
  const { data, error, isLoading, mutate } = useApi<{ supplier: Supplier }[]>('/scm/api/in/v1/suppliers');
  const { data: permissions } = useApi<PurchaseReceiptPermissions>('/scm/api/in/v1/purchase-receipt-permissions');

  const handleCreate = () => { setEditing(null); form.resetFields(); setModalOpen(true); };
  const handleEdit = (record: Supplier) => { setEditing(record); form.setFieldsValue(record); setModalOpen(true); };

  const saveTolerance = async ({ percent }: { percent: number }) => {
    if (!configuring || savingTolerance) return;
    setSavingTolerance(true);
    setToleranceError(undefined);
    try {
      await patch(`/scm/api/in/v1/suppliers/${encodeURIComponent(configuring.supplierId)}/receipt-tolerance`, { percent });
      setConfiguring(undefined);
      void mutate();
      message.success('超收容差已保存');
    } catch (failure) {
      setToleranceError(isAxiosError(failure) ? failure.response?.data?.message ?? '超收容差保存失败' : '超收容差保存失败');
    } finally {
      setSavingTolerance(false);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editing) { await scmApi.updateSupplier(editing.supplierId, values); }
      else { await scmApi.createSupplier(values); }
      message.success(editing ? '供应商更新成功' : '供应商创建成功');
      setModalOpen(false);
      mutate();
    } catch { message.error('操作失败'); }
  };

  const columns = [
    { title: '供应商名称', dataIndex: 'name', key: 'name' },
    { title: '国家或地区', dataIndex: 'countryCode', key: 'countryCode' },
    { title: '联系人', dataIndex: 'contactName', key: 'contactName' },
    { title: '超收容差', dataIndex: 'receiptTolerancePercent', key: 'receiptTolerancePercent', render: (percent: number) => `${percent}%` },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => <Tag color={v === 'ACTIVE' ? 'green' : 'red'}>{v}</Tag> },
    { title: '采购单', key: 'po', render: (_: unknown, r: Supplier) => <a href={`/scm/purchase-orders?supplierId=${r.supplierId}`}>查看</a> },
    { title: '操作', key: 'action', render: (_: unknown, record: Supplier) => (
      <Space wrap>
        <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button>
        {permissions?.canConfigure && <Button onClick={() => {
          setToleranceError(undefined);
          setConfiguring(record);
        }}>设置超收容差</Button>}
      </Space>
    ) },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>供应商管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建供应商</Button>
      </div>
      <Card style={{ borderRadius: 8 }}>
        {error && <Alert type="error" showIcon message="供应商列表加载失败" />}
        <Table rowKey="supplierId" columns={columns} dataSource={(data ?? []).map(({ supplier }) => supplier)}
          loading={isLoading} size="middle" pagination={{ pageSize: 20 }} />
      </Card>
      <Modal title={editing ? '编辑供应商' : '新建供应商'} open={modalOpen} onOk={handleSubmit} onCancel={() => setModalOpen(false)} width={520}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="供应商名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="countryCode" label="国家或地区代码" rules={[{ required: true }]}><Input placeholder="例如 CN" /></Form.Item>
          <Form.Item name="contactName" label="联系人"><Input /></Form.Item>
          {editing && <Form.Item name="status" label="状态">
            <Select options={[
              { value: 'ACTIVE', label: '启用' },
              { value: 'INACTIVE', label: '停用' },
              { value: 'BLACKLISTED', label: '黑名单' },
              { value: 'PENDING_REVIEW', label: '待审核' },
            ]} />
          </Form.Item>}
        </Form>
      </Modal>
      <Modal title={`设置超收容差 · ${configuring?.name ?? ''}`} open={!!configuring} okText="保存容差"
        onOk={() => toleranceForm.submit()} confirmLoading={savingTolerance} onCancel={() => setConfiguring(undefined)}
        cancelButtonProps={{ disabled: savingTolerance }} closable={!savingTolerance}
        maskClosable={!savingTolerance} keyboard={!savingTolerance} destroyOnClose>
        {toleranceError && <Alert type="error" showIcon message={toleranceError} />}
        <Form form={toleranceForm} layout="vertical" preserve={false} onFinish={saveTolerance}
          initialValues={{ percent: configuring?.receiptTolerancePercent }}>
          <Form.Item name="percent" label="超收容差（%）" extra="采购单审批时锁定此比例。"
            rules={[{ required: true, type: 'number', min: 0, max: 100 }]}>
            <InputNumber min={0} max={100} precision={2} disabled={savingTolerance} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
