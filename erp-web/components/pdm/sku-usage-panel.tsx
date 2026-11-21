'use client';

import { useState } from 'react';
import { Alert, Button, Descriptions, Input, InputNumber, Select, Space, Typography } from 'antd';
import { post } from '@/lib/api/client';
import { productDataApi, type ProductUsage, type ProductUsageCheck } from '@/lib/pdm-product-data';
import { specificationError } from '@/lib/pdm-specification';

export function SkuUsagePanel({ skuId }: { skuId: string }) {
  const [usage, setUsage] = useState<ProductUsage>('FREIGHT');
  const [check, setCheck] = useState<ProductUsageCheck>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [origin, setOrigin] = useState('CN');
  const [destination, setDestination] = useState('US');
  const [quantity, setQuantity] = useState<number | null>(1);
  const [quote, setQuote] = useState<{ chargeableWeight: number; estimatedFreight: number; currency: string }>();
  const inspect = async () => {
    setBusy(true); setError(undefined); setCheck(undefined);
    try { setCheck(await productDataApi.check(skuId, usage)); } catch (failure) { setError(specificationError(failure)); }
    finally { setBusy(false); }
  };
  const estimate = async () => {
    setBusy(true); setError(undefined); setQuote(undefined);
    try { setQuote(await post('/tms/api/v1/shipping-rates/estimate', { originCountry: origin, destinationCountry: destination, items: [{ skuId, quantity }] })); }
    catch (failure) { setError(specificationError(failure)); }
    finally { setBusy(false); }
  };
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Alert type="info" showIcon message="检查和试算使用已保存的商品资料，请先保存修改。重量、图片和申报资料不影响基础启用；不同使用阶段分别检查缺项。" />
    {error && <Alert type="error" showIcon message={error} />}
    <Space wrap><Select aria-label="商品资料使用阶段" value={usage} disabled={busy} style={{ width: 180 }} onChange={value => { setUsage(value); setCheck(undefined); }}
      options={[{ value: 'FREIGHT', label: '运费试算' }, { value: 'PACKAGING', label: '包装尺寸' }, { value: 'DECLARATION', label: '申报资料' }]} />
      <Button loading={busy} onClick={() => void inspect()}>检查已保存资料</Button></Space>
    {check && <Alert type={check.valid ? 'success' : 'warning'} showIcon message={check.message} />}
    <Typography.Title level={5} style={{ margin: 0 }}>按商品重量试算运费</Typography.Title>
    <Typography.Text type="secondary">使用已配置的物流渠道费率，优先采用单件含包装重量；不会生成运单或库存。</Typography.Text>
    <Space wrap>
      <Input aria-label="运费起运国" value={origin} maxLength={2} style={{ width: 100 }} placeholder="起运国" onChange={event => setOrigin(event.target.value.toUpperCase())} />
      <Input aria-label="运费目的国" value={destination} maxLength={2} style={{ width: 100 }} placeholder="目的国" onChange={event => setDestination(event.target.value.toUpperCase())} />
      <InputNumber aria-label="试算商品数量" value={quantity} min={1} max={2147483647} onChange={setQuantity} />
      <Button disabled={busy || !quantity || !Number.isInteger(quantity) || !origin || !destination} onClick={() => void estimate()}>试算运费</Button>
    </Space>
    {quote && <Descriptions bordered column={2} size="small">
      <Descriptions.Item label="计费重量（kg）">{quote.chargeableWeight}</Descriptions.Item>
      <Descriptions.Item label="预估运费">{quote.estimatedFreight} {quote.currency}</Descriptions.Item>
    </Descriptions>}
  </Space>;
}
