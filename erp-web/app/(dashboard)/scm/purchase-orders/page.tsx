'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Alert, Table, Button, Modal, Form, Input, InputNumber, Select, Space, message, Card, Typography, Badge } from 'antd';
import { PlusOutlined, CheckOutlined } from '@ant-design/icons';
import { useApi } from '@/lib/hooks';
import { scmApi } from '@/lib/api';
import type { PurchaseOrder, PurchaseReceiptPermissions, Supplier } from '@/types';
import { PurchaseDetails } from './purchase-details';

const { Title } = Typography;

const poStatusMap: Record<string, { color: string; text: string }> = {
  DRAFT: { color: 'default', text: '草稿' }, SUBMITTED: { color: 'orange', text: '待审批' },
  APPROVED: { color: 'blue', text: '已审批' }, PARTIALLY_RECEIVED: { color: 'cyan', text: '部分收货' },
  RECEIVED: { color: 'green', text: '收齐' }, CANCELLED: { color: 'red', text: '已取消' },
};

export default function PurchaseOrdersPage() {
  const [selectedStatus, setSelectedStatus] = useState<string>();
  const [modalOpen, setModalOpen] = useState(false);
  const [details, setDetails] = useState<{ order: PurchaseOrder; amend: boolean }>();
  const [form] = Form.useForm();
  const { data, error, isLoading, mutate } = useApi<PurchaseOrder[]>('/scm/api/in/v1/purchase-orders');
  const { data: suppliers } = useApi<{ supplier: Supplier }[]>(modalOpen ? '/scm/api/in/v1/suppliers' : null);
  const { data: permissions } = useApi<PurchaseReceiptPermissions>('/scm/api/in/v1/purchase-receipt-permissions');

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      await scmApi.createPurchaseOrder(values);
      message.success('采购单创建成功');
      setModalOpen(false);
      mutate();
    } catch { message.error('操作失败'); }
  };

  const handleApprove = async (id: string) => {
    try { await scmApi.approvePurchaseOrder(id); message.success('已审批'); mutate(); } catch { message.error('审批失败'); }
  };

  const handleSubmitForApproval = async (id: string) => {
    try { await scmApi.submitPurchaseOrder(id); message.success('已提交审批'); mutate(); } catch { message.error('提交失败'); }
  };

  const columns = [
    { title: '采购单号', dataIndex: 'poNumber', key: 'poNumber' },
    { title: '供应商ID', dataIndex: 'supplierId', key: 'supplierId', render: (v: string) => <a href={`/scm/suppliers?supplierId=${v}`}>{v}</a> },
    { title: '金额', dataIndex: 'totalAmount', key: 'totalAmount', render: (v: number, r: PurchaseOrder) => `${r.currency} ${v?.toFixed(2)}` },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => <Badge color={poStatusMap[v]?.color || 'default'} text={poStatusMap[v]?.text || v} /> },
    { title: '累计实收', key: 'received', render: (_: unknown, r: PurchaseOrder) => r.lines.reduce((sum, line) => sum + line.receivedQuantity, 0) },
    { title: '待收数量', key: 'pending', render: (_: unknown, r: PurchaseOrder) => r.lines.reduce((sum, line) => sum + line.pendingQuantity, 0) },
    { title: '超收数量', key: 'over', render: (_: unknown, r: PurchaseOrder) => r.lines.reduce((sum, line) => sum + line.overReceivedQuantity, 0) },
    { title: '适用容差', key: 'tolerance', render: (_: unknown, r: PurchaseOrder) =>
      ['DRAFT', 'SUBMITTED', 'CANCELLED'].includes(r.status) ? '—' : `${r.receiptTolerancePercent}%` },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '操作', key: 'action', render: (_: unknown, r: PurchaseOrder) => (
      <Space wrap>
        {r.status === 'DRAFT' && <Button size="small" onClick={() => handleSubmitForApproval(r.poId)}>提交审批</Button>}
        {r.status === 'SUBMITTED' && <Button size="small" type="primary" icon={<CheckOutlined />} onClick={() => handleApprove(r.poId)}>审批</Button>}
        <Button size="small" onClick={() => setDetails({ order: r, amend: false })}>采购明细</Button>
        {r.amendable && permissions?.canAmend && <Button size="small"
          onClick={() => setDetails({ order: r, amend: true })}>采购变更</Button>}
        <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(r.poId)}`}>收货记录</Link>
        <Link href={`/wms/inventory?purchaseOrderId=${encodeURIComponent(r.poId)}`}>批次结存</Link>
      </Space>
    ) },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>采购单管理</Title>
        <Space>
          <Select placeholder="状态" allowClear style={{ width: 120 }}
            options={Object.entries(poStatusMap).map(([k, v]) => ({ value: k, label: v.text }))}
            onChange={setSelectedStatus} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setModalOpen(true); }}>新建采购单</Button>
        </Space>
      </div>
      {error && <Alert type="error" showIcon message="采购单加载失败" />}
      <Card style={{ borderRadius: 8 }}>
        <Table rowKey="poId" columns={columns} loading={isLoading} size="middle"
          dataSource={(data ?? []).filter((order) => !selectedStatus || order.status === selectedStatus)}
          pagination={{ pageSize: 20 }} />
      </Card>
      {details && <PurchaseDetails order={details.order} amend={details.amend} onClose={() => setDetails(undefined)}
        onSaved={() => { setDetails(undefined); void mutate(); message.success('采购变更已确认，待收数量已更新'); }} />}
      <Modal title="新建采购单" open={modalOpen} onOk={handleSubmit} onCancel={() => setModalOpen(false)} width={560}>
        <Form form={form} layout="vertical">
          <Form.Item name="supplierId" label="供应商" rules={[{ required: true }]}>
            <Select options={(suppliers ?? []).map(({ supplier }) => ({ value: supplier.supplierId, label: supplier.name }))} />
          </Form.Item>
          <Form.Item name="currency" label="币种" initialValue="CNY">
            <Select options={[{ value: 'CNY', label: 'CNY' }, { value: 'USD', label: 'USD' }]} />
          </Form.Item>
          <Form.Item name="purchaseType" label="采购模式" initialValue="MARKET" rules={[{ required: true }]}>
            <Select options={[
              { value: 'MARKET', label: '市场采购' },
              { value: 'FACTORY', label: '工厂采购' },
              { value: 'TAOBAO_TMALL_1688', label: '淘宝天猫采购' },
              { value: 'ALIBABA_1688', label: '1688 采购' },
              { value: 'PROCESSING', label: '加工采购' },
            ]} />
          </Form.Item>
          <Form.List name="lines" initialValue={[{}]}>
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name }) => (
                  <Space key={key} align="baseline" wrap>
                    <Form.Item name={[name, 'sellerSku']} rules={[{ required: true }]}><Input placeholder="SKU" /></Form.Item>
                    <Form.Item name={[name, 'quantity']} rules={[{ required: true }]}><InputNumber min={1} precision={0} placeholder="数量" /></Form.Item>
                    <Form.Item name={[name, 'unitCost']} rules={[{ required: true }]}><InputNumber min={0.01} precision={2} placeholder="单价" /></Form.Item>
                    <Button disabled={fields.length === 1} onClick={() => remove(name)}>移除</Button>
                  </Space>
                ))}
                <Button onClick={() => add()}>添加采购明细</Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
}
