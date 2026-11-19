'use client';

import { useRef, useState } from 'react';
import { Alert, Form, Input, Modal, Typography } from 'antd';
import { isAxiosError } from 'axios';
import { post } from '@/lib/api/client';

export interface BoxLabelPlan { amazonShipmentId: string; boxNumbers: string[]; registeredBy: string; registeredAt: string }
export function FbaBoxLabels({ shipment, plan, onClose, onSaved }: {
  shipment: { fbaShipmentId: string; amazonShipmentId: string }; plan?: BoxLabelPlan | null; onClose: () => void; onSaved: () => Promise<unknown>;
}) {
  const [form] = Form.useForm<{ amazonShipmentId: string; numbers: string }>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const submitting = useRef(false);
  const numbers = Form.useWatch('numbers', form) ?? '';
  const submit = async (values: { amazonShipmentId: string; numbers: string }) => {
    if (submitting.current) return;
    submitting.current = true; setSaving(true); setError(undefined);
    try {
      await post(`/fba/api/in/v1/shipments/${shipment.fbaShipmentId}/box-labels`, {
        amazonShipmentId: values.amazonShipmentId.trim(), boxNumbers: values.numbers.split(/\r?\n/).map(n => n.trim()).filter(Boolean),
      });
      await onSaved(); onClose();
    } catch (failure) {
      setError(isAxiosError(failure) ? failure.response?.data?.message ?? '箱号登记结果未确认，请保持原清单重试，或刷新查看登记结果' : '箱号登记失败，请重试');
    } finally { submitting.current = false; setSaving(false); }
  };
  return <Modal open title={plan ? 'Seller Central 箱号清单' : '登记 Seller Central 箱号'} width={680} maskClosable={false}
    onCancel={() => !saving && onClose()} closable={!saving} keyboard={!saving} cancelButtonProps={{ disabled: saving }}
    onOk={() => plan ? onClose() : form.submit()} okText={plan ? '关闭' : '确认完整箱号清单'} confirmLoading={saving}>
    <Alert type="info" showIcon message="请按 Seller Central 的完整清单登记，每行一个 FBA 箱号。确认后仓管按清单贴标并逐箱绑定。" />
    {error && <Alert type="error" showIcon message={error} />}
    {plan && <Typography.Paragraph>已登记 {plan.boxNumbers.length} 箱 · {plan.registeredBy} · {new Date(plan.registeredAt).toLocaleString()}</Typography.Paragraph>}
    <Form form={form} layout="vertical" disabled={saving || !!plan} onFinish={submit} initialValues={{
      amazonShipmentId: plan?.amazonShipmentId ?? (shipment.amazonShipmentId.startsWith('LOCAL-') ? '' : shipment.amazonShipmentId),
      numbers: plan?.boxNumbers.join('\n') ?? '',
    }}>
      <Form.Item label="Amazon 货件号" name="amazonShipmentId" rules={[{ required: true, whitespace: true, max: 128 }]}><Input aria-label="Amazon 货件号" /></Form.Item>
      <Form.Item label="FBA 箱号清单（每行一个）" name="numbers" rules={[{ required: true, whitespace: true, message: '请输入完整箱号清单' }]}>
        <Input.TextArea aria-label="FBA 箱号清单（每行一个）" rows={10} />
      </Form.Item>
      <Typography.Text>本次清单 {numbers.split(/\r?\n/).filter(n => n.trim()).length} 箱</Typography.Text>
    </Form>
  </Modal>;
}
