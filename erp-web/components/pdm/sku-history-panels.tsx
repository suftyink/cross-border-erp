'use client';

import { Alert, Modal, Table, Typography } from 'antd';
import { useApi } from '@/lib/hooks';
import { skuPath, type SkuHistoryEntry, type SkuMigrationIssue } from '@/lib/pdm-sku';
import { specificationText, type SpecificationItem } from '@/lib/pdm-specification';

const actionNames: Record<string, string> = { CREATE: '新增', UPDATE: '修改资料', ENABLE: '启用', DISABLE: '停用', DELETE: '删除草稿', SPECIFICATION: '修改规格', MIGRATE_EVIDENCE: '历史证据记录' };
const fieldNames: Record<string, string> = { sellerSku: '本地编码', spuId: '所属 SPU', title: '名称', baseUnit: '基本单位', status: '状态', specifications: '规格组合' };
const format = (field: string, value: unknown) =>
  field === 'specifications' ? specificationText(Array.isArray(value) ? (value as SpecificationItem[]) : undefined) : String(value ?? '—');

function fieldChanged(field: string, before: unknown, after: unknown) {
  if (field === 'specifications') {
    const left = Array.isArray(before) ? before : [];
    const right = Array.isArray(after) ? after : [];
    return JSON.stringify(left) !== JSON.stringify(right);
  }
  return JSON.stringify(before ?? null) !== JSON.stringify(after ?? null);
}

function changes(entry: SkuHistoryEntry) {
  return Object.entries(fieldNames)
    .filter(([field]) => fieldChanged(field, entry.before?.[field], entry.after?.[field]))
    .map(([field, label]) => `${label}：${format(field, entry.before?.[field])} → ${format(field, entry.after?.[field])}`)
    .join('；') || '记录历史身份事实';
}

export function SkuHistoryPanel({ skuId, onClose }: { skuId: string | null; onClose: () => void }) {
  const result = useApi<SkuHistoryEntry[]>(skuId ? `${skuPath}/${encodeURIComponent(skuId)}/history` : null);
  return <Modal title="SKU 变更记录" open={!!skuId} onCancel={onClose} footer={null} width={900}>
    {result.error && <Alert type="error" showIcon message="变更记录加载失败" />}
    <Table<SkuHistoryEntry> rowKey="eventId" loading={result.isLoading} dataSource={result.data?.slice().reverse() ?? []}
      locale={{ emptyText: '暂无变更记录' }} scroll={{ x: 800 }} size="small" pagination={{ pageSize: 10 }} columns={[
        { title: '时间', dataIndex: 'occurredAt', render: (time: string) => new Date(time).toLocaleString('zh-CN') },
        { title: '操作', dataIndex: 'action', render: (action: string) => actionNames[action] ?? action },
        { title: '操作人 ID', dataIndex: 'actorId' },
        { title: '变更内容', render: (_, entry) => <Typography.Text>{changes(entry)}</Typography.Text> },
        { title: '说明', dataIndex: 'reason', render: (reason: string | null) => reason || '—' },
      ]} />
  </Modal>;
}

export function SkuMigrationPanel({ open, issues, onClose }: { open: boolean; issues: SkuMigrationIssue[]; onClose: () => void }) {
  return <Modal title="历史商品待核实记录" open={open} onCancel={onClose} footer={null} width={900}>
    <Alert type="warning" showIcon message="这些记录缺少明确证据，系统保留原始资料并保护身份。请核实后再修复，不会自动认定为从未启用。" style={{ marginBottom: 16 }} />
    <Table<SkuMigrationIssue> rowKey={(row) => `${row.kind}:${row.id}`} dataSource={issues} size="small" pagination={{ pageSize: 10 }}
      locale={{ emptyText: '没有待核实记录' }} columns={[
        { title: '类型', dataIndex: 'kind', render: (kind: string) => kind === 'SKU' ? '本地 SKU' : '旧变体' },
        { title: '编码', dataIndex: 'code', render: (code: string | null) => code ?? '未知' },
        { title: '标识', dataIndex: 'id' }, { title: '待核实原因', dataIndex: 'message' },
      ]} />
  </Modal>;
}
