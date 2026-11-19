'use client';

import { Alert, Form, Input, InputNumber, Modal, Select, Typography } from 'antd';
import { channels } from './recall-types';
import type { RecallAction } from './recall-types';
export function RecallDialog({ action, saving, retry, error, onClose, onSubmit }: {
  action: RecallAction; saving: boolean; retry: boolean; error?: string; onClose: () => void; onSubmit: (values: Record<string, unknown>) => void;
}) {
  const [form] = Form.useForm();
  const titles = { create: '创建批次召回单', notify: '登记通知记录', return: '创建销售退货单', verify: '核实退货批次', close: '异常结案' };
  return <Modal open title={titles[action.kind]} onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={retry ? '重试原操作' : titles[action.kind]}
    onOk={() => retry ? onSubmit({}) : form.submit()}>
    {error && <Alert type="error" showIcon message={error} />}
    {action.kind === 'create' && <Typography.Paragraph>按该批次尚未纳入召回的实际发货记录建立范围：{action.batchNo}</Typography.Paragraph>}
    {action.kind === 'return' && <Typography.Paragraph>目标批次应召回 {action.line.expectedQuantity} 件，尚可建退货单 {action.line.expectedQuantity - action.line.authorizedQuantity} 件。</Typography.Paragraph>}
    {action.kind === 'close' && <>
      <Typography.Paragraph>应召回 {action.line.expectedQuantity} 件 · 已实收 {action.line.receivedQuantity} 件 · 已确认召回 {action.line.confirmedQuantity} 件 · 批次不明 {action.line.unidentifiedQuantity} 件</Typography.Paragraph>
      <Alert type="warning" showIcon message="结案会保留当前数量快照。隔离货物继续冻结，既有退货单仍可记录后续实收与核实；结案快照保持不变。" />
    </>}
    {action.kind === 'verify' && <>
      {action.receipt.unidentified && <Alert type="warning" showIcon message={`原实收批次不明，实物在隔离库位 ${action.receipt.quarantineLocationCode}。核实后在原库位转待检并保持冻结。`} />}
      <Typography.Paragraph>本次实收 {action.receipt.quantity} 件。核实实物属于目标批次后计入召回进度。</Typography.Paragraph>
      <Typography.Paragraph copyable>{action.order.expectedBatchNo}</Typography.Paragraph>
    </>}
    <Form form={form} layout="vertical" disabled={saving || retry} onFinish={onSubmit}>
      {(action.kind === 'create' || action.kind === 'close') && <Form.Item name="reason" label={action.kind === 'close' ? '异常结案原因' : '召回原因'} rules={[{ required: true, whitespace: true }]}><Input.TextArea aria-label={action.kind === 'close' ? '异常结案原因' : '召回原因'} maxLength={500} rows={3} /></Form.Item>}
      {action.kind === 'notify' && <>
        <Form.Item name="channel" label="已通知渠道" rules={[{ required: true }]}><Select aria-label="已通知渠道" options={Object.entries(channels).map(([value, label]) => ({ value, label }))} /></Form.Item>
        <Form.Item name="content" label="通知结果记录" rules={[{ required: true, whitespace: true }]}><Input.TextArea aria-label="通知结果记录" maxLength={1000} rows={3} /></Form.Item>
      </>}
      {action.kind === 'return' && <Form.Item name="quantity" label="本次应退件数" rules={[{ required: true }, { type: 'integer', min: 1, max: action.line.expectedQuantity - action.line.authorizedQuantity }]}>
        <InputNumber aria-label="本次应退件数" min={1} max={action.line.expectedQuantity - action.line.authorizedQuantity} precision={0} />
      </Form.Item>}
      {action.kind === 'verify' && <Form.Item name="batchNo" label="核实后的实物批次码" rules={[{ required: true, whitespace: true }]}><Input aria-label="核实后的实物批次码" maxLength={64} autoFocus /></Form.Item>}
    </Form>
  </Modal>;
}
