'use client';

import { Alert, Form, Input, InputNumber, Modal, Typography } from 'antd';
import type { CorrectionAction, CorrectionAttempt } from './quality-correction-types';

export function QualityCorrectionDialog({ action, saving, pending, error, onClose, onSubmit }: {
  action: CorrectionAction; saving: boolean; pending?: CorrectionAttempt; error?: string;
  onClose: () => void; onSubmit: (values: Record<string, unknown>) => void;
}) {
  const [form] = Form.useForm();
  const titles = { pause: '发起质检更正', reverse: '确认撤销上架', unpick: '确认撤拣退回待检', inspection: '更正后重新全检' };
  const quantity = 'correction' in action ? action.correction.quantity : undefined;
  return <Modal open title={titles[action.kind]} onCancel={onClose} maskClosable={false} closable={!saving} keyboard={!saving}
    cancelButtonProps={{ disabled: saving }} confirmLoading={saving} okText={pending ? '重试原操作' : titles[action.kind]}
    onOk={() => pending ? onSubmit({}) : form.submit()}>
    {error && <Alert type="error" showIcon message={error} />}
    {'correction' in action && <Typography.Paragraph>{action.correction.sellerSku} · 本次更正 {quantity} 件</Typography.Paragraph>}
    {action.kind === 'reverse' && <Typography.Paragraph>原库位 {action.placement.locationCode}；确认实物已移回待上架区。</Typography.Paragraph>}
    {action.kind === 'unpick' && <Typography.Paragraph>本次撤拣 {action.pick.quantity - action.pick.returnedQuantity - action.pick.shippedQuantity} 件；确认实物从发货暂存区直接移到待检区，相关未发货包裹将作废。</Typography.Paragraph>}
    {action.kind === 'pause' && <Alert type="info" showIcon message="按原批次暂停仍在库的良品；已发货历史保留。发起后须完成实物退回和重新质检。" />}
    <Form form={form} layout="vertical" disabled={saving || !!pending} onFinish={onSubmit}>
      {action.kind === 'pause' && <>
        <Form.Item name="batchNo" label="原收货批次码" rules={[{ required: true, whitespace: true }]}><Input aria-label="原收货批次码" maxLength={64} /></Form.Item>
        <Form.Item name="reason" label="更正原因" rules={[{ required: true, whitespace: true }]}><Input.TextArea aria-label="更正原因" maxLength={500} rows={3} /></Form.Item>
      </>}
      {(action.kind === 'reverse' || action.kind === 'unpick') && <>
        <Form.Item name="locationCode" label="原实物库位码" rules={[{ required: true, whitespace: true }]}><Input aria-label="原实物库位码" maxLength={128} autoFocus /></Form.Item>
        {action.kind === 'unpick' && <Form.Item name="batchNo" label="实物批次码" rules={[{ required: true, whitespace: true }]}><Input aria-label="实物批次码" maxLength={64} /></Form.Item>}
        {action.kind === 'reverse' && <Form.Item name="quantity" label="本次撤销上架件数" rules={[{ required: true }, { type: 'integer', min: 1, max: action.placement.quantity - action.placement.reversedQuantity }]}>
          <InputNumber aria-label="本次撤销上架件数" min={1} max={action.placement.quantity - action.placement.reversedQuantity} precision={0} />
        </Form.Item>}
      </>}
      {action.kind === 'inspection' && <>
        <Typography.Paragraph>对本次退回的 {quantity} 件完成全检后确认，良品仍需重新上架才可用。</Typography.Paragraph>
        <Form.Item name="goodQuantity" label="全检良品件数" rules={[{ required: true }, { type: 'integer', min: 0, max: quantity }]}><InputNumber aria-label="全检良品件数" min={0} max={quantity} precision={0} /></Form.Item>
        <Form.Item name="defectiveQuantity" label="全检次品件数" dependencies={['goodQuantity']} rules={[{ required: true }, { type: 'integer', min: 0, max: quantity },
          ({ getFieldValue }) => ({ validator: async (_, value: unknown) => { if (typeof value === 'number' && value + Number(getFieldValue('goodQuantity')) !== quantity) throw new Error('良品与次品之和须等于本次更正数量'); } })]}>
          <InputNumber aria-label="全检次品件数" min={0} max={quantity} precision={0} />
        </Form.Item>
      </>}
    </Form>
  </Modal>;
}
