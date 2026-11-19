'use client';

import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Space, Table, Tag } from 'antd';
import { isAxiosError } from 'axios';
import Link from 'next/link';
import { useSWRConfig } from 'swr';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import { newOperationId } from './operation-id';
import { ShortPickDialog } from './short-pick-dialog';
import { shortPickEndpoint as endpoint } from './short-pick-types';
import type { ShortPickAction, ShortPickAttempt, ShortPickCase, ShortPickSource } from './short-pick-types';

interface Work { canReport: boolean; canCount: boolean; canApprove: boolean; cases: ShortPickCase[] }
const statusLabels: Record<string, string> = { PENDING_COUNT: '待仓管复盘', AWAITING_APPROVAL: '待主管确认', CONFIRMED: '已确认复盘' };

export function ShortPickWork({ source, outboundId, reviewId }: {
  source?: ShortPickSource; outboundId?: string | null; reviewId?: string | null;
}) {
  const tenant = useAuthStore((s) => s.tenantId);
  const { mutate } = useSWRConfig();
  const storageKey = `wms:pending-short-pick:${tenant}:${outboundId ?? source?.outboundId ?? 'all'}`;
  const query = outboundId ?? source?.outboundId;
  const work = useApi<Work>(`${endpoint}/work${query ? `?outboundId=${encodeURIComponent(query)}` : ''}`);
  const [action, setAction] = useState<ShortPickAction>();
  const [pending, setPending] = useState<ShortPickAttempt>();
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const busy = useRef(false);
  useEffect(() => {
    try { const value = sessionStorage.getItem(storageKey); setPending(value ? JSON.parse(value) as ShortPickAttempt : undefined); }
    catch { setError('待确认缺拣作业无法读取，请联系管理员核对。'); }
    setAction(undefined);
  }, [storageKey]);
  const submit = async (attempt: ShortPickAttempt) => {
    if (busy.current) return;
    busy.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt)); setPending(attempt);
      await post(attempt.path, attempt.body);
      sessionStorage.removeItem(storageKey); setPending(undefined); setAction(undefined);
      await mutate((key) => typeof key === 'string' && (key.startsWith(`${endpoint}/work`)
        || key.startsWith('/wms/api/in/v1/sales-picking/')));
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(storageKey); setPending(undefined);
      }
      setError(response?.data?.message ?? '作业结果未确认，请重试原缺拣作业。');
    } finally { busy.current = false; setSaving(false); }
  };
  const run = (values: Record<string, unknown>) => {
    if (pending) { void submit(pending); return; }
    if (!action) return;
    let attempt: ShortPickAttempt;
    if (action.kind === 'report') attempt = { path: `${endpoint}/outbounds/${action.source.outboundId}`,
      body: { ...values, shortPickId: newOperationId() }, label: '缺拣登记' };
    else if (action.kind === 'replace') attempt = { path: `${endpoint}/${action.review.shortPickId}/replace`,
      body: { ...values, requestId: newOperationId() }, label: '同库位换批' };
    else if (action.kind === 'count') attempt = { path: `${endpoint}/${action.review.shortPickId}/count`, body: values, label: '缺拣复盘' };
    else attempt = { path: `${endpoint}/${action.review.shortPickId}/confirm`,
      body: { foundQuantity: action.review.foundQuantity, missingQuantity: action.review.missingQuantity }, label: '主管确认' };
    void submit(attempt);
  };
  const show = (next: ShortPickAction) => { setError(undefined); setAction(next); };
  const canView = work.data?.canReport || work.data?.canCount || work.data?.canApprove;
  return <Card title="缺拣换批与差异复核" extra={<Space>
    <Button onClick={() => void work.mutate()}>刷新复核</Button>
    {source && work.data?.canReport && <Button disabled={!!pending || !source.allocations.some((a) => a.plannedQuantity - a.pickedQuantity - a.shortQuantity - a.cancelledQuantity > 0)}
      onClick={() => show({ kind: 'report', source })}>登记缺拣</Button>}
  </Space>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="待复核数量保持账面在手并暂停分配。仓管录入找回与短少，主管确认后才调整库存。" />
      {source && <Tag>{source.allowBatchSubstitution ? '订单允许同库位换批' : '订单未允许换批'}</Tag>}
      {error && <Alert type="error" showIcon message={error} />}
      {work.error && <Alert type="error" showIcon message="缺拣复核加载失败，请刷新复核" />}
      {!work.isLoading && work.data && !canView && <Alert type="info" showIcon message="当前账号没有缺拣或库存复核权限" />}
      {pending && <Alert type="warning" showIcon message={`${pending.label}结果待确认`}
        action={<Button loading={saving} onClick={() => void submit(pending)}>重试原缺拣作业</Button>} />}
      {(work.isLoading || canView) && <Table<ShortPickCase> rowKey="shortPickId" loading={work.isLoading} tableLayout="fixed"
        dataSource={(work.data?.cases ?? []).filter((c) => !reviewId || c.shortPickId === reviewId)} scroll={{ x: 1800 }}
        expandable={{ expandedRowRender: (row) => <Descriptions column={2} size="small" items={[
          { key: 'reason', label: '缺拣原因', children: row.reason }, { key: 'by', label: '登记人', children: row.reportedBy },
          { key: 'time', label: '登记时间', children: new Date(row.reportedAt).toLocaleString() },
          { key: 'count', label: '复盘人', children: row.countedBy ?? '—' },
          { key: 'remark', label: '复盘说明', children: row.countRemark ?? '—' },
          { key: 'approve', label: '主管确认人', children: row.confirmedBy ?? '—' },
          { key: 'at', label: '主管确认时间', children: row.confirmedAt ? new Date(row.confirmedAt).toLocaleString() : '—' },
        ]} /> }} columns={[
          { title: '缺拣编号', dataIndex: 'shortPickId', width: 190, ellipsis: true },
          { title: 'SKU', dataIndex: 'sellerSku', width: 110 },
          { title: '原库位', dataIndex: 'locationCode', width: 90 },
          { title: '原批次', dataIndex: 'batchNo', width: 210, ellipsis: true },
          { title: '缺拣', dataIndex: 'shortQuantity', width: 75 }, { title: '待复核', dataIndex: 'pendingReview', width: 85 },
          { title: '换批', width: 210, ellipsis: true, render: (_, row) => row.replacedAt ? row.replacementBatchNo : row.allowBatchSubstitution ? '待换批' : '订单不允许' },
          { title: '找回 / 短少', width: 110, render: (_, row) => row.countedAt ? `${row.foundQuantity} / ${row.missingQuantity}` : '未复盘' },
          { title: '复核状态', width: 140, render: (_, row) => <Tag>{statusLabels[row.status] ?? row.status}</Tag> },
          { title: '操作与来源', width: 300, fixed: 'right', render: (_, row) => <Space wrap>
            {work.data?.canReport && !row.replacedAt && <Button disabled={!!pending || !row.allowBatchSubstitution}
              onClick={() => show({ kind: 'replace', review: row })}>确认同库位换批</Button>}
            {work.data?.canCount && !row.confirmedAt && <Button disabled={!!pending} onClick={() => show({ kind: 'count', review: row })}>录入复盘</Button>}
            {work.data?.canApprove && !row.confirmedAt && <Button disabled={!!pending || !row.countedAt} onClick={() => show({ kind: 'confirm', review: row })}>主管确认</Button>}
            <Link href={`/wms/operations?outboundId=${encodeURIComponent(row.outboundId)}`}>拣货作业</Link>
            <Link href={`/wms/operations?purchaseOrderId=${encodeURIComponent(row.purchaseOrderId)}&batchNo=${encodeURIComponent(row.batchNo)}`}>来源收货</Link>
            <Link href={`/wms/inventory?warehouseId=${encodeURIComponent(row.warehouseId)}&documentId=${encodeURIComponent(row.shortPickId)}&tab=movements`}>复核流水</Link>
          </Space> },
        ]} />}
    </Space>
    {action && <ShortPickDialog key={action.kind + ('review' in action ? action.review.shortPickId : '')}
      action={action} saving={saving} pending={pending} error={error} onClose={() => setAction(undefined)} onSubmit={run} />}
  </Card>;
}
