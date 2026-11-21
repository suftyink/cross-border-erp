'use client';

import { useEffect, useState } from 'react';
import { Alert, Descriptions, Form, Input, Modal, Space, Tabs, Typography, message } from 'antd';
import { productDataApi, type Dimensions, type ProductData } from '@/lib/pdm-product-data';
import { specificationError } from '@/lib/pdm-specification';
import { ProductImage, ProductImagesEditor } from './product-images';
import { ProductBarcodeFields, ProductDeclarationFields, ProductLogisticsFields } from './product-data-fields';
import { SkuUsagePanel } from './sku-usage-panel';

export function SkuProductDetailsEditor({ skuId, onClose, onSaved }: { skuId: string | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const [form] = Form.useForm<ProductData>();
  const [loadedSkuId, setLoadedSkuId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string>();
  useEffect(() => {
    let cancelled = false;
    setLoadedSkuId(null); form.resetFields(); setError(undefined);
    if (skuId) void productDataApi.get(skuId).then(data => {
      if (!cancelled) { form.setFieldsValue(data); setLoadedSkuId(skuId); }
    }).catch(failure => { if (!cancelled) setError(specificationError(failure)); });
    return () => { cancelled = true; };
  }, [skuId, form]);
  const save = async () => {
    if (!skuId || loadedSkuId !== skuId || saving || uploading) return;
    try { await form.validateFields(); } catch { return; }
    setSaving(true); setError(undefined);
    try {
      await productDataApi.replace(skuId, form.getFieldsValue(true));
      message.success('商品资料已保存'); await onSaved().catch(() => message.warning('已保存，请手动刷新列表')); onClose();
    } catch (failure) { setError(specificationError(failure)); } finally { setSaving(false); }
  };
  return <Modal title="维护商品资料" open={!!skuId} width={960} destroyOnClose maskClosable={!saving && !uploading}
    onCancel={() => { if (!saving && !uploading) onClose(); }} onOk={() => void save()} confirmLoading={saving}
    okText="保存商品资料" cancelText="取消" okButtonProps={{ disabled: uploading || loadedSkuId !== skuId }} styles={{ body: { maxHeight: '72vh', overflowY: 'auto' } }}>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
    {!error && skuId && loadedSkuId !== skuId && <Typography.Paragraph>正在加载最新商品资料…</Typography.Paragraph>}
    <Form form={form} layout="vertical" disabled={saving || loadedSkuId !== skuId}>
      <Tabs items={[
        { key: 'logistics', label: '物流与包装', forceRender: true, children: <ProductLogisticsFields /> },
        { key: 'declaration', label: '申报资料', forceRender: true, children: <ProductDeclarationFields /> },
        { key: 'images', label: '图片与说明', forceRender: true, children: <>
          <Form.Item name="images" label="商品图片"><ProductImagesEditor disabled={saving || loadedSkuId !== skuId} onUploadingChange={setUploading} /></Form.Item>
          <ProductBarcodeFields />
          <Form.Item name="description" label="商品说明" rules={[{ max: 4000 }]}><Input.TextArea rows={3} maxLength={4000} /></Form.Item>
          <Form.Item name="remarks" label="备注" rules={[{ max: 2000 }]}><Input.TextArea rows={3} maxLength={2000} /></Form.Item>
        </> },
        { key: 'usage', label: '使用检查', children: skuId ? <SkuUsagePanel key={skuId} skuId={skuId} /> : null },
      ]} />
    </Form>
  </Modal>;
}

const dimensions = (value: Dimensions | null | undefined) => value ? `${value.lengthCm ?? '—'} × ${value.widthCm ?? '—'} × ${value.heightCm ?? '—'} cm` : '未填写';
export function SkuProductDetailsView({ data }: { data?: ProductData | null }) {
  if (!data) return <Typography.Text type="secondary">暂无扩展资料</Typography.Text>;
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Space wrap>{data.images.map(image => <ProductImage key={image.assetId ?? image.url} image={image} size={88} />)}</Space>
    <Descriptions bordered column={2} size="small">
      <Descriptions.Item label="商品重量（kg）">{data.weightKg ?? '未填写'}</Descriptions.Item>
      <Descriptions.Item label="单件含包装重量（kg）">{data.packageWeightKg ?? '未填写'}</Descriptions.Item>
      <Descriptions.Item label="商品尺寸">{dimensions(data.productDimensionsCm)}</Descriptions.Item>
      <Descriptions.Item label="单品包装尺寸">{dimensions(data.packageDimensionsCm)}</Descriptions.Item>
      <Descriptions.Item label="装箱数量">{data.unitsPerCarton ?? '未填写'}</Descriptions.Item>
      <Descriptions.Item label="外箱尺寸">{dimensions(data.cartonDimensionsCm)}</Descriptions.Item>
      <Descriptions.Item label="整箱毛重（kg）">{data.cartonWeightKg ?? '未填写'}</Descriptions.Item>
      <Descriptions.Item label="申报金额">{data.declaredValue == null ? '未填写' : `${data.declaredValue} ${data.currency ?? ''}`}</Descriptions.Item>
      <Descriptions.Item label="中文申报品名">{data.declaration?.nameChinese || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="英文申报品名">{data.declaration?.nameEnglish || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="HS 编码">{data.declaration?.hsCode || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="原产国">{data.declaration?.countryOfOrigin || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="材质">{data.declaration?.material || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="用途">{data.declaration?.usage || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="外部条码" span={2}>{data.barcodes.map(b => `${b.type}: ${b.value}`).join('；') || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="商品说明" span={2}>{data.description || '未填写'}</Descriptions.Item>
      <Descriptions.Item label="备注" span={2}>{data.remarks || '未填写'}</Descriptions.Item>
    </Descriptions>
  </Space>;
}
