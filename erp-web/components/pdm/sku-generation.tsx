'use client';

import { useEffect, useState } from 'react';
import { isAxiosError } from 'axios';
import { Alert, Button, Checkbox, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useApi } from '@/lib/hooks';
import { generationApi, type GenerationCommit, type GenerationPreview, type GenerationRow } from '@/lib/pdm-generation';
import { specificationError, specificationOptionsPath, specificationText, type SpecificationOptions } from '@/lib/pdm-specification';
import { skuDraftApi, skuStatusLabels } from '@/lib/pdm-sku';

type Range = { attributeId: string; valueIds: string[] };

export function SkuGenerationButton({ spuId, onGenerated }: { spuId: string; onGenerated: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [dimensions, setDimensions] = useState<Range[]>([]);
  const [preview, setPreview] = useState<GenerationPreview>();
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [unit, setUnit] = useState('件');
  const [busy, setBusy] = useState(false);
  const [generating, setGenerating] = useState<string>();
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState<GenerationCommit>();
  const options = useApi<SpecificationOptions>(open ? specificationOptionsPath(spuId) : null);
  const locked = busy || !!pending || !!generating;

  useEffect(() => {
    if (open) { setPreview(undefined); setSelected([]); setError(undefined); setPending(undefined); setDimensions([]); setUnit('件'); }
  }, [open, spuId]);
  useEffect(() => {
    if (open && options.data) setDimensions(current => current.length ? current : options.data!.dimensions.map(d => ({ attributeId: d.attributeId, valueIds: d.values.map(v => v.valueId) })));
  }, [open, options.data]);

  const loadPreview = async () => {
    setBusy(true); setError(undefined);
    try { setPreview(await generationApi.preview(spuId, dimensions)); setSelected([]); }
    catch (failure) { setError(specificationError(failure)); }
    finally { setBusy(false); }
  };
  const restart = async () => {
    setBusy(true); setError(undefined);
    try {
      const fresh = await options.mutate();
      if (!fresh) throw new Error('规格范围加载失败');
      setDimensions(fresh.dimensions.map(d => ({ attributeId: d.attributeId, valueIds: d.values.map(v => v.valueId) })));
      setPreview(undefined); setSelected([]); setPending(undefined);
    } catch (failure) { setError(specificationError(failure)); }
    finally { setBusy(false); }
  };
  const edit = (key: string, field: 'sellerSku' | 'title', value: string) => setPreview(current => current && ({
    ...current, rows: current.rows.map(row => row.combinationKey === key ? { ...row, [field]: value } : row),
  }));
  const generateCode = async (row: GenerationRow) => {
    setGenerating(row.combinationKey);
    try { edit(row.combinationKey, 'sellerSku', await skuDraftApi.generateCode()); }
    catch (failure) { setError(specificationError(failure)); }
    finally { setGenerating(undefined); }
  };
  const commit = async () => {
    if (!preview || busy) return;
    const request = pending ?? { previewId: preview.previewId, rows: preview.rows.filter(row => selected.includes(row.combinationKey))
      .map(row => ({ combinationKey: row.combinationKey, sellerSku: row.sellerSku, title: row.title, baseUnit: unit })) };
    setBusy(true); setError(undefined); setPending(request);
    try {
      const result = await generationApi.commit(spuId, request);
      message.success(`已生成 ${result.created.length} 个草稿 SKU`);
      await onGenerated().catch(() => message.warning('已生成，请手动刷新商品列表'));
      setPending(undefined); setOpen(false);
    } catch (failure) {
      if (isAxiosError(failure) && !failure.response) setError('提交结果尚未确认，请保持内容不变，点击「重试确认」。');
      else { setPending(undefined); setError(specificationError(failure)); }
    } finally { setBusy(false); }
  };

  return <>
    <Button onClick={() => setOpen(true)}>批量生成规格 SKU</Button>
    <Modal title="批量生成规格 SKU" open={open} width={1200} destroyOnClose maskClosable={!busy}
      onCancel={() => { if (!busy) setOpen(false); }} footer={<Space>
        <Button disabled={busy} onClick={() => setOpen(false)}>取消</Button>
        {preview && <Button disabled={locked} onClick={() => void restart()}>更新预览</Button>}
        {!preview ? <Button type="primary" loading={busy} disabled={!dimensions.length || dimensions.some(d => !d.valueIds.length) || !!options.error}
          onClick={() => void loadPreview()}>预览组合</Button>
          : <Button type="primary" loading={busy} disabled={!!generating || (!selected.length && !pending)}
            onClick={() => void commit()}>{pending ? '重试确认' : `生成选中 ${selected.length} 个草稿`}</Button>}
      </Space>}>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert type="info" showIcon message="预览不会创建 SKU。已有组合不可重复生成；确认只生成勾选的草稿，整批成功或整批回滚。预览 30 分钟内有效，单次最多 200 个组合。" />
        {error && <Alert type="error" showIcon message={error} />}
        {options.error && <Alert type="error" showIcon message="SPU 规格范围加载失败" action={<Button onClick={() => void options.mutate()}>重试</Button>} />}
        {!preview ? <>
          {!options.isLoading && !options.data?.dimensions.length && <Alert type="warning" showIcon message="请先在 SPU 详情配置规格范围。" />}
          {options.data?.dimensions.map(d => <div key={d.attributeId}>
            <Typography.Text strong>{d.attributeName}</Typography.Text>
            <div style={{ marginTop: 8 }}><Checkbox.Group aria-label={`预览规格值 ${d.attributeName}`} disabled={busy}
              value={dimensions.find(range => range.attributeId === d.attributeId)?.valueIds ?? []}
              options={d.values.map(v => ({ label: v.name, value: v.valueId }))}
              onChange={values => setDimensions(current => current.map(range => range.attributeId === d.attributeId ? { ...range, valueIds: values as string[] } : range))} /></div>
          </div>)}
        </> : <>
          <Space wrap><Typography.Text>共 {preview.rows.length} 个组合，已选 {selected.length} 个新组合</Typography.Text>
            <Typography.Text>新草稿基本单位</Typography.Text>
            <Select aria-label="批量生成基本单位" disabled={locked} value={unit} onChange={setUnit} style={{ width: 100 }}
              options={['件', '个', '套', '台', '支', '包'].map(value => ({ value, label: value }))} />
          </Space>
          <Table<GenerationRow> rowKey="combinationKey" dataSource={preview.rows} size="small" scroll={{ x: 1060 }} pagination={{ pageSize: 20 }}
            rowSelection={{ selectedRowKeys: selected, onChange: setSelected, getCheckboxProps: row => ({ disabled: !!row.existing || locked }) }} columns={[
              { title: '规格组合', width: 250, render: (_, row) => specificationText(row.specifications) },
              { title: '已有商品', width: 160, render: (_, row) => row.existing ? <Space direction="vertical" size={2}>
                <Typography.Text>{row.existing.sellerSku}</Typography.Text><Tag>{skuStatusLabels[row.existing.status] ?? row.existing.status}</Tag>
              </Space> : <Tag color="blue">待生成</Tag> },
              { title: '本地 SKU 编码', width: 330, render: (_, row) => row.existing ? row.existing.sellerSku : <Space.Compact style={{ width: '100%' }}>
                <Input aria-label={`编码 ${specificationText(row.specifications)}`} maxLength={128} disabled={locked} value={row.sellerSku}
                  onChange={event => edit(row.combinationKey, 'sellerSku', event.target.value)} />
                <Button disabled={locked} loading={generating === row.combinationKey} onClick={() => void generateCode(row)}>生成</Button>
              </Space.Compact> },
              { title: 'SKU 名称', width: 300, render: (_, row) => row.existing ? '—' : <Input aria-label={`名称 ${specificationText(row.specifications)}`}
                maxLength={512} disabled={locked} value={row.title} onChange={event => edit(row.combinationKey, 'title', event.target.value)} /> },
            ]} />
        </>}
      </Space>
    </Modal>
  </>;
}
