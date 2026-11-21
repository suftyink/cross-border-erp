'use client';

import { Alert, Button, Select, Space, Typography } from 'antd';
import { useApi } from '@/lib/hooks';
import { specificationOptionsPath, type SpecificationOptions, type SpecificationSelection } from '@/lib/pdm-specification';

export function SkuSpecificationPicker({ spuId, disabled, value = [], onChange }: {
  spuId?: string; disabled: boolean; value?: SpecificationSelection[]; onChange?: (value: SpecificationSelection[]) => void;
}) {
  const options = useApi<SpecificationOptions>(spuId ? specificationOptionsPath(spuId) : null);
  const invalidResponse = options.data !== undefined && !Array.isArray(options.data?.dimensions);
  if (options.error || invalidResponse) return <Alert type="error" showIcon message={invalidResponse ? '规格范围接口返回格式错误' : '规格范围加载失败'} action={<Button onClick={() => void options.mutate()}>重试</Button>} />;
  if (options.isLoading) return <Typography.Text>正在加载规格范围…</Typography.Text>;
  if (!options.data?.dimensions.length) return <Typography.Text type="secondary">普通商品无须填写规格；多规格需先在 SPU 详情配置范围。</Typography.Text>;
  return <Space direction="vertical" style={{ width: '100%' }}>
    {options.data.dimensions.map(d => <div key={d.attributeId}>
      <Typography.Text style={{ display: 'block', marginBottom: 4 }}>{d.attributeName}</Typography.Text>
      <Select aria-label={`SKU 规格 ${d.attributeName}`} allowClear disabled={disabled} style={{ width: '100%' }} placeholder={`选择${d.attributeName}`}
        value={value.find(item => item.attributeId === d.attributeId)?.valueId} options={d.values.map(v => ({ value: v.valueId, label: `${v.name} (${v.code})` }))}
        onChange={(selected?: string) => onChange?.([...value.filter(item => item.attributeId !== d.attributeId), ...(selected ? [{ attributeId: d.attributeId, valueId: selected }] : [])])} />
    </div>)}
    <Typography.Text type="secondary">每个维度选择一个值；相同组合只能对应一个 SKU。</Typography.Text>
  </Space>;
}
