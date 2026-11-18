'use client';

import { useState } from 'react';
import { Alert, Form, Input, InputNumber, Modal, Table, Typography } from 'antd';
import { isAxiosError } from 'axios';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import type { PurchaseAmendmentLine, PurchaseOrder, PurchaseOrderItem, SupplierReturn } from '@/types';

interface AmendmentForm {
  reason: string;
  quantities: Record<string, number | null>;
}

export function PurchaseDetails({ order, amend, onClose, onSaved }: {
  order: PurchaseOrder;
  amend: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<AmendmentForm>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const endpoint = `/scm/api/in/v1/purchase-orders/${encodeURIComponent(order.poId)}/amendments`;
  const history = useApi<PurchaseAmendmentLine[]>(endpoint);
  const returns = useApi<SupplierReturn[]>(`/scm/api/in/v1/purchase-orders/${encodeURIComponent(order.poId)}/supplier-returns`);
  const columns = [
    { title: 'SKU', dataIndex: 'sellerSku' },
    { title: '原订购', dataIndex: 'originalQuantity' },
    { title: '现订购', dataIndex: 'quantity' },
    { title: '累计实收', dataIndex: 'receivedQuantity' },
    { title: '超收', dataIndex: 'overReceivedQuantity' },
    { title: '已退', render: (_: unknown, line: PurchaseOrderItem) => returns.error ? '—'
      : returns.data?.reduce((total, item) => item.purchaseOrderLineId === line.lineId
        ? total + (item.shippedQuantity ?? 0) : total, 0) ?? '—' },
    { title: '待收', dataIndex: 'pendingQuantity' },
  ];

  const save = async (values: AmendmentForm) => {
    if (saving) return;
    const lines = Object.entries(values.quantities ?? {}).flatMap(([lineId, quantity]) =>
      quantity == null ? [] : [{ lineId, quantity }]);
    if (lines.length === 0) {
      setError('请至少填写一行变更后订购量。');
      return;
    }
    setSaving(true);
    setError(undefined);
    try {
      await post(endpoint, { reason: values.reason, lines });
      await history.mutate();
      onSaved();
    } catch (failure) {
      setError(isAxiosError(failure) ? failure.response?.data?.message ?? '采购变更保存失败，请刷新查看变更记录。'
        : '采购变更保存失败，请刷新查看变更记录。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={`${amend ? '采购变更' : '采购明细'} · ${order.poNumber}`} open
      width={amend ? 900 : 780} okText="确认变更" onOk={() => form.submit()} onCancel={onClose}
      footer={amend ? undefined : null} confirmLoading={saving} closable={!saving}
      cancelButtonProps={{ disabled: saving }} maskClosable={!saving} keyboard={!saving}>
      <Typography.Paragraph>审批锁定容差 {order.receiptTolerancePercent}%</Typography.Paragraph>
      {error && <Alert showIcon type="error" message={error} />}
      {amend ? (
        <Form form={form} layout="vertical" onFinish={save} disabled={saving}>
          <Table<PurchaseOrderItem> size="small" rowKey="lineId" pagination={false}
            dataSource={order.lines} columns={[...columns, {
              title: '变更后订购量',
              render: (_, line) => {
                const minimum = Math.max(line.quantity, line.receivedQuantity) + 1;
                return <Form.Item name={['quantities', line.lineId]} style={{ margin: 0 }}
                  rules={[{ type: 'integer', min: minimum, max: 2147483647, message: `请填写至少 ${minimum} 件` }]}>
                  <InputNumber aria-label={`${line.sellerSku} 变更后订购量`}
                    min={minimum} max={2147483647} precision={0} />
                </Form.Item>;
              },
            }]} />
          <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
            填写要变更的采购行。变更后订购量减去累计实收量，就是仓库可继续收货的数量。
          </Typography.Paragraph>
          <Form.Item name="reason" label="变更原因" rules={[{ required: true, whitespace: true }]}>
            <Input.TextArea maxLength={500} />
          </Form.Item>
        </Form>
      ) : <Table size="small" rowKey="lineId" pagination={false} dataSource={order.lines} columns={columns} />}
      <Typography.Title level={5} style={{ marginTop: 16 }}>采购变更记录</Typography.Title>
      {history.error && <Alert showIcon type="error" message="采购变更记录加载失败" />}
      {returns.error && <Alert showIcon type="error" message="退供数量加载失败" />}
      <Table<PurchaseAmendmentLine> size="small" rowKey={(line) => `${line.amendmentId}/${line.lineId}`}
        loading={history.isLoading} pagination={{ pageSize: 5 }} dataSource={history.data ?? []} columns={[
          { title: 'SKU', render: (_, line) => order.lines.find((item) => item.lineId === line.lineId)?.sellerSku ?? line.lineId },
          { title: '变更前', dataIndex: 'previousQuantity' },
          { title: '变更后', dataIndex: 'revisedQuantity' },
          { title: '原因', dataIndex: 'reason' },
          { title: '操作人', dataIndex: 'operatorId' },
          { title: '时间', render: (_, line) => new Date(line.createdAt).toLocaleString() },
        ]} />
    </Modal>
  );
}
