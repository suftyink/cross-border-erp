'use client';

import { useEffect, useState } from 'react';
import { isAxiosError } from 'axios';
import { Alert, Button, Descriptions, Form, Input, Modal, Radio, Select, Space, Typography, message } from 'antd';
import { useApi } from '@/lib/hooks';
import { skuDraftApi, spuPath, type CreateSkuDraft, type SkuDraft, type SpuOption } from '@/lib/pdm-sku';

type FormValues = CreateSkuDraft & { mode: 'EXISTING' | 'NEW' };

interface Props {
  open: boolean;
  sku: SkuDraft | null;
  spuId?: string;
  onCancel: () => void;
  onSaved: () => Promise<void>;
}

export function SkuDraftForm({ open, sku, spuId, onCancel, onSaved }: Props) {
  const [form] = Form.useForm<FormValues>();
  const [saving, setSaving] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mode = Form.useWatch('mode', form);
  const selectedSpu = Form.useWatch('spuId', form);
  const products = useApi<SpuOption[]>(open ? spuPath : null);
  const categories = useApi<{ categoryId: string; name: string }[]>(open ? '/pdm/api/in/v1/categories' : null);
  const brands = useApi<{ brandId: string; name: string }[]>(open ? '/pdm/api/in/v1/brands' : null);
  const parent = products.data?.find((item) => item.spuId === selectedSpu);
  const dictionariesFailed = !!(products.error || categories.error || brands.error);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ mode: 'EXISTING', spuId: sku?.spuId ?? spuId, sellerSku: sku?.sellerSku,
      title: sku?.title ?? '', baseUnit: sku?.baseUnit ?? (sku ? undefined : '件') });
    setError(null);
  }, [open, sku, spuId, form]);

  const generateCode = async () => {
    setGenerating(true);
    try {
      form.setFieldsValue({ sellerSku: await skuDraftApi.generateCode() });
      await form.validateFields(['sellerSku']);
    } catch { setError('编码生成失败，请重试'); }
    finally { setGenerating(false); }
  };

  const submit = async () => {
    if (saving) return;
    let values: FormValues;
    try { values = await form.validateFields(); } catch { return; }
    setSaving(true);
    setError(null);
    try {
      if (sku) {
        await skuDraftApi.update(sku.skuId, { title: values.title ?? '', baseUnit: values.baseUnit ?? '' });
      } else {
        await skuDraftApi.create({ sellerSku: values.sellerSku, title: values.title, baseUnit: values.baseUnit,
          ...(values.mode === 'NEW' ? { newSpu: values.newSpu ?? {} } : { spuId: values.spuId }) });
      }
      message.success(sku ? 'SKU 草稿已更新' : 'SKU 草稿已创建');
      await onSaved().catch(() => { message.warning('已保存，列表刷新失败，请手动刷新'); });
      onCancel();
    } catch (failure) {
      const text = isAxiosError<{ message?: string; code?: string }>(failure)
        ? failure.response?.data.message ?? '保存失败，请重试' : '保存失败，请重试';
      setError(text);
      if (isAxiosError<{ code?: string }>(failure) && failure.response?.data.code === 'SKU_DUPLICATED') {
        form.setFields([{ name: 'sellerSku', errors: [text] }]);
      }
    } finally { setSaving(false); }
  };

  return (
    <Modal title={sku ? '编辑 SKU 草稿' : '新建 SKU 草稿'} open={open} width={640} destroyOnClose
      okText="保存草稿" cancelText="取消" confirmLoading={saving} maskClosable={!saving}
      onOk={() => void submit()} onCancel={() => { if (!saving) onCancel(); }}
      okButtonProps={{ disabled: dictionariesFailed || generating }}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Typography.Text type="secondary">草稿可先保存，启用前需补齐名称、分类和基本库存单位。</Typography.Text>
        {error && <Alert type="error" showIcon message={error} />}
        {dictionariesFailed && <Alert type="error" showIcon message="商品或字典加载失败"
          action={<Button size="small" onClick={() => { void products.mutate(); void categories.mutate(); void brands.mutate(); }}>重试</Button>} />}
        <Form form={form} name="sku-draft" layout="vertical" disabled={saving} validateTrigger="onBlur">
          {!sku && !spuId && <Form.Item name="mode" label="建档方式">
            <Radio.Group options={[{ label: '选择已有 SPU', value: 'EXISTING' }, { label: '同时新建 SPU', value: 'NEW' }]} />
          </Form.Item>}
          {(sku || spuId || mode !== 'NEW') ? <>
            <Form.Item name="spuId" label="所属 SPU" rules={[{ required: true, message: '请选择所属 SPU' }]}>
              <Select showSearch optionFilterProp="label" disabled={!!sku || !!spuId} loading={products.isLoading}
                options={products.data?.map((item) => ({ value: item.spuId, label: `${item.sku ?? '未编码'} · ${item.title ?? '待补名称'}` }))} />
            </Form.Item>
            {parent && <Descriptions size="small" column={2} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="继承分类">{categories.data?.find((item) => item.categoryId === parent.categoryId)?.name ?? '待补齐'}</Descriptions.Item>
              <Descriptions.Item label="继承品牌">{brands.data?.find((item) => item.brandId === parent.brandId)?.name ?? '未设置'}</Descriptions.Item>
            </Descriptions>}
          </> : <>
            <Form.Item name={['newSpu', 'sku']} label="SPU 编码" extra="留空时自动生成，与本地 SKU 编码分别管理。"
              rules={[{ max: 128, message: '最多 128 个字符' }]}><Input maxLength={128} /></Form.Item>
            <Form.Item name={['newSpu', 'title']} label="SPU 名称" rules={[{ max: 512 }]}><Input maxLength={512} /></Form.Item>
            <Space align="start" wrap style={{ width: '100%' }}>
              <Form.Item name={['newSpu', 'categoryId']} label="SPU 分类">
                <Select aria-label="SPU 分类" allowClear showSearch optionFilterProp="label" loading={categories.isLoading} style={{ width: 260 }}
                  options={categories.data?.map((item) => ({ value: item.categoryId, label: item.name }))} />
              </Form.Item>
              <Form.Item name={['newSpu', 'brandId']} label="SPU 品牌（可选）">
                <Select aria-label="SPU 品牌" allowClear showSearch optionFilterProp="label" loading={brands.isLoading} style={{ width: 260 }}
                  options={brands.data?.map((item) => ({ value: item.brandId, label: item.name }))} />
              </Form.Item>
            </Space>
          </>}
          <Form.Item name="sellerSku" label="本地 SKU 编码" extra={sku ? '本次编辑保留编码和所属 SPU。' : '可手输或生成；保存时去除首尾空白并转大写，留空则自动生成。'}
            rules={[{ max: 128, message: '最多 128 个字符' }, { pattern: /^[A-Za-z0-9_-]+$/, transform: (value: string) => value?.trim(), message: '仅支持字母、数字、连字符和下划线' }]}>
            <Input disabled={!!sku} maxLength={128} addonAfter={!sku && <Button type="link" size="small" loading={generating} onClick={() => void generateCode()}>生成编码</Button>} />
          </Form.Item>
          <Form.Item name="title" label="SKU 名称" rules={[{ max: 512 }]}><Input maxLength={512} /></Form.Item>
          <Form.Item name="baseUnit" label="基本库存单位" extra="按整数记库存，箱规不自动换算数量。">
            <Select allowClear disabled={!!sku?.baseUnit} options={['件', '个', '套', '台', '支', '包'].map((value) => ({ value, label: value }))} />
          </Form.Item>
        </Form>
      </Space>
    </Modal>
  );
}
