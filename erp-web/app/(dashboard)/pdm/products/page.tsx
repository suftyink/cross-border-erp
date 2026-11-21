'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Alert, Table, Button, Modal, Form, Input, Select, Tag, Space, message, Card, Typography, Descriptions, Badge } from 'antd';
import { PlusOutlined, EditOutlined, EyeOutlined } from '@ant-design/icons';
import { useApi } from '@/lib/hooks';
import { post, put } from '@/lib/api/client';
import { SkuManagement } from '@/components/pdm/sku-management';
import { SpuSpecifications } from '@/components/pdm/spu-specifications';
import { skuPath, type SpuOption as Product, type SkuRecord } from '@/lib/pdm-sku';
import { mutate as mutateCache } from 'swr';

const { Title } = Typography;
const pdmPath = '/pdm/api/in/v1';
const productPath = `${pdmPath}/products`;

function useProductSkus(spuId: string | null) {
  return useApi<SkuRecord[]>(spuId ? `${productPath}/${encodeURIComponent(spuId)}/skus` : null);
}

function SkuCount({ spuId }: { spuId: string }) {
  const { data } = useProductSkus(spuId);
  return data?.length ?? '—';
}

export default function ProductsPage() {
  return <Suspense fallback={<Typography.Text>正在加载产品…</Typography.Text>}><ProductsContent /></Suspense>;
}

function ProductsContent() {
  const search = useSearchParams();
  const sourceProductId = search.get('productId');
  const sourceKeyword = search.get('keyword') ?? '';
  const [keyword, setKeyword] = useState(sourceKeyword);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [modalOpen, setModalOpen] = useState(false);
  const [detailId, setDetailId] = useState<string | null>(null);
  const [editing, setEditing] = useState<Product | null>(null);
  const [form] = Form.useForm<Pick<Product, 'sku' | 'title' | 'description' | 'categoryId' | 'brandId'>>();
  const products = useApi<Product[]>(productPath);
  const access = useApi<{ canMaintain: boolean }>(`${skuPath}/access`);
  const canMaintain = access.data?.canMaintain === true;
  const categories = useApi<{ categoryId: string; name: string }[]>(`${pdmPath}/categories`);
  const brands = useApi<{ brandId: string; name: string }[]>(`${pdmPath}/brands`);
  const detail = useApi<Product>(detailId ? `${productPath}/${encodeURIComponent(detailId)}` : null);
  const currentProduct = detail.data;

  useEffect(() => { setDetailId(sourceProductId); }, [sourceProductId]);
  useEffect(() => { setKeyword(sourceKeyword); setPage(1); }, [sourceKeyword]);

  const searchTerm = keyword.trim().toLowerCase();
  const filtered = (products.data ?? []).filter((product) =>
    `${product.sku ?? ''} ${product.title ?? ''}`.toLowerCase().includes(searchTerm));
  const categoryNames = new Map(categories.data?.map(({ categoryId, name }) => [categoryId, name]));
  const brandNames = new Map(brands.data?.map(({ brandId, name }) => [brandId, name]));

  const handleCreate = () => { setEditing(null); form.resetFields(); setModalOpen(true); };
  const handleEdit = (record: Product) => { setEditing(record); form.resetFields(); form.setFieldsValue(record); setModalOpen(true); };

  const handleSubmit = async () => {
    try {
      const entered = await form.validateFields();
      const values = { ...entered, brandId: entered.brandId ?? '' };
      if (editing) { await put<Product>(`${productPath}/${encodeURIComponent(editing.spuId)}`, values); message.success('产品更新成功'); }
      else { await post<Product>(productPath, values); message.success('产品创建成功'); }
      setModalOpen(false);
      void products.mutate();
      if (editing?.spuId === detailId) void detail.mutate();
      void mutateCache((key: unknown) => typeof key === 'string' && key.startsWith(skuPath))
        .catch(() => { message.warning('产品已保存，SKU 列表刷新失败，请重试'); });
    } catch { message.error('操作失败'); }
  };

  const productColumns = [
    { title: 'SPU编码', dataIndex: 'sku', key: 'sku' },
    { title: '产品名称', dataIndex: 'title', key: 'title' },
    { title: '分类', dataIndex: 'categoryId', key: 'categoryId', render: (id: string) => categoryNames.get(id) ?? '—' },
    { title: '品牌', dataIndex: 'brandId', key: 'brandId', render: (id: string) => brandNames.get(id) ?? '—' },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => <Badge color={v === 'ACTIVE' ? 'green' : 'default'} text={v} /> },
    { title: 'SKU数', key: 'skuCount', render: (_: unknown, record: Product) => <SkuCount spuId={record.spuId} /> },
    { title: '操作', key: 'action', render: (_: unknown, record: Product) => (
      <Space>
        <Button type="link" icon={<EyeOutlined />} onClick={() => setDetailId(record.spuId)}>详情</Button>
        {canMaintain && <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button>}
      </Space>
    )},
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>产品管理</Title>
        <Space>
          <Input placeholder="搜索 SPU 编码或产品名称" value={keyword} allowClear style={{ width: 220 }}
            onChange={(e) => { setKeyword(e.target.value); setPage(1); }} />
          {canMaintain && <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建产品</Button>}
        </Space>
      </div>
      <Card style={{ borderRadius: 8 }}>
        {(categories.error || brands.error) && <Alert type="error" showIcon message="分类或品牌加载失败" />}
        <Table rowKey="spuId" columns={productColumns} dataSource={filtered} loading={products.isLoading} size="middle"
          locale={products.error ? { emptyText: '产品加载失败，请刷新页面重试' } : undefined}
          pagination={{ current: page, pageSize, onChange: (nextPage, nextSize) => { setPage(nextPage); setPageSize(nextSize); } }} />
      </Card>
      <Modal title={editing ? '编辑产品' : '新建产品'} open={modalOpen} onOk={handleSubmit} onCancel={() => setModalOpen(false)} width={600}>
        <Form form={form} layout="vertical">
          <Form.Item name="sku" label="SPU编码" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item>
          <Form.Item name="title" label="产品名称" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item>
          <Form.Item name="categoryId" label="分类" rules={[{ required: true }]}>
            <Select loading={categories.isLoading} options={categories.data?.map(({ categoryId, name }) => ({ value: categoryId, label: name }))} />
          </Form.Item>
          <Form.Item name="brandId" label="品牌（可选）">
            <Select allowClear loading={brands.isLoading} options={brands.data?.map(({ brandId, name }) => ({ value: brandId, label: name }))} />
          </Form.Item>
          <Form.Item name="description" label="产品描述"><Input.TextArea rows={3} /></Form.Item>
        </Form>
      </Modal>
      <Modal title="产品详情" open={!!detailId} onCancel={() => setDetailId(null)} footer={null} width={1180}>
        {detail.isLoading && <Typography.Text>正在加载产品详情…</Typography.Text>}
        {detail.error && <Alert type="error" showIcon message="产品详情加载失败" />}
        {currentProduct && (
          <div>
            <Descriptions bordered column={2} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="SPU编码">{currentProduct.sku ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="产品名称">{currentProduct.title}</Descriptions.Item>
              <Descriptions.Item label="分类"><Tag>{categoryNames.get(currentProduct.categoryId ?? '') ?? '—'}</Tag></Descriptions.Item>
              <Descriptions.Item label="品牌">{brandNames.get(currentProduct.brandId ?? '') ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="状态" span={2}><Badge color={currentProduct.status === 'ACTIVE' ? 'green' : 'default'} text={currentProduct.status} /></Descriptions.Item>
              <Descriptions.Item label="产品描述" span={2}>{currentProduct.description || '—'}</Descriptions.Item>
            </Descriptions>
            <SpuSpecifications spuId={currentProduct.spuId} canMaintain={canMaintain} />
            <SkuManagement spuId={currentProduct.spuId} compact />
          </div>
        )}
      </Modal>
    </div>
  );
}
