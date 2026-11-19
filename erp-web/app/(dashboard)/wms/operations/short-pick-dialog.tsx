'use client';

import { Alert, Descriptions, Form, Input, InputNumber, Modal, Select, Table, Typography } from 'antd';
import { useApi } from '@/lib/hooks';
import type { ShortPickAction, ShortPickAttempt } from './short-pick-types';
import { shortPickEndpoint } from './short-pick-types';

export function ShortPickDialog({ action, saving, pending, error, onClose, onSubmit }: {
  action: ShortPickAction; saving: boolean; pending?: ShortPickAttempt; error?: string;
  onClose: () => void; onSubmit: (values: Record<string, unknown>) => void;
}) {
  const [form] = Form.useForm();
  const candidates = useApi<{ batchNo: string; quantity: number }[]>(action.kind === 'replace'
    ? `${shortPickEndpoint}/${action.review.shortPickId}/replacement-candidates` : null);
  const titles = { report: '登记缺拣', replace: '确认同库位换批', count: '录入缺拣复盘', confirm: '主管确认复盘' };
  const allocations = action.kind === 'report' ? action.source.allocations.filter((a) => a.plannedQuantity - a.pickedQuantity - a.shortQuantity - a.cancelledQuantity > 0) : [];
  const selectedAllocation = Form.useWatch('allocationId', form);
  const remaining = allocations.find((a) => a.allocationId === selectedAllocation);
  return <Modal open title={titles[action.kind]} width={720} onCancel={onClose} maskClosable={false}
    closable={!saving} keyboard={!saving} cancelButtonProps={{ disabled: saving }} confirmLoading={saving}
    okText={pending ? '重试原作业' : titles[action.kind]} onOk={() => pending || action.kind === 'confirm' ? onSubmit({}) : form.submit()}>
    {error && <Alert type="error" showIcon message={error} />}
    {'review' in action && <Descriptions size="small" column={1} items={[
      { key: 'source', label: '原库位与批次', children: `${action.review.locationCode} · ${action.review.batchNo}` },
      { key: 'quantity', label: '原缺拣数量', children: action.review.shortQuantity },
      { key: 'reason', label: '缺拣原因', children: action.review.reason },
    ]} />}
    {action.kind === 'confirm' ? <>
      <Typography.Paragraph>找回 {action.review.foundQuantity} 件，确认短少 {action.review.missingQuantity} 件。</Typography.Paragraph>
      <Alert type="warning" showIcon message="主管确认后，找回数量恢复可用，短少数量扣减在手，并记录确认人及调整流水。" />
    </> : <Form form={form} layout="vertical" disabled={saving || !!pending} onFinish={onSubmit}
      initialValues={action.kind === 'count' ? { foundQuantity: action.review.foundQuantity,
        missingQuantity: action.review.missingQuantity, remark: action.review.countRemark } : undefined}>
      {action.kind === 'report' && <>
        <Form.Item name="allocationId" label="原拣货分配明细" rules={[{ required: true }]}>
          <Select aria-label="原拣货分配明细" options={allocations.map((a) => ({ value: a.allocationId,
            label: `${a.sellerSku} · ${a.locationCode} · ${a.batchNo} · 待拣 ${a.plannedQuantity - a.pickedQuantity - a.shortQuantity - a.cancelledQuantity}` }))} />
        </Form.Item>
        <Form.Item name="quantity" label="本次缺拣件数" rules={[{ required: true }, { type: 'integer', min: 1,
          max: remaining ? remaining.plannedQuantity - remaining.pickedQuantity - remaining.shortQuantity - remaining.cancelledQuantity : undefined }]}>
          <InputNumber aria-label="本次缺拣件数" min={1} precision={0} />
        </Form.Item>
        <Form.Item name="reason" label="缺拣原因" rules={[{ required: true, whitespace: true }]}>
          <Input.TextArea aria-label="缺拣原因" maxLength={500} />
        </Form.Item>
        <Alert type="info" showIcon message="登记后将对应数量从订单预占转入待复核，保留账面在手并暂停分配。" />
      </>}
      {action.kind === 'replace' && <>
        {candidates.error && <Alert type="warning" showIcon message="候选批次加载失败，可输入实际标签，提交时会重新核验。" />}
        <Table size="small" rowKey="batchNo" pagination={false} loading={candidates.isLoading} dataSource={candidates.data ?? []}
          columns={[{ title: '同库位候选批次', dataIndex: 'batchNo' }, { title: '当前可用', dataIndex: 'quantity' }]} />
        <Form.Item name="locationCode" label="实际库位码" rules={[{ required: true, whitespace: true }]}>
          <Input aria-label="实际库位码" maxLength={128} />
        </Form.Item>
        <Form.Item name="batchNo" label="替代批次码" rules={[{ required: true, whitespace: true }]}>
          <Input aria-label="替代批次码" maxLength={64} />
        </Form.Item>
        <Alert type="info" showIcon message={`仅补占原缺拣的 ${action.review.shortQuantity} 件。确认后仍需扫描替代批次登记实际拣货。`} />
      </>}
      {action.kind === 'count' && <>
        <Form.Item name="foundQuantity" label="找回件数" rules={[{ required: true }, { type: 'integer', min: 0, max: action.review.shortQuantity }]}>
          <InputNumber aria-label="找回件数" min={0} max={action.review.shortQuantity} precision={0} />
        </Form.Item>
        <Form.Item name="missingQuantity" label="确认短少件数" dependencies={['foundQuantity']} rules={[{ required: true },
          { type: 'integer', min: 0, max: action.review.shortQuantity }, ({ getFieldValue }) => ({ validator(_, value) {
            return typeof value !== 'number' || typeof getFieldValue('foundQuantity') !== 'number'
              || value + getFieldValue('foundQuantity') === action.review.shortQuantity
              ? Promise.resolve() : Promise.reject(new Error('找回与短少之和须等于原缺拣数量'));
          } })]}>
          <InputNumber aria-label="确认短少件数" min={0} max={action.review.shortQuantity} precision={0} />
        </Form.Item>
        <Form.Item name="remark" label="复盘说明"><Input.TextArea aria-label="复盘说明" maxLength={500} /></Form.Item>
      </>}
    </Form>}
  </Modal>;
}
