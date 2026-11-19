'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Table, Card, Typography, Tag, Space, Input, Select, DatePicker, Button, Badge, Descriptions, Modal, message, Tabs, Dropdown, Popconfirm, Form, InputNumber } from 'antd';
import { SearchOutlined, EyeOutlined, SyncOutlined, ImportOutlined, DollarOutlined, CloseCircleOutlined, RollbackOutlined } from '@ant-design/icons';
import { useApi } from '@/lib/hooks';
import { get } from '@/lib/api/client';
import { omsApi } from '@/lib/api';
import type { Order, PageParams } from '@/types';
import { WarehouseDispatch } from './warehouse-dispatch';
import { OrderWarehouseShipments } from './warehouse-shipments';

interface OrderResponse extends Omit<Order, 'items' | 'orderNo' | 'customerName'> {
  platformOrderNo: string; buyerName: string;
  lines: { lineId: string; sellerSku: string; title: string; quantity: number; unitPrice: number }[];
}
const displayOrder = (order: OrderResponse): Order => ({ ...order, orderNo: order.platformOrderNo,
  customerName: order.buyerName, items: order.lines.map((line) => ({ itemId: line.lineId,
    sellerSku: line.sellerSku, productName: line.title, quantity: line.quantity, unitPrice: line.unitPrice, currency: order.currency })) });

const { Title } = Typography;
const { RangePicker } = DatePicker;

const statusMap: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'orange', text: '待审核' },
  CREATED: { color: 'orange', text: '待付款' },
  REVIEW_REQUIRED: { color: 'orange', text: '待风控审核' },
  REVIEW_REJECTED: { color: 'red', text: '审核拒绝' },
  CONFIRMED: { color: 'blue', text: '已确认' },
  PAID: { color: 'geekblue', text: '已付款' },
  SHIPPED: { color: 'cyan', text: '已发货' },
  DELIVERED: { color: 'green', text: '已签收' },
  CANCELLED: { color: 'red', text: '已取消' },
  REFUNDED: { color: 'volcano', text: '已退款' },
};

export default function OrdersPage() {
  return <Suspense fallback={<Typography.Text>正在加载订单…</Typography.Text>}><OrdersContent /></Suspense>;
}

function OrdersContent() {
  const search = useSearchParams();
  const sourceOrderId = search.get('orderId');
  const sourceTab = search.get('tab');
  const [params, setParams] = useState<PageParams & Record<string, unknown>>({ page: 1, size: 20 });
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailTab, setDetailTab] = useState('basic');
  const [currentOrder, setCurrentOrder] = useState<Order | null>(null);
  const [syncOpen, setSyncOpen] = useState(false);
  const [refundOpen, setRefundOpen] = useState(false);
  const [refundOrderId, setRefundOrderId] = useState<string>('');
  const [syncForm] = Form.useForm();
  const [refundForm] = Form.useForm();
  const { data: orders, mutate, isLoading, error } = useApi<OrderResponse[]>('/oms/api/in/v1/orders');
  useEffect(() => {
    setParams((p) => ({ ...p, page: 1 }));
    if (sourceOrderId && sourceTab === 'warehouse') void showDetail(sourceOrderId, 'warehouse');
    else setDetailOpen(false);
  }, [sourceOrderId, sourceTab]);
  const filtered = (orders ?? []).map(displayOrder).filter((order) =>
    (!sourceOrderId || order.orderId === sourceOrderId)
    && (!params.keyword || `${order.orderNo} ${order.customerName}`.toLowerCase().includes(String(params.keyword).toLowerCase()))
    && (!params.platform || order.platform.toUpperCase() === params.platform)
    && (!params.status || order.status === params.status));

  const showDetail = async (orderId: string, tab = 'basic') => {
    try {
      const order = await get<OrderResponse>(`/oms/api/in/v1/orders/${encodeURIComponent(orderId)}`);
      setCurrentOrder(displayOrder(order));
      setDetailTab(tab);
      setDetailOpen(true);
    } catch { message.error('获取订单详情失败'); }
  };

  const handleMarkPaid = async (orderId: string) => {
    try {
      await omsApi.markPaid(orderId);
      message.success('已标记为已付款');
      mutate();
    } catch { message.error('操作失败'); }
  };

  const handleCancel = async (orderId: string) => {
    try {
      await omsApi.cancelOrder(orderId);
      message.success('订单已取消');
      mutate();
    } catch { message.error('取消失败'); }
  };

  const handleSync = async () => {
    try {
      const values = await syncForm.validateFields();
      await omsApi.syncOrders(values);
      message.success('同步任务已发起');
      setSyncOpen(false);
      mutate();
    } catch { message.error('同步失败'); }
  };

  const handleRefund = async () => {
    try {
      const values = await refundForm.validateFields();
      await omsApi.requestRefund(refundOrderId, values);
      message.success('退款申请已提交');
      setRefundOpen(false);
      mutate();
    } catch { message.error('退款申请失败'); }
  };

  const columns = [
    { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', render: (v: string, r: Order) => (
      <a onClick={() => showDetail(r.orderId)}>{v}</a>
    )},
    { title: '平台', dataIndex: 'platform', key: 'platform', render: (v: string) => <Tag color="blue">{v}</Tag> },
    { title: '客户', dataIndex: 'customerName', key: 'customerName' },
    { title: '金额', dataIndex: 'totalAmount', key: 'totalAmount', render: (v: number, r: Order) => `${r.currency} ${v?.toFixed(2)}` },
    { title: '状态', dataIndex: 'status', key: 'status', render: (v: string) => {
      const s = statusMap[v] || { color: 'default', text: v };
      return <Badge color={s.color} text={s.text} />;
    }},
    { title: '下单时间', dataIndex: 'orderDate', key: 'orderDate', render: (v: string) => v ? new Date(v).toLocaleString() : '-' },
    { title: '操作', key: 'action', width: 200, render: (_: unknown, record: Order) => (
      <Space size="small">
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => showDetail(record.orderId)}>详情</Button>
        {record.status === 'CREATED' && (
          <Button type="link" size="small" icon={<DollarOutlined />} onClick={() => handleMarkPaid(record.orderId)}>确认付款</Button>
        )}
        {record.status === 'PENDING' && (
          <Popconfirm title="确认取消此订单？" onConfirm={() => handleCancel(record.orderId)}>
            <Button type="link" size="small" danger icon={<CloseCircleOutlined />}>取消</Button>
          </Popconfirm>
        )}
        {['PAID', 'CONFIRMED', 'SHIPPED'].includes(record.status) && (
          <Button type="link" size="small" icon={<RollbackOutlined />} onClick={() => { setRefundOrderId(record.orderId); refundForm.resetFields(); setRefundOpen(true); }}>退款</Button>
        )}
        <Button type="link" size="small" onClick={() => {
          window.location.href = `/pdm/products?keyword=${record.items?.[0]?.sellerSku}`;
        }}>查看商品</Button>
        <WarehouseDispatch orderId={record.orderId} paid={record.status === 'PAID'} />
      </Space>
    )},
  ];

  return (
    <div>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
          <Title level={4} style={{ margin: 0 }}>订单列表</Title>
          <Space>
            <Input placeholder="搜索订单号/客户" prefix={<SearchOutlined />} allowClear style={{ width: 220 }}
              onChange={(e) => setParams({ ...params, keyword: e.target.value || undefined })} />
            <Select placeholder="平台" allowClear style={{ width: 130 }}
              options={[{ value: 'AMAZON', label: 'Amazon' }, { value: 'SHOPIFY', label: 'Shopify' }, { value: 'EBAY', label: 'eBay' }, { value: 'SHOPEE', label: 'Shopee' }]}
              onChange={(v) => setParams({ ...params, platform: v })} />
            <Select placeholder="状态" allowClear style={{ width: 130 }}
              options={Object.entries(statusMap).map(([k, v]) => ({ value: k, label: v.text }))}
              onChange={(v) => setParams({ ...params, status: v })} />
            <Button icon={<SyncOutlined />} onClick={() => { syncForm.resetFields(); setSyncOpen(true); }}>同步订单</Button>
          </Space>
        </div>
        <Table
          rowKey="orderId"
          columns={columns}
          loading={isLoading}
          locale={error ? { emptyText: '订单加载失败，请刷新页面重试' } : undefined}
          dataSource={filtered.slice(((params.page ?? 1) - 1) * (params.size ?? 20), (params.page ?? 1) * (params.size ?? 20))}
          scroll={{ x: 1200 }}
          pagination={{
            current: params.page,
            pageSize: params.size,
            total: filtered.length,
            onChange: (page, size) => setParams({ ...params, page, size }),
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
          }}
        />
      </Card>

      <Modal title="订单详情" open={detailOpen} onCancel={() => setDetailOpen(false)} footer={null} width={800}>
        {currentOrder && (
          <Tabs activeKey={detailTab} onChange={setDetailTab} items={[
            { key: 'warehouse', label: '仓储履约', children: <OrderWarehouseShipments orderId={currentOrder.orderId}
              onChange={() => { void mutate(); void showDetail(currentOrder.orderId, 'warehouse'); }} /> },
            {
              key: 'basic',
              label: '基本信息',
              children: (
                <Descriptions bordered column={2} size="small">
                  <Descriptions.Item label="订单号">{currentOrder.orderNo}</Descriptions.Item>
                  <Descriptions.Item label="平台"><Tag>{currentOrder.platform}</Tag></Descriptions.Item>
                  <Descriptions.Item label="店铺">{currentOrder.storeId}</Descriptions.Item>
                  <Descriptions.Item label="客户">{currentOrder.customerName}</Descriptions.Item>
                  <Descriptions.Item label="金额">{currentOrder.currency} {currentOrder.totalAmount?.toFixed(2)}</Descriptions.Item>
                  <Descriptions.Item label="状态"><Badge color={statusMap[currentOrder.status]?.color} text={statusMap[currentOrder.status]?.text || currentOrder.status} /></Descriptions.Item>
                  <Descriptions.Item label="下单时间" span={2}>{currentOrder.orderDate ? new Date(currentOrder.orderDate).toLocaleString() : '-'}</Descriptions.Item>
                </Descriptions>
              ),
            },
            {
              key: 'items',
              label: '商品明细',
              children: (
                <Table
                  rowKey="itemId"
                  dataSource={currentOrder.items || []}
                  size="small"
                  pagination={false}
                  columns={[
                    { title: '商品ID', dataIndex: 'productId', key: 'productId', render: (v: string) => (
                      <a href={`/pdm/products?productId=${v}`}>{v}</a>
                    )},
                    { title: 'SKU', dataIndex: 'sellerSku', key: 'sku' },
                    { title: '商品名', dataIndex: 'productName', key: 'productName' },
                    { title: '数量', dataIndex: 'quantity', key: 'quantity' },
                    { title: '单价', dataIndex: 'unitPrice', key: 'unitPrice', render: (v: number) => `$${v?.toFixed(2)}` },
                    { title: '小计', key: 'subtotal', render: (_: unknown, r: { quantity: number; unitPrice: number }) => `$${((r.quantity || 0) * (r.unitPrice || 0)).toFixed(2)}` },
                  ]}
                />
              ),
            },
          ]} />
        )}
      </Modal>

      <Modal title="同步订单" open={syncOpen} onOk={handleSync} onCancel={() => setSyncOpen(false)} width={480}>
        <Form form={syncForm} layout="vertical">
          <Form.Item name="platform" label="平台" rules={[{ required: true }]}>
            <Select options={[{ value: 'AMAZON', label: 'Amazon' }, { value: 'SHOPIFY', label: 'Shopify' }, { value: 'EBAY', label: 'eBay' }]} />
          </Form.Item>
          <Form.Item name="storeId" label="店铺ID" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="syncType" label="同步类型" rules={[{ required: true }]}>
            <Select options={[{ value: 'INCREMENTAL', label: '增量同步' }, { value: 'FULL', label: '全量同步' }]} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="申请退款" open={refundOpen} onOk={handleRefund} onCancel={() => setRefundOpen(false)} width={480}>
        <Form form={refundForm} layout="vertical">
          <Form.Item name="refundType" label="退款类型" rules={[{ required: true }]}>
            <Select options={[{ value: 'FULL', label: '全额退款' }, { value: 'PARTIAL', label: '部分退款' }]} />
          </Form.Item>
          <Form.Item name="amount" label="退款金额" rules={[{ required: true }]}>
            <InputNumber min={0} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="currency" label="币种" initialValue="USD">
            <Select options={[{ value: 'USD', label: 'USD' }, { value: 'EUR', label: 'EUR' }, { value: 'GBP', label: 'GBP' }]} />
          </Form.Item>
          <Form.Item name="reason" label="退款原因" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
