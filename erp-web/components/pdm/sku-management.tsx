'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { mutate } from 'swr';
import { Alert, Button, Card, Descriptions, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { isAxiosError } from 'axios';
import { EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { useApi } from '@/lib/hooks';
import { skuDraftApi, skuPath, skuStatusLabels, spuPath, type SkuDraft, type SpuOption, type SkuBatchOutcome, type SkuMigrationIssue } from '@/lib/pdm-sku';
import { SkuDraftForm } from './sku-draft-form';
import { SkuGenerationButton } from './sku-generation';
import { SkuHistoryPanel, SkuMigrationPanel } from './sku-history-panels';
import { specificationText, type SpecificationItem } from '@/lib/pdm-specification';
import { ProductImage } from './product-images';
import { SkuProductDetailsEditor, SkuProductDetailsView } from './sku-product-details';
import { SkuUsagePanel } from './sku-usage-panel';

interface Props { spuId?: string; initialKeyword?: string; compact?: boolean }

export function SkuManagement({ spuId, initialKeyword = '', compact = false }: Props) {
  const [keyword, setKeyword] = useState(initialKeyword);
  const [parent, setParent] = useState(spuId);
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(compact ? 5 : 20);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<SkuDraft | null>(null);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [historyId, setHistoryId] = useState<string | null>(null);
  const [productDataId, setProductDataId] = useState<string | null>(null);
  const [selected, setSelected] = useState<React.Key[]>([]);
  const [busy, setBusy] = useState(false);
  const [outcomes, setOutcomes] = useState<SkuBatchOutcome[] | null>(null);
  const [migrationOpen, setMigrationOpen] = useState(false);
  const migration = useApi<SkuMigrationIssue[]>(compact ? null : `${skuPath}/migration-issues`);
  const access = useApi<{ canMaintain: boolean }>(`${skuPath}/access`);
  const products = useApi<SpuOption[]>(spuPath);
  const categories = useApi<{ categoryId: string; name: string }[]>('/pdm/api/in/v1/categories');
  const brands = useApi<{ brandId: string; name: string }[]>('/pdm/api/in/v1/brands');
  const query = new URLSearchParams();
  if (keyword.trim()) query.set('keyword', keyword.trim());
  if (parent) query.set('spuId', parent);
  if (status) query.set('status', status);
  const skus = useApi<SkuDraft[]>(`${skuPath}?${query.toString()}`);
  const detail = useApi<SkuDraft>(detailId ? `${skuPath}/${encodeURIComponent(detailId)}` : null);
  const canMaintain = access.data?.canMaintain === true;

  useEffect(() => { setParent(spuId); setPage(1); }, [spuId]);
  useEffect(() => { setKeyword(initialKeyword); setPage(1); }, [initialKeyword]);
  useEffect(() => { setSelected([]); }, [keyword, parent, status]);

  const onSaved = async () => {
    await mutate((key: unknown) => typeof key === 'string' && (key.startsWith(skuPath) || key.startsWith(spuPath)));
  };
  const operate = async (action: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    try {
      await action();
      await onSaved().catch(() => message.warning('操作成功，列表刷新失败，请手动刷新'));
      setSelected([]);
    } catch (error) {
      message.error(isAxiosError<{ message?: string }>(error) ? error.response?.data.message ?? '操作失败，请重试' : '操作失败，请重试');
    } finally { setBusy(false); }
  };
  const batch = (action: 'ENABLE' | 'DISABLE') => void operate(async () => {
    setOutcomes(await skuDraftApi.batch(selected.map(String), action));
  });
  const rows = Array.isArray(skus.data) ? skus.data : [];
  const invalidResponse = skus.data !== undefined && !Array.isArray(skus.data);
  const categoryName = (id: string | null) => categories.data?.find((item) => item.categoryId === id)?.name ?? '待补齐';
  const brandName = (id: string | null) => brands.data?.find((item) => item.brandId === id)?.name ?? '未设置';
  const columns: TableColumnsType<SkuDraft> = [
    { title: '图片', key: 'image', width: 80, render: (_: unknown, row: SkuDraft) =>
      row.details?.images?.[0] ? <ProductImage image={row.details.images[0]} size={48} /> : '—' },
    { title: '本地 SKU 编码', dataIndex: 'sellerSku', key: 'sellerSku', width: 210,
      render: (value: string) => <Typography.Text copyable>{value}</Typography.Text> },
    { title: 'SKU 名称', dataIndex: 'title', key: 'title', width: 180, render: (value: string | null) => value || <Typography.Text type="secondary">待补名称</Typography.Text> },
    ...(!compact ? [{ title: '所属 SPU', key: 'spu', render: (_: unknown, row: SkuDraft) =>
      <Link href={`/pdm/products?productId=${encodeURIComponent(row.spuId)}`}>{row.spuCode ?? row.spuTitle ?? '查看 SPU'}</Link> }] : []),
    { title: '基本单位', dataIndex: 'baseUnit', key: 'baseUnit', width: 90, render: (value: string | null) => value || '待补齐' },
    { title: '规格组合', dataIndex: 'specifications', width: 190, render: (items: SpecificationItem[]) => specificationText(items) },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100, render: (value: string) =>
      <Tag color={value === 'ACTIVE' ? 'green' : value === 'DRAFT' ? 'orange' : undefined}>{skuStatusLabels[value] ?? value}</Tag> },
    { title: '资料', key: 'completeness', width: 120, render: (_: unknown, row: SkuDraft) =>
      <Space direction="vertical" size={0}>
        {row.missingFields.length ? <Typography.Text type="warning">待补 {row.missingFields.length} 项</Typography.Text> : '基础资料齐全'}
        {row.historyReviewRequired ? <Tag color="warning">历史待核实</Tag> : row.identityLocked && <Tag>身份已锁定</Tag>}
      </Space> },
    { title: '操作', key: 'actions', width: 330, render: (_: unknown, row: SkuDraft) => <Space size={0} wrap>
      <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetailId(row.skuId)}>查看</Button>
      {canMaintain && row.editable && <Button type="link" size="small" onClick={() => setProductDataId(row.skuId)}>商品资料</Button>}
      {canMaintain && row.editable && <Button type="link" size="small" icon={<EditOutlined />}
        onClick={() => { setEditing(row); setFormOpen(true); }}>编辑</Button>}
      {canMaintain && ['DRAFT', 'INACTIVE'].includes(row.status) && <Button type="link" size="small" disabled={busy}
        onClick={() => void operate(() => skuDraftApi.enable(row.skuId))}>启用</Button>}
      {canMaintain && row.status === 'ACTIVE' && <Popconfirm title="确认停用此 SKU？" description="停用后不能用于新增业务，历史作业保留。"
        onConfirm={() => operate(() => skuDraftApi.disable(row.skuId))} okText="确认停用" cancelText="取消">
        <Button type="link" size="small" disabled={busy}>停用</Button></Popconfirm>}
      {canMaintain && row.deletable && <Popconfirm title="确认删除未使用的草稿？" description="系统将再次核对引用和启用历史。"
        onConfirm={() => operate(() => skuDraftApi.remove(row.skuId))} okText="确认删除" cancelText="取消">
        <Button type="link" size="small" danger disabled={busy}>删除</Button></Popconfirm>}
      <Button type="link" size="small" onClick={() => setHistoryId(row.skuId)}>记录</Button>
      <Button type="link" size="small" href={`/wms/inventory?sellerSku=${encodeURIComponent(row.sellerSku)}`}>库存</Button>
    </Space> },
  ];

  const content = <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
      <Typography.Title level={compact ? 5 : 4} style={{ margin: 0 }}>{compact ? 'SKU 列表' : 'SKU 管理'}</Typography.Title>
      {canMaintain && <Space wrap>{parent && <SkuGenerationButton spuId={parent} onGenerated={onSaved} />}<Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>新建 SKU</Button></Space>}
    </Space>
    {!compact && !!migration.data?.length && <Alert type="warning" showIcon message={`有 ${migration.data.length} 条历史商品记录待核实`}
      action={<Button size="small" onClick={() => setMigrationOpen(true)}>查看待核实记录</Button>} />}
    {migration.error && <Alert type="warning" showIcon message="历史核实清单加载失败" action={<Button onClick={() => void migration.mutate()}>重试</Button>} />}
    <Space wrap>
      <Input aria-label="搜索 SKU 编码或名称" placeholder="搜索 SKU 编码或名称" value={keyword} allowClear
        style={{ width: 240 }} onChange={(event) => { setKeyword(event.target.value); setPage(1); }} />
      {!compact && <Select aria-label="所属 SPU 筛选" placeholder="所属 SPU" allowClear showSearch optionFilterProp="label"
        style={{ width: 240 }} value={parent} loading={products.isLoading}
        options={products.data?.map((item) => ({ value: item.spuId, label: `${item.sku ?? '未编码'} · ${item.title ?? '待补名称'}` }))}
        onChange={(value) => { setParent(value); setPage(1); }} />}
      <Select aria-label="SKU 状态筛选" placeholder="状态" allowClear style={{ width: 140 }} value={status}
        options={Object.entries(skuStatusLabels).map(([value, label]) => ({ value, label }))}
        onChange={(value) => { setStatus(value); setPage(1); }} />
      <Button onClick={() => { setKeyword(''); setParent(spuId); setStatus(undefined); setPage(1); }}>重置筛选</Button>
    </Space>
    {(access.error || products.error || categories.error || brands.error) && <Alert type="error" showIcon message="权限或商品字典加载失败"
      action={<Button size="small" onClick={() => { void access.mutate(); void products.mutate(); void categories.mutate(); void brands.mutate(); }}>重试</Button>} />}
    {(skus.error || invalidResponse) && <Alert type="error" showIcon message={invalidResponse ? 'SKU 接口返回格式错误' : 'SKU 加载失败，请重试'}
      action={<Button size="small" onClick={() => void skus.mutate()}>重试</Button>} />}
    {canMaintain && <Space>
      <Typography.Text>已选 {selected.length} 项</Typography.Text>
      <Button disabled={!selected.length || busy || selected.length > 200} loading={busy} onClick={() => batch('ENABLE')}>批量启用</Button>
      <Popconfirm title={`确认停用选中的 ${selected.length} 个 SKU？`} onConfirm={() => batch('DISABLE')} okText="确认停用" cancelText="取消">
        <Button disabled={!selected.length || busy || selected.length > 200}>批量停用</Button>
      </Popconfirm>
      {selected.length > 200 && <Typography.Text type="warning">每次最多 200 项</Typography.Text>}
    </Space>}
    <Table<SkuDraft> rowKey="skuId" columns={columns} dataSource={rows} loading={skus.isLoading} size={compact ? 'small' : 'middle'}
      rowSelection={canMaintain ? { selectedRowKeys: selected, onChange: setSelected, getCheckboxProps: () => ({ disabled: busy }) } : undefined}
      locale={{ emptyText: skus.error || invalidResponse ? '加载失败，请点击重试' : '暂无 SKU' }}
      scroll={{ x: compact ? 1300 : 1500 }} pagination={{ current: page, pageSize, showSizeChanger: true,
        onChange: (next, size) => { setPage(next); setPageSize(size); }, showTotal: (total) => `共 ${total} 个 SKU` }} />
    <SkuDraftForm open={formOpen} sku={editing} spuId={compact ? spuId : parent} onCancel={() => setFormOpen(false)} onSaved={onSaved} />
    <SkuHistoryPanel skuId={historyId} onClose={() => setHistoryId(null)} />
    <SkuProductDetailsEditor skuId={productDataId} onClose={() => setProductDataId(null)} onSaved={onSaved} />
    <SkuMigrationPanel open={migrationOpen} issues={migration.data ?? []} onClose={() => setMigrationOpen(false)} />
    <Modal title="批量操作结果" open={outcomes !== null} onCancel={() => setOutcomes(null)} footer={null} width={760}>
      <Table<SkuBatchOutcome> rowKey="skuId" dataSource={outcomes ?? []} size="small" pagination={{ pageSize: 10 }} columns={[
        { title: 'SKU ID', dataIndex: 'skuId' },
        { title: '结果', dataIndex: 'success', render: (ok: boolean) => <Tag color={ok ? 'green' : 'red'}>{ok ? '成功' : '失败'}</Tag> },
        { title: '状态', dataIndex: 'status', render: (value: string) => skuStatusLabels[value] ?? '—' },
        { title: '说明', dataIndex: 'message' },
      ]} />
    </Modal>
    <Modal title="SKU 详情" open={!!detailId} onCancel={() => setDetailId(null)} footer={null} width={1000} styles={{ body: { maxHeight: '75vh', overflowY: 'auto' } }}>
      {detail.isLoading && <Typography.Text>正在加载 SKU…</Typography.Text>}
      {detail.error && <Alert type="error" showIcon message="SKU 详情加载失败" />}
      {detail.data && <Tabs items={[{ key: 'basic', label: '基础信息', children: <Space direction="vertical" style={{ width: '100%' }}>
        {!!detail.data.missingFields.length && <Alert type="warning" showIcon message={`基础资料待补齐：${detail.data.missingFields.join('、')}`} />}
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="本地 SKU 编码"><Typography.Text copyable>{detail.data.sellerSku}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="内部 SKU ID"><Typography.Text copyable>{detail.data.skuId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="SKU 名称">{detail.data.title || '待补齐'}</Descriptions.Item>
          <Descriptions.Item label="所属 SPU">{detail.data.spuCode ?? '未编码'} · {detail.data.spuTitle ?? '待补名称'}</Descriptions.Item>
          <Descriptions.Item label="继承分类">{categoryName(detail.data.categoryId)}</Descriptions.Item>
          <Descriptions.Item label="继承品牌">{brandName(detail.data.brandId)}</Descriptions.Item>
          <Descriptions.Item label="基本库存单位">{detail.data.baseUnit || '待补齐'}</Descriptions.Item>
          <Descriptions.Item label="规格组合">{specificationText(detail.data.specifications)}</Descriptions.Item>
          <Descriptions.Item label="重量（kg）">{detail.data.details?.weightKg ?? '未填写'}</Descriptions.Item>
          <Descriptions.Item label="申报价值">{detail.data.details?.declaredValue == null ? '未填写' : `${detail.data.details.declaredValue} ${detail.data.details.currency ?? ''}`}</Descriptions.Item>
          <Descriptions.Item label="状态">{skuStatusLabels[detail.data.status] ?? detail.data.status}</Descriptions.Item>
        </Descriptions>
      </Space> }, { key: 'details', label: '商品资料', children: <SkuProductDetailsView data={detail.data.details} /> },
      { key: 'usage', label: '使用检查', children: <SkuUsagePanel key={detail.data.skuId} skuId={detail.data.skuId} /> }]} />}
    </Modal>
  </Space>;

  return compact ? content : <Card>{content}</Card>;
}
