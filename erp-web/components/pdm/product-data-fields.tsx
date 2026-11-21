'use client';

import { Button, Col, Form, Input, InputNumber, Row, Select, Space, Typography } from 'antd';

function DecimalField({ name, label, scale = 4, digits = 6 }: { name: string | string[]; label: string; scale?: number; digits?: number }) {
  return <Form.Item name={name} label={label} rules={[{ validator: (_, value) => {
    if (value == null || value === '' || new RegExp(`^\\d{1,${digits}}(?:\\.\\d{1,${scale}})?$`).test(String(value))) return Promise.resolve();
    return Promise.reject(new Error(`请输入最多 ${digits} 位整数、${scale} 位小数的非负数`));
  } }]}><InputNumber stringMode style={{ width: '100%' }} /></Form.Item>;
}
function DimensionFields({ name, label }: { name: string; label: string }) {
  return <><Typography.Text strong>{label}</Typography.Text><Row gutter={16}>
    {(['lengthCm', 'widthCm', 'heightCm'] as const).map((field, index) => <Col span={8} key={field}>
      <DecimalField name={[name, field]} label={`${label}${['长', '宽', '高'][index]}（cm）`} />
    </Col>)}
  </Row></>;
}
export function ProductLogisticsFields() {
  return <>
    <Row gutter={16}><Col span={12}><DecimalField name="weightKg" label="商品重量（kg）" /></Col><Col span={12}><DecimalField name="packageWeightKg" label="单件含包装重量（kg）" /></Col></Row>
    <DimensionFields name="productDimensionsCm" label="商品尺寸" />
    <DimensionFields name="packageDimensionsCm" label="单品包装" />
    <Typography.Paragraph type="secondary">按商品资料试算运费时，优先使用单件含包装重量，其次使用商品重量。</Typography.Paragraph>
    <Row gutter={16}><Col span={12}><Form.Item name="unitsPerCarton" label="装箱数量（基本单位／箱）" extra="只作包装资料，不换算库存。" rules={[{ validator: (_, value) =>
      value == null || (Number.isInteger(Number(value)) && Number(value) > 0 && Number(value) <= 2147483647) ? Promise.resolve() : Promise.reject(new Error('请输入正整数')) }]}>
      <InputNumber style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><DecimalField name="cartonWeightKg" label="整箱毛重（kg）" /></Col></Row>
    <DimensionFields name="cartonDimensionsCm" label="外箱" />
  </>;
}
export function ProductDeclarationFields() {
  return <>
    <Row gutter={16}><Col span={12}><DecimalField name="declaredValue" label="单件申报金额" digits={16} scale={2} /></Col><Col span={12}>
      <Form.Item name="currency" label="申报币种" rules={[{ pattern: /^[A-Za-z]{3}$/, message: '请填写三位字母币种代码' }]}><Input maxLength={3} placeholder="USD / CNY" /></Form.Item>
    </Col></Row>
    <Row gutter={16}>{[
      ['nameChinese', '中文申报品名', 255], ['nameEnglish', '英文申报品名', 255], ['hsCode', 'HS 编码', 20],
      ['countryOfOrigin', '原产国（二位字母代码）', 2], ['material', '材质', 255], ['usage', '用途', 255],
    ].map(([name, label, max]) => <Col span={12} key={String(name)}><Form.Item name={['declaration', String(name)]} label={String(label)} rules={[{ max: Number(max) }]}>
      <Input maxLength={Number(max)} /></Form.Item></Col>)}</Row>
  </>;
}
export function ProductBarcodeFields() {
  return <Form.List name="barcodes">{(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
    <Typography.Text strong>外部条码</Typography.Text>
    {fields.map(field => <Space key={field.key} align="start">
      <Form.Item name={[field.name, 'type']} rules={[{ required: true }]}><Select aria-label={`条码类型 ${field.name + 1}`} style={{ width: 120 }}
        options={['UPC', 'EAN', 'GTIN', 'OTHER'].map(value => ({ value, label: value }))} /></Form.Item>
      <Form.Item name={[field.name, 'value']} rules={[{ required: true, whitespace: true }, { max: 128 }]}>
        <Input aria-label={`外部条码 ${field.name + 1}`} maxLength={128} style={{ width: 300 }} /></Form.Item>
      <Button danger onClick={() => remove(field.name)}>移除条码</Button>
    </Space>)}
    <Button disabled={fields.length >= 10} onClick={() => add({ type: 'OTHER', value: '' })}>添加条码</Button>
  </Space>}</Form.List>;
}
