'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Alert, Button, Form, Modal, Select, Space, Tag, Typography, message } from 'antd';
import { mutate } from 'swr';
import { useApi } from '@/lib/hooks';
import { put } from '@/lib/api/client';
import { specificationError, specificationOptionsPath, specificationPath, type SpecificationAttribute, type SpecificationOptions } from '@/lib/pdm-specification';

type Fields = { dimensions: { attributeId?: string; valueIds: string[] }[] };

export function SpuSpecifications({ spuId, canMaintain }: { spuId: string; canMaintain: boolean }) {
  const options = useApi<SpecificationOptions>(specificationOptionsPath(spuId));
  const dictionary = useApi<SpecificationAttribute[]>(specificationPath);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [form] = Form.useForm<Fields>();
  const dimensions = Form.useWatch('dimensions', form) ?? [];
  const invalidOptions = options.data !== undefined && !Array.isArray(options.data?.dimensions);
  const invalidDictionary = dictionary.data !== undefined && !Array.isArray(dictionary.data);
  const currentDimensions = Array.isArray(options.data?.dimensions) ? options.data.dimensions : [];
  const attributes = Array.isArray(dictionary.data) ? dictionary.data : [];
  const submit = async () => {
    let values: Fields;
    try { values = await form.validateFields(); } catch { return; }
    setBusy(true); setError(undefined);
    try {
      await put(specificationOptionsPath(spuId), { dimensions: values.dimensions ?? [] }); setOpen(false); message.success('SPU 规格范围已保存');
      await mutate((key: unknown) => typeof key === 'string' && key.startsWith('/pdm/api/in/v1/'))
        .catch(() => message.warning('已保存，请手动刷新'));
    } catch (failure) { setError(specificationError(failure)); } finally { setBusy(false); }
  };
  return <Space direction="vertical" style={{ width: '100%', marginBottom: 20 }}>
    <Space wrap><Typography.Text strong>允许的规格范围</Typography.Text>
      {canMaintain && <Button size="small" disabled={!!options.error || options.isLoading || !!dictionary.error || invalidOptions || invalidDictionary} onClick={() => {
        form.setFieldsValue({ dimensions: currentDimensions.map(d => ({ attributeId: d.attributeId, valueIds: d.values.map(v => v.valueId) })) });
        setError(undefined); setOpen(true);
      }}>配置规格</Button>}<Link href="/pdm/specifications">共享规格字典</Link></Space>
    {(options.error || dictionary.error || invalidOptions || invalidDictionary) && <Alert showIcon type="error"
      message={invalidOptions || invalidDictionary ? '规格范围或字典接口返回格式错误' : '规格范围或字典加载失败'}
      action={<Button onClick={() => { void options.mutate(); void dictionary.mutate(); }}>重试</Button>} />}
    {!options.isLoading && !options.error && !invalidOptions && !currentDimensions.length && <Typography.Text type="secondary">未启用多规格，普通商品无须填写规格。</Typography.Text>}
    {currentDimensions.map(d => <div key={d.attributeId}><Typography.Text>{d.attributeName}：</Typography.Text>{d.values.map(v => <Tag key={v.valueId}>{v.name}</Tag>)}</div>)}
    <Modal title="配置 SPU 规格范围" open={open} onCancel={() => { if (!busy) setOpen(false); }} onOk={() => void submit()} confirmLoading={busy}
      okText="保存范围" cancelText="取消" width={760} maskClosable={!busy} destroyOnClose>
      <Alert type="info" showIcon message="从共享字典选择每个维度允许的值。现有组合及已锁定商品受保护；不能通过改范围替换它们的规格。" style={{ marginBottom: 16 }} />
      {error && <Alert showIcon type="error" message={error} style={{ marginBottom: 16 }} />}
      <Form form={form} layout="vertical" disabled={busy}>
        <Form.List name="dimensions">{(fields, { add, remove }) => <>
          {fields.map(field => <Space key={field.key} align="start" wrap>
            <Form.Item name={[field.name, 'attributeId']} label="规格属性" rules={[{ required: true, message: '请选择规格属性' }]}>
              <Select aria-label={`规格属性 ${field.name + 1}`} style={{ width: 200 }} onChange={() => form.setFieldValue(['dimensions', field.name, 'valueIds'], [])}
                options={attributes.map(a => ({ value: a.attributeId, label: `${a.name} (${a.code})`, disabled: dimensions.some((d, i) => i !== field.name && d.attributeId === a.attributeId) }))} />
            </Form.Item>
            <Form.Item name={[field.name, 'valueIds']} label="允许的规格值" rules={[{ required: true, type: 'array', min: 1, message: '至少选择一个值' }]}>
              <Select mode="multiple" aria-label={`允许的规格值 ${field.name + 1}`} style={{ width: 320 }}
                options={attributes.find(a => a.attributeId === dimensions[field.name]?.attributeId)?.values.map(v => ({ value: v.valueId, label: `${v.name} (${v.code})` }))} />
            </Form.Item>
            <Button danger style={{ marginTop: 30 }} onClick={() => remove(field.name)}>移除维度</Button>
          </Space>)}
          <Button disabled={fields.length >= 20 || busy} onClick={() => add({ valueIds: [] })}>添加规格维度</Button>
        </>}</Form.List>
      </Form>
    </Modal>
  </Space>;
}
