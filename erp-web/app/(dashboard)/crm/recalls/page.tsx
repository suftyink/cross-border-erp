'use client';

import { Suspense, useEffect, useState } from 'react';
import { Alert, Button, Card, Descriptions, Input, Space, Table, Tag, Typography } from 'antd';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useApi } from '@/lib/hooks';
import { useReturnOperation } from '@/components/use-return-operation';
import { salesReturnLabels } from '@/components/sales-return-types';
import type { SalesReturnOrder, SalesReturnDetail, SalesReturnReceipt } from '@/components/sales-return-types';
import type { ShipmentTrace } from '@/types/warehouse-shipment';
import { newOperationId } from '../../wms/operations/operation-id';
import { RecallDialog } from './recall-dialog';
import { recallEndpoint as endpoint, returnEndpoint, recallStatus, channels } from './recall-types';
import type { Recall, RecallAction, RecallDetail, RecallLine, Notification } from './recall-types';

export default function RecallsPage() { return <Suspense fallback={<Typography.Text>正在加载批次召回…</Typography.Text>}><RecallWork /></Suspense>; }
function RecallWork() {
  const search = useSearchParams();
  const [batch, setBatch] = useState(search.get('batchNo') ?? '');
  const [query, setQuery] = useState(search.get('batchNo') ?? '');
  const [selectedId, setSelectedId] = useState(search.get('recallId') ?? undefined);
  const [selectedReturn, setSelectedReturn] = useState(search.get('returnId') ?? undefined);
  useEffect(() => { setSelectedId(search.get('recallId') ?? undefined); setSelectedReturn(search.get('returnId') ?? undefined); setBatch(search.get('batchNo') ?? ''); setQuery(search.get('batchNo') ?? ''); }, [search]);
  const work = useApi<{ canRecall: boolean; canReturn: boolean; canClose: boolean; recalls: Recall[] }>(`${endpoint}/work`);
  const validBatch = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(query);
  const affected = useApi<ShipmentTrace[]>(work.data?.canRecall && validBatch ? `${endpoint}/affected?batchNo=${query}` : null);
  const detail = useApi<RecallDetail>(selectedId ? `${endpoint}/${selectedId}` : null);
  const returns = useApi<SalesReturnOrder[]>('/crm/api/in/v1/sales-returns');
  const returnDetail = useApi<SalesReturnDetail>(selectedReturn ? `${returnEndpoint}/${selectedReturn}` : null);
  const [action, setAction] = useState<RecallAction>();
  const operation = useReturnOperation<RecallDetail | SalesReturnDetail>('after-sales', async (data) => {
    setAction(undefined);
    if ('order' in data) { setSelectedId(data.order.recallId); setSelectedReturn(data.order.returnId); }
    else setSelectedId(data.recall.recallId);
    await work.mutate(); await detail.mutate(); await returns.mutate(); await returnDetail.mutate();
  });
  const canRetry = operation.pending?.permission === 'close' ? work.data?.canClose : operation.pending?.permission === 'recall' ? work.data?.canRecall : work.data?.canReturn;
  const start = (next: RecallAction) => { operation.setError(undefined); setAction(next); };
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Typography.Title level={4}>批次召回与销售退货</Typography.Title>
    <Alert type="info" showIcon message="按实际发货的批次追踪订单。仓库实收进入待检，售后核实后计入召回进度；通知渠道及结果按实际完成情况登记。" />
    {work.error && <Alert type="error" message="召回待办加载失败" action={<Button onClick={() => void work.mutate()}>重试加载</Button>} />}
    {operation.error && <Alert type="error" showIcon message={operation.error} />}
    {operation.pending && <Alert type="warning" showIcon message={`${operation.pending.label}结果待确认`} action={<Button loading={operation.saving} disabled={!canRetry}
      onClick={() => void operation.send(operation.pending!)}>重试原操作</Button>} />}
    {work.data?.canRecall && <Card title="问题批次发货范围">
      <Space wrap style={{ marginBottom: 16 }}>
        <Typography.Text>问题批次码</Typography.Text>
        <Input aria-label="问题批次码" placeholder="扫描或输入内部收货批次码" value={batch} onChange={(e) => setBatch(e.target.value)} style={{ width: 350 }} />
        <Button onClick={() => setQuery(batch.trim())}>查询批次发货</Button>
        <Button type="primary" disabled={operation.disabled || !affected.data?.length} onClick={() => start({ kind: 'create', batchNo: query })}>创建批次召回单</Button>
      </Space>
      {query && !validBatch && <Alert type="error" message="请输入完整的收货批次码" />}
      {affected.error && <Alert type="error" message="批次发货范围加载失败" />}
      {validBatch ? <SourceTable sources={affected.data ?? []} loading={affected.isLoading} /> : !query && <Typography.Text type="secondary">输入批次码可查询实际发货记录。</Typography.Text>}
    </Card>}
    <Card title="召回单" extra={<Button onClick={() => { void work.mutate(); void detail.mutate(); void returns.mutate(); void returnDetail.mutate(); }}>刷新召回进度</Button>}>
      <Table<Recall> rowKey="recallId" dataSource={work.data?.recalls ?? []} loading={work.isLoading} scroll={{ x: 'max-content' }} columns={[
        { title: '召回编号', dataIndex: 'recallId' }, { title: '目标批次', dataIndex: 'batchNo' }, { title: '原因', dataIndex: 'reason' },
        { title: '发起人', dataIndex: 'createdBy' }, { title: '时间', render: (_, row) => new Date(row.createdAt).toLocaleString() },
        { title: '操作', render: (_, row) => <Button onClick={() => { setSelectedId(row.recallId); setSelectedReturn(undefined); }}>查看召回</Button> },
      ]} />
    </Card>
    {detail.error && <Alert type="error" message="召回详情加载失败" />}
    {detail.data && <Card title="按订单跟踪召回">
      <Typography.Paragraph copyable>{detail.data.recall.batchNo}</Typography.Paragraph>
      <Table<RecallLine> rowKey="lineId" dataSource={detail.data.lines} size="small" pagination={false} scroll={{ x: 'max-content' }} columns={[
        { title: '销售订单', render: (_, row) => <Link href={`/oms/orders?orderId=${row.salesOrderId}`}>{row.salesOrderId}</Link> },
        { title: '原出库单', render: (_, row) => <Link href={`/wms/operations?outboundId=${row.outboundId}`}>{row.outboundId}</Link> },
        { title: 'SKU', dataIndex: 'sellerSku' }, { title: '应召回', dataIndex: 'expectedQuantity' }, { title: '仓库实收', dataIndex: 'receivedQuantity' },
        { title: '批次不明实收', dataIndex: 'unidentifiedQuantity' }, { title: '已确认召回', dataIndex: 'confirmedQuantity' }, { title: '待确认召回', dataIndex: 'pendingQuantity' },
        { title: '状态', render: (_, row) => <Tag>{recallStatus[row.status]}</Tag> },
        { title: '操作', render: (_, row) => <Space>
          {work.data?.canRecall && <Button disabled={operation.disabled} onClick={() => start({ kind: 'notify', line: row })}>登记通知记录</Button>}
          {work.data?.canReturn && <Button disabled={operation.disabled || row.authorizedQuantity >= row.expectedQuantity || !!row.closedAt} onClick={() => start({ kind: 'return', line: row })}>创建销售退货单</Button>}
          {work.data?.canClose && !row.closedAt && <Button danger disabled={operation.disabled || row.unidentifiedQuantity <= 0}
            onClick={() => start({ kind: 'close', line: row })}>异常结案</Button>}
        </Space> },
      ]} />
      {detail.data.lines.some((line) => line.closedAt) && <>
        <Typography.Title level={5}>异常结案记录（结案时快照）</Typography.Title>
        <Table<RecallLine> rowKey="lineId" size="small" pagination={false} dataSource={detail.data.lines.filter((line) => line.closedAt)} scroll={{ x: 'max-content' }} columns={[
          { title: '原订单', dataIndex: 'salesOrderId' }, { title: '应召回', dataIndex: 'closedExpectedQuantity' },
          { title: '结案时实收', dataIndex: 'closedReceivedQuantity' }, { title: '结案时确认召回', dataIndex: 'closedConfirmedQuantity' },
          { title: '结案时批次不明', dataIndex: 'closedUnidentifiedQuantity' }, { title: '原因', dataIndex: 'closeReason' },
          { title: '结案人', dataIndex: 'closedBy' }, { title: '结案时间', render: (_, row) => row.closedAt && new Date(row.closedAt).toLocaleString() },
        ]} />
      </>}
      <Typography.Title level={5}>原始发货范围</Typography.Title><SourceTable sources={detail.data.sources} />
      <Typography.Title level={5}>通知记录</Typography.Title>
      <Table<Notification> rowKey="notificationId" size="small" dataSource={detail.data.notifications} pagination={false} columns={[
        { title: '召回明细', dataIndex: 'lineId' }, { title: '渠道', render: (_, row) => channels[row.channel as keyof typeof channels] },
        { title: '通知结果', dataIndex: 'content' }, { title: '登记人', dataIndex: 'operatorId' }, { title: '登记时间', render: (_, row) => new Date(row.notifiedAt).toLocaleString() },
      ]} />
    </Card>}
    {returns.error && <Alert type="error" message="销售退货单加载失败" />}
    <Card title="销售退货单">
      <Table<SalesReturnOrder> rowKey="returnId" loading={returns.isLoading} dataSource={(returns.data ?? []).filter((r) => !selectedId || r.recallId === selectedId)} scroll={{ x: 'max-content' }} columns={[
        { title: '退货单号', dataIndex: 'returnId' }, { title: '目标批次', dataIndex: 'expectedBatchNo' }, { title: '应退', dataIndex: 'quantity' },
        { title: '实收', dataIndex: 'receivedQuantity' }, { title: '批次不明', dataIndex: 'unidentifiedQuantity' }, { title: '已核实', dataIndex: 'confirmedQuantity' }, { title: '状态', render: (_, row) => <Tag>{salesReturnLabels[row.status]}</Tag> },
        { title: '操作', render: (_, row) => <Space><Button onClick={() => setSelectedReturn(row.returnId)}>实收与核实记录</Button>
          <Link href={`/wms/operations?salesReturnId=${row.returnId}`}>仓库退货收货</Link></Space> },
      ]} />
    </Card>
    {returnDetail.error && <Alert type="error" message="退货实收与核实记录加载失败" />}
    {returnDetail.data && <Card title="实收批次核实">
      {returnDetail.data.order.recallClosed && <Alert type="warning" showIcon message="原召回明细已异常结案，后续实收及核实继续记录，结案时快照保留。" />}
      <Descriptions size="small" items={[
        { key: 'id', label: '退货单', children: returnDetail.data.order.returnId },
        { key: 'batch', label: '目标批次', children: <Typography.Text copyable>{returnDetail.data.order.expectedBatchNo}</Typography.Text> },
        { key: 'physical', label: '实收 / 已核实', children: `${returnDetail.data.order.receivedQuantity} / ${returnDetail.data.order.confirmedQuantity}` },
      ]} />
      <Table<SalesReturnReceipt> rowKey="receiptId" size="small" pagination={false} dataSource={returnDetail.data.receipts} scroll={{ x: 'max-content' }} columns={[
        { title: '收货编号', dataIndex: 'receiptId' }, { title: '原实收批次', render: (_, row) => row.batchNo ?? '批次不明' }, { title: '核实归属批次', dataIndex: 'identifiedBatchNo' },
        { title: '隔离库位', dataIndex: 'quarantineLocationCode' }, { title: '批次不明原因', dataIndex: 'unidentifiedReason' }, { title: '数量', dataIndex: 'quantity' },
        { title: '实收人', dataIndex: 'operatorId' }, { title: '实收时间', render: (_, row) => new Date(row.receivedAt).toLocaleString() },
        { title: '核实人', render: (_, row) => row.verifiedBy ?? '待核实' },
        { title: '核实时间', render: (_, row) => row.verifiedAt ? new Date(row.verifiedAt).toLocaleString() : '—' },
        { title: '操作', render: (_, row) => work.data?.canReturn && <Button disabled={operation.disabled || !!row.verifiedAt}
          onClick={() => start({ kind: 'verify', order: returnDetail.data!.order, receipt: row })}>核实退货批次</Button> },
      ]} />
    </Card>}
    {action && <RecallDialog key={action.kind} action={action} saving={operation.saving} retry={!!operation.pending} error={operation.error} onClose={() => setAction(undefined)} onSubmit={(values) => {
      if (operation.pending) { void operation.send(operation.pending); return; }
      if (action.kind === 'create') void operation.send({ path: endpoint, body: { recallId: newOperationId(), batchNo: action.batchNo, ...values }, label: '创建召回', permission: 'recall' });
      else if (action.kind === 'notify') void operation.send({ path: `${endpoint}/${action.line.recallId}/lines/${action.line.lineId}/notifications`, body: { notificationId: newOperationId(), ...values }, label: '登记通知', permission: 'recall' });
      else if (action.kind === 'close') void operation.send({ path: `${endpoint}/${action.line.recallId}/lines/${action.line.lineId}/exception-close`, body: { ...values, expectedReceivedQuantity: action.line.receivedQuantity, expectedConfirmedQuantity: action.line.confirmedQuantity, expectedUnidentifiedQuantity: action.line.unidentifiedQuantity }, label: '异常结案', permission: 'close' });
      else if (action.kind === 'return') void operation.send({ path: `${endpoint}/${action.line.recallId}/lines/${action.line.lineId}/returns`, body: { returnId: newOperationId(), ...values }, label: '创建销售退货', permission: 'return' });
      else void operation.send({ path: `${returnEndpoint}/${action.order.returnId}/receipts/${action.receipt.receiptId}/verify`, body: values, label: '核实退货批次', permission: 'return' });
    }} />}
  </Space>;
}

function SourceTable({ sources, loading }: { sources: ShipmentTrace[]; loading?: boolean }) {
  return <Table<ShipmentTrace> rowKey={(s) => `${s.packageId}/${s.pickId}`} size="small" dataSource={sources} loading={loading} pagination={false} scroll={{ x: 'max-content' }} columns={[
    { title: '销售订单', dataIndex: 'salesOrderId' }, { title: '原出库单', dataIndex: 'outboundId' }, { title: '包裹', dataIndex: 'packageId' },
    { title: '批次', dataIndex: 'batchNo' }, { title: '实际发货数量', dataIndex: 'quantity' },
    { title: '发货时间', render: (_, row) => new Date(row.shippedAt).toLocaleString() },
  ]} />;
}
