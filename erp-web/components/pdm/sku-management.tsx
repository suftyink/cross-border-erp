'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { mutate } from 'swr';
import { Alert, Button, Card, Descriptions, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { EditOutlined, EyeOutlined, PlusOutlined } from '@ant-design/icons';
import type { TableColumnsType } from 'antd';
import { useApi } from '@/lib/hooks';
import { skuPath, skuStatusLabels, spuPath, type SkuDraft, type SpuOption } from '@/lib/pdm-sku';
import { SkuDraftForm } from './sku-draft-form';

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

  const onSaved = async () => {
    await mutate((key: unknown) => typeof key === 'string' && (key.startsWith(skuPath) || key.startsWith(spuPath)));
  };
  const rows = Array.isArray(skus.data) ? skus.data : [];
  const invalidResponse = skus.data !== undefined && !Array.isArray(skus.data);
  const categoryName = (id: string | null) => categories.data?.find((item) => item.categoryId === id)?.name ?? '待补齐';
  const brandName = (id: string | null) => brands.data?.find((item) => item.brandId === id)?.name ?? '未设置';
  const columns: TableColumnsType<SkuDraft> = [
    { title: '本地 SKU 编码', dataIndex: 'sellerSku', key: 'sellerSku', width: 210,
      render: (value: string) => <Typography.Text copyable>{value}</Typography.Text> },
    { title: 'SKU 名称', dataIndex: 'title', key: 'title', render: (value: string | null) => value || <Typography.Text type="secondary">待补名称</Typography.Text> },
    ...(!compact ? [{ title: '所属 SPU', key: 'spu', render: (_: unknown, row: SkuDraft) =>
      <Link href={`/pdm/products?productId=${encodeURIComponent(row.spuId)}`}>{row.spuCode ?? row.spuTitle ?? '查看 SPU'}</Link> }] : []),
    { title: '基本单位', dataIndex: 'baseUnit', key: 'baseUnit', width: 90, render: (value: string | null) => value || '待补齐' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100, render: (value: string) =>
      <Tag color={value === 'ACTIVE' ? 'green' : value === 'DRAFT' ? 'orange' : undefined}>{skuStatusLabels[value] ?? value}</Tag> },
    { title: '资料', key: 'completeness', width: 120, render: (_: unknown, row: SkuDraft) =>
      row.missingFields.length ? <Typography.Text type="warning">待补 {row.missingFields.length} 项</Typography.Text> : '基础资料齐全' },
    { title: '操作', key: 'actions', width: 180, render: (_: unknown, row: SkuDraft) => <Space size={0}>
      <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetailId(row.skuId)}>查看</Button>
      {canMaintain && row.editable && <Button type="link" size="small" icon={<EditOutlined />}
        onClick={() => { setEditing(row); setFormOpen(true); }}>编辑</Button>}
      <Button type="link" size="small" href={`/wms/inventory?sellerSku=${encodeURIComponent(row.sellerSku)}`}>库存</Button>
    </Space> },
  ];

  const content = <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Space wrap style={{ width: '100%', justifyContent: 'space-between' }}>
      <Typography.Title level={compact ? 5 : 4} style={{ margin: 0 }}>{compact ? 'SKU 列表' : 'SKU 管理'}</Typography.Title>
      {canMaintain && <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>新建 SKU</Button>}
    </Space>
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
    <Table<SkuDraft> rowKey="skuId" columns={columns} dataSource={rows} loading={skus.isLoading} size={compact ? 'small' : 'middle'}
      locale={{ emptyText: skus.error || invalidResponse ? '加载失败，请点击重试' : '暂无 SKU' }}
      scroll={{ x: compact ? 750 : 1100 }} pagination={{ current: page, pageSize, showSizeChanger: true,
        onChange: (next, size) => { setPage(next); setPageSize(size); }, showTotal: (total) => `共 ${total} 个 SKU` }} />
    <SkuDraftForm open={formOpen} sku={editing} spuId={compact ? spuId : parent} onCancel={() => setFormOpen(false)} onSaved={onSaved} />
    <Modal title="SKU 详情" open={!!detailId} onCancel={() => setDetailId(null)} footer={null} width={680}>
      {detail.isLoading && <Typography.Text>正在加载 SKU…</Typography.Text>}
      {detail.error && <Alert type="error" showIcon message="SKU 详情加载失败" />}
      {detail.data && <Space direction="vertical" style={{ width: '100%' }}>
        {!!detail.data.missingFields.length && <Alert type="warning" showIcon message={`基础资料待补齐：${detail.data.missingFields.join('、')}`} />}
        <Descriptions bordered column={1} size="small">
          <Descriptions.Item label="本地 SKU 编码"><Typography.Text copyable>{detail.data.sellerSku}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="内部 SKU ID"><Typography.Text copyable>{detail.data.skuId}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label="SKU 名称">{detail.data.title || '待补齐'}</Descriptions.Item>
          <Descriptions.Item label="所属 SPU">{detail.data.spuCode ?? '未编码'} · {detail.data.spuTitle ?? '待补名称'}</Descriptions.Item>
          <Descriptions.Item label="继承分类">{categoryName(detail.data.categoryId)}</Descriptions.Item>
          <Descriptions.Item label="继承品牌">{brandName(detail.data.brandId)}</Descriptions.Item>
          <Descriptions.Item label="基本库存单位">{detail.data.baseUnit || '待补齐'}</Descriptions.Item>
          <Descriptions.Item label="重量（kg）">{detail.data.weightKg ?? '未填写'}</Descriptions.Item>
          <Descriptions.Item label="申报价值">{detail.data.declaredValue == null ? '未填写' : `${detail.data.declaredValue} ${detail.data.currency ?? ''}`}</Descriptions.Item>
          <Descriptions.Item label="状态">{skuStatusLabels[detail.data.status] ?? detail.data.status}</Descriptions.Item>
        </Descriptions>
      </Space>}
    </Modal>
  </Space>;

  return compact ? content : <Card>{content}</Card>;
}
