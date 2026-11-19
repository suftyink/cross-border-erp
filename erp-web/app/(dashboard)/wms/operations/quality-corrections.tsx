'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Space, Table, Tag, Typography } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import { newOperationId } from './operation-id';
import { QualityCorrectionDialog } from './quality-correction-dialog';
import { correctionLabels, qualityEndpoint as endpoint } from './quality-correction-types';
import type { Correction, CorrectionAction, CorrectionAttempt, CorrectionDetail, CorrectionWork } from './quality-correction-types';

export function QualityCorrections({ correctionId, batchNo }: { correctionId: string | null; batchNo: string | null }) {
  const tenant = useAuthStore((s) => s.tenantId);
  const key = `wms:pending-quality-correction:${tenant}`;
  const work = useApi<CorrectionWork>(`${endpoint}/work${batchNo ? `?batchNo=${encodeURIComponent(batchNo)}` : ''}`);
  const [selectedId, setSelectedId] = useState(correctionId ?? undefined);
  const detail = useApi<CorrectionDetail>(selectedId ? `${endpoint}/${selectedId}` : null);
  const [action, setAction] = useState<CorrectionAction>();
  const [pending, setPending] = useState<CorrectionAttempt>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const busy = useRef(false);
  useEffect(() => { setSelectedId(correctionId ?? undefined); }, [correctionId]);
  useEffect(() => {
    setAction(undefined); setError(undefined);
    try { const stored = sessionStorage.getItem(key); setPending(stored ? JSON.parse(stored) as CorrectionAttempt : undefined); }
    catch { setError('原操作记录无法读取，请联系管理员核对。'); }
  }, [key]);
  const send = async (attempt: CorrectionAttempt) => {
    if (busy.current) return;
    busy.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(key, JSON.stringify(attempt)); setPending(attempt);
      const result = await post<CorrectionDetail>(attempt.path, attempt.body);
      sessionStorage.removeItem(key); setPending(undefined); setAction(undefined);
      setSelectedId(result.correction.correctionId);
      await work.mutate(); await detail.mutate();
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(key); setPending(undefined);
      }
      setError(response?.data?.message ?? '操作结果未确认，请重试原操作。');
    } finally { busy.current = false; setSaving(false); }
  };
  const c = detail.data?.correction;
  const disabled = saving || !!pending;
  const start = (next: CorrectionAction) => { setError(undefined); setAction(next); };
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon message="先暂停原批次，再按库存阶段退回。未拣预占由主管解除；已拣未发从发货暂存直接退待检。已发货历史保留。" />
    {work.error && <Alert type="error" message="质检更正待办加载失败" action={<Button onClick={() => void work.mutate()}>重试加载</Button>} />}
    {error && <Alert type="error" showIcon message={error} />}
    {pending && <Alert type="warning" showIcon message={`${pending.label}结果待确认`}
      action={<Button loading={saving} disabled={!work.data?.[pending.permission]} onClick={() => void send(pending)}>重试原操作</Button>} />}
    <Card title="已上架货物质检更正" extra={<Space>
      <Button onClick={() => { void work.mutate(); void detail.mutate(); }}>刷新更正</Button>
      {work.data?.canCorrect && <Button type="primary" disabled={disabled} onClick={() => start({ kind: 'pause' })}>发起质检更正</Button>}
    </Space>}>
      <Table<Correction> rowKey="correctionId" loading={work.isLoading} dataSource={work.data?.cases ?? []} scroll={{ x: 1200 }} tableLayout="fixed" columns={[
        { title: '更正编号', dataIndex: 'correctionId', width: 210, ellipsis: true },
        { title: 'SKU', dataIndex: 'sellerSku', width: 110 }, { title: '原批次', dataIndex: 'batchNo', width: 220, ellipsis: true },
        { title: '本次更正', dataIndex: 'quantity', width: 85 }, { title: '原位撤回', dataIndex: 'reversedQuantity', width: 85 },
        { title: '撤拣待检', dataIndex: 'directPendingQuantity', width: 85 },
        { title: '状态', width: 150, render: (_, row) => <Tag>{correctionLabels[row.status]}</Tag> },
        { title: '操作', width: 120, fixed: 'right', render: (_, row) => <Button onClick={() => { setSelectedId(row.correctionId); setError(undefined); }}>更正详情</Button> },
      ]} />
    </Card>
    {detail.error && <Alert type="error" message="更正详情加载失败" action={<Button onClick={() => void detail.mutate()}>重试详情</Button>} />}
    {c && <Card title="实物退回与重新质检">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Descriptions size="small" column={{ xs: 1, md: 2 }} items={[
          { key: 'batch', label: '原批次', children: <Typography.Text copyable>{c.batchNo}</Typography.Text> },
          { key: 'status', label: '状态', children: correctionLabels[c.status] },
          { key: 'quantity', label: '本次重检数量', children: c.quantity }, { key: 'missing', label: '复核确认短少（不再重检）', children: c.excludedMissingQuantity }, { key: 'reason', label: '原因', children: c.reason },
          { key: 'created', label: '发起人', children: c.createdBy }, { key: 'released', label: '解除预占主管', children: c.releasedBy ?? '—' },
          { key: 'withdrawn', label: '撤销判定人', children: c.withdrawnBy ?? '—' }, { key: 'inspected', label: '重新全检人', children: c.inspectedBy ?? '—' },
          { key: 'result', label: '更正后判定', children: c.status === 'COMPLETED' ? `良品 ${c.goodQuantity} / 次品 ${c.defectiveQuantity}` : '待完成' },
        ]} />
        {!!detail.data?.pendingReviewQuantity && c.status !== 'COMPLETED' && <Alert type="warning" showIcon message={`仍有 ${detail.data.pendingReviewQuantity} 件缺拣待复核。原批次已暂停发货；主管确认短少后扣除重检数量，找回实物继续质量冻结。`} />}
        <Space wrap>
          {(detail.data?.reviewIds ?? []).map((reviewId) => <Link key={reviewId} href={`/wms/operations?reviewId=${reviewId}`}>关联缺拣复核</Link>)}
          <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(c.purchaseOrderId)}&batchNo=${encodeURIComponent(c.batchNo)}`}>来源收货</Link>
          <Link href={`/wms/inventory?batchNo=${encodeURIComponent(c.batchNo)}`}>批次库存</Link>
          <Link href={`/wms/inventory?tab=movements&documentId=${c.correctionId}`}>更正流水</Link>
          {c.status === 'COMPLETED' && <Link href={`/wms/operations?tab=putaway&purchaseOrderId=${encodeURIComponent(c.purchaseOrderId)}`}>重新上架</Link>}
          {work.data?.canApprove && c.status === 'PAUSED' && !c.releasedAt && detail.data?.allocations.some((a) => a.plannedQuantity > a.pickedQuantity + a.shortQuantity + a.cancelledQuantity)
            && <Button disabled={disabled} loading={saving} onClick={() => void send({ path: `${endpoint}/${c.correctionId}/release`, body: {}, label: '主管解除预占', permission: 'canApprove' })}>主管确认解除未拣预占</Button>}
          {work.data?.canInspect && c.status === 'PAUSED' && <Button disabled={disabled} onClick={() => void send({ path: `${endpoint}/${c.correctionId}/withdraw`, body: {}, label: '撤销原质检判定', permission: 'canInspect' })}>撤销原质检判定</Button>}
          {work.data?.canInspect && c.status === 'PENDING_INSPECTION' && <Button type="primary" disabled={disabled} onClick={() => start({ kind: 'inspection', correction: c })}>重新全检</Button>}
        </Space>
        <Typography.Title level={5}>原上架作业</Typography.Title>
        <Table rowKey="transactionId" size="small" pagination={false} dataSource={detail.data?.placements} scroll={{ x: 'max-content' }} columns={[
          { title: '原上架流水', dataIndex: 'transactionId' }, { title: '原库位', dataIndex: 'locationCode' },
          { title: '原数量', dataIndex: 'quantity' }, { title: '累计已撤销', dataIndex: 'reversedQuantity' },
          { title: '操作', render: (_, row) => work.data?.canCorrect && c.status === 'PAUSED' && <Button disabled={disabled || row.quantity <= row.reversedQuantity}
            onClick={() => start({ kind: 'reverse', placement: row, correction: c })}>撤销上架</Button> },
        ]} />
        <Typography.Title level={5}>受影响拣货任务</Typography.Title>
        <Table rowKey="allocationId" size="small" pagination={false} dataSource={detail.data?.allocations} scroll={{ x: 'max-content' }} columns={[
          { title: '原分配明细', dataIndex: 'allocationId' }, { title: '原计划', dataIndex: 'plannedQuantity' }, { title: '有效已拣', dataIndex: 'pickedQuantity' },
          { title: '已取消', dataIndex: 'cancelledQuantity' },
          { title: '作业', render: (_, row) => <Link href={`/wms/operations?outboundId=${row.outboundId}`}>查看拣货与重新分配</Link> },
        ]} />
        <Typography.Title level={5}>发货暂存实物</Typography.Title>
        <Table rowKey="requestId" size="small" pagination={false} dataSource={detail.data?.picks ?? []} scroll={{ x: 'max-content' }} columns={[
          { title: '原实拣记录', dataIndex: 'requestId' }, { title: '实拣数量', dataIndex: 'quantity' },
          { title: '已发货', dataIndex: 'shippedQuantity' }, { title: '已撤拣', dataIndex: 'returnedQuantity' },
          { title: '操作', render: (_, row) => work.data?.canCorrect && c.status === 'PAUSED' && <Button disabled={disabled || row.quantity <= row.returnedQuantity + row.shippedQuantity}
            onClick={() => start({ kind: 'unpick', pick: row, correction: c })}>撤拣退回待检</Button> },
        ]} />
        <OriginalInspection snapshot={c.originalInspection} />
      </Space>
    </Card>}
    {action && <QualityCorrectionDialog key={action.kind + ('correction' in action ? action.correction.correctionId : '')}
      action={action} saving={saving} pending={pending} error={error} onClose={() => setAction(undefined)} onSubmit={(values) => {
        if (pending) { void send(pending); return; }
        const id = 'correction' in action ? action.correction.correctionId : undefined;
        const attempt: CorrectionAttempt = action.kind === 'pause'
          ? { path: endpoint, body: { correctionId: newOperationId(), ...values }, label: '发起质检更正', permission: 'canCorrect' }
          : action.kind === 'inspection'
            ? { path: `${endpoint}/${id}/inspection`, body: values, label: '重新全检', permission: 'canInspect' }
            : { path: `${endpoint}/${id}/${action.kind === 'reverse' ? 'reverse-putaway' : 'unpick'}`,
              body: { requestId: newOperationId(), ...values, ...(action.kind === 'reverse' ? { putawayTransactionId: action.placement.transactionId } : { pickId: action.pick.requestId }) },
              label: action.kind === 'reverse' ? '撤销上架' : '撤拣退回待检', permission: 'canCorrect' };
        void send(attempt);
      }} />}
  </Space>;
}

function OriginalInspection({ snapshot }: { snapshot: string }) {
  let original: Record<string, unknown>;
  try { original = JSON.parse(snapshot) as Record<string, unknown>; } catch { return <Alert type="error" message="原质检快照无法读取" />; }
  return <Descriptions title="原质检判定快照（更正前）" bordered size="small" column={{ xs: 1, md: 2 }} items={[
    { key: 'id', label: '原判定编号', children: String(original.inspection_id) },
    { key: 'sample', label: '原抽检数量', children: String(original.sample_checked) },
    { key: 'good', label: '原良品', children: String(original.good_quantity) }, { key: 'bad', label: '原次品', children: String(original.defective_quantity) },
    { key: 'operator', label: '原判定人', children: String(original.disposition_operator_id) },
    { key: 'at', label: '原判定时间', children: original.disposed_at ? new Date(String(original.disposed_at)).toLocaleString() : '—' },
  ]} />;
}
