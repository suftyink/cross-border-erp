'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, message } from 'antd';
import Link from 'next/link';
import { isAxiosError } from 'axios';
import { post } from '@/lib/api/client';
import { useApi } from '@/lib/hooks';
import { useAuthStore } from '@/lib/store';
import type { PurchaseReceipt, Warehouse, SupplierReturn } from '@/types';
import { newOperationId } from './operation-id';

interface QuarantineStock {
  batchNo: string;
  warehouseId: string;
  sellerSku: string;
  quantity: number;
  status: string;
  locationId?: string;
  locationCode?: string;
}
interface Work {
  canIsolate: boolean;
  canRequest: boolean;
  canShip: boolean;
  pendingIsolation: QuarantineStock[];
  returns: SupplierReturn[];
}
const endpoint = '/wms/api/in/v1/purchase-returns';
const workUrl = (purchaseOrderId: string | null) => purchaseOrderId
  ? `${endpoint}/work?purchaseOrderId=${encodeURIComponent(purchaseOrderId)}` : `${endpoint}/work`;
type Action = 'isolate' | 'request' | 'ship';
type Fields = { quantity?: number; locationCode?: string; stockKey?: string; reason?: string };
const label: Record<Action, string> = { isolate: '确认隔离入位', request: '提交仅退货申请', ship: '确认退供出库' };
type PendingCommand = { path: string; action: Action; body: Record<string, unknown> };
const storageEvent = 'wms:purchase-return-command';

export function PurchaseReturns({ purchaseOrderId, returnId, warehouses }: {
  purchaseOrderId: string | null; returnId: string | null; warehouses: Warehouse[];
}) {
  const work = useApi<Work>(workUrl(purchaseOrderId));
  const [isolation, setIsolation] = useState<QuarantineStock>();
  const [shipment, setShipment] = useState<SupplierReturn>();
  const refresh = () => { void work.mutate(); };
  const recovery = useReturnCommand(refresh);
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    {work.error && <Alert type="error" showIcon message="退供作业加载失败" />}
    {recovery.error && <Alert type="error" showIcon message={recovery.error} />}
    {recovery.pending && <Alert type="warning" showIcon message={`待确认 · ${label[recovery.pending.action]}`}
      action={<Button loading={recovery.saving} onClick={() => void recovery.send(recovery.pending!)}>重试原操作</Button>} />}
    {work.data?.canIsolate && <Card title="待隔离入位">
      <Table<QuarantineStock> rowKey="batchNo" size="small" dataSource={work.data.pendingIsolation} scroll={{ x: 'max-content' }}
        columns={[
          { title: '仓库', render: (_, row) => warehouses.find((warehouse) => warehouse.warehouseId === row.warehouseId)?.name ?? row.warehouseId },
          { title: 'SKU', dataIndex: 'sellerSku' },
          { title: '收货批次', dataIndex: 'batchNo' },
          { title: '未入位件数', dataIndex: 'quantity' },
          { title: '操作', render: (_, row) => <Button onClick={() => setIsolation(row)}>登记隔离库位</Button> },
        ]} />
    </Card>}
    <Card title="采购退供">
      <Table<SupplierReturn> rowKey="returnId" size="small" loading={work.isLoading}
        dataSource={(work.data?.returns ?? []).filter((row) => !returnId || row.returnId === returnId)}
        scroll={{ x: 'max-content' }}
        columns={[
          { title: '退供单号', dataIndex: 'returnId' },
          { title: 'SKU', dataIndex: 'sellerSku' },
          { title: '原批次', render: (_, row) => <Link
            href={`/wms/operations?batchNo=${encodeURIComponent(row.batchNo)}`}>{row.batchNo}</Link> },
          { title: '采购单行', dataIndex: 'purchaseOrderLineId' },
          { title: '隔离库位', dataIndex: 'locationCode' },
          { title: '申请件数', dataIndex: 'requestedQuantity' },
          { title: '实退件数', render: (_, row) => row.shippedQuantity ?? '—' },
          { title: '状态', render: (_, row) => <Tag>{row.shippedAt ? '已出库' : '待出库'}</Tag> },
          { title: '操作', render: (_, row) => row.shippedAt
            ? <Link href={`/wms/inventory?batchNo=${row.batchNo}&documentId=${row.returnId}&tab=movements`}>退供流水</Link>
            : work.data?.canShip && <Button onClick={() => setShipment(row)}>确认实发</Button> },
        ]} />
    </Card>
    {isolation && <ReturnDialog action="isolate" title="登记隔离库位" max={isolation.quantity}
      path={`${endpoint}/batches/${isolation.batchNo}/isolate`}
      onClose={() => setIsolation(undefined)} onSaved={() => { setIsolation(undefined); refresh(); }} />}
    {shipment && <ReturnDialog action="ship" title="确认退供实发" max={shipment.requestedQuantity}
      path={`${endpoint}/${shipment.returnId}/ship`}
      source={`${shipment.sellerSku} · ${shipment.locationCode} · ${shipment.batchNo}`}
      onClose={() => setShipment(undefined)} onSaved={() => { setShipment(undefined); refresh(); }} />}
  </Space>;
}

export function ReceiptReturnAction({ receipt }: { receipt: PurchaseReceipt }) {
  const [open, setOpen] = useState(false);
  const work = useApi<Work>(workUrl(receipt.purchaseOrderId));
  const stocks = useApi<QuarantineStock[]>(open
    ? `/wms/api/in/v1/inventory-ledger/balances?purchaseOrderId=${encodeURIComponent(receipt.purchaseOrderId)}` : null);
  const byKey = new Map((stocks.data ?? []).filter((row) => row.status === 'QUARANTINED' && row.locationId
    && receipt.lines.some((line) => line.batchNo === row.batchNo))
    .map((row) => [`${row.batchNo}/${row.locationId}`, row]));
  if (!work.data?.canRequest) return null;
  return <>
    <Button onClick={() => setOpen(true)}>发起仅退货</Button>
    {open && <ReturnDialog action="request" title="发起仅退货" path={`${endpoint}/receipts/${receipt.receiptId}`}
      choices={byKey} loadingChoices={stocks.isLoading} choicesError={!!stocks.error} onClose={() => setOpen(false)}
      onSaved={() => { setOpen(false); void work.mutate(); }} />}
  </>;
}

function ReturnDialog({ action, title, path, source, max, choices, loadingChoices, choicesError, onClose, onSaved }: {
  action: Action; title: string; path: string; source?: string; max?: number; choices?: Map<string, QuarantineStock>;
  loadingChoices?: boolean; choicesError?: boolean; onClose: () => void; onSaved: () => void;
}) {
  const [form] = Form.useForm<Fields>();
  const recovery = useReturnCommand(onSaved);
  const key = Form.useWatch('stockKey', form);
  const stock = key && choices?.get(key);
  const allowed = stock ? stock.quantity : max;
  const retry = recovery.pending?.path === path ? recovery.pending : undefined;
  const submit = async (values: Fields) => {
    if (recovery.saving || recovery.pending || values.quantity == null) return;
    const body = action === 'ship' ? { quantity: values.quantity } : action === 'isolate'
      ? { requestId: newOperationId(), locationCode: values.locationCode, quantity: values.quantity }
      : { returnId: newOperationId(), batchNo: stock && stock.batchNo, locationId: stock && stock.locationId,
        quantity: values.quantity, reason: values.reason };
    await recovery.send({ path, action, body });
  };
  return <Modal title={title} open closable={!recovery.saving} keyboard={!recovery.saving} maskClosable={false}
    cancelButtonProps={{ disabled: recovery.saving }} onCancel={onClose} confirmLoading={recovery.saving}
    okText={retry ? '重试原操作' : label[action]}
    okButtonProps={{ disabled: !retry && (!!recovery.pending || (action === 'request' && !choices?.size)) }}
    onOk={() => retry ? void recovery.send(retry) : form.submit()}>
    {source && <p>{source}</p>}
    {recovery.error && <Alert showIcon type="error" message={recovery.error} />}
    {retry && <Alert showIcon type="warning" message={`待确认 ${retry.body.quantity} 件。重试将提交原操作。`} />}
    {recovery.pending && !retry && <Alert showIcon type="warning" message="请先在隔离与退供页签重试待确认操作" />}
    {choicesError && <Alert showIcon type="error" message="隔离库存查询失败" />}
    {action === 'request' && !loadingChoices && !choicesError && !choices?.size
      && <Alert showIcon type="info" message="该收货记录暂无已入隔离库位的次品" />}
    <Form form={form} layout="vertical" onFinish={submit} disabled={recovery.saving || !!recovery.pending}>
      {action === 'request' && <Form.Item name="stockKey" label="原批次与隔离库位" rules={[{ required: true }]}>
        <Select aria-label="原批次与隔离库位" loading={loadingChoices}
          options={Array.from(choices ?? [], ([value, row]) => ({
            value, label: `${row.locationCode} · ${row.batchNo} · ${row.quantity} 件`,
          }))} />
      </Form.Item>}
      {action === 'isolate' && <Form.Item name="locationCode" label="实际隔离库位码"
        rules={[{ required: true, whitespace: true }]}>
        <Input aria-label="实际隔离库位码" maxLength={128} />
      </Form.Item>}
      <Form.Item name="quantity" label="数量" extra={allowed ? `本次最多 ${allowed} 件` : undefined}
        rules={[{ required: true }, { type: 'integer', min: 1, max: allowed }]}>
        <InputNumber aria-label="数量" precision={0} min={1} max={allowed} />
      </Form.Item>
      {action === 'request' && <Form.Item name="reason" label="退货原因" rules={[{ required: true, whitespace: true }]}>
        <Input.TextArea aria-label="退货原因" maxLength={500} />
      </Form.Item>}
      {action === 'ship' && <p>提交实发数量即结案，未发数量留在原隔离库位，可另行申请。</p>}
    </Form>
  </Modal>;
}

function useReturnCommand(onSaved: () => void) {
  const tenant = useAuthStore((state) => state.tenantId);
  const storageKey = `wms:purchase-return-command:${tenant}`;
  const [pending, setPending] = useState<PendingCommand>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  useEffect(() => {
    const refresh = () => {
      const raw = sessionStorage.getItem(storageKey);
      setPending(raw ? JSON.parse(raw) as PendingCommand : undefined);
    };
    refresh();
    window.addEventListener(storageEvent, refresh);
    return () => window.removeEventListener(storageEvent, refresh);
  }, [storageKey]);

  const send = async (command: PendingCommand) => {
    if (saving) return;
    const stored = sessionStorage.getItem(storageKey);
    const serialized = JSON.stringify(command);
    if (stored && stored !== serialized) {
      setError('请先重试上一笔待确认操作');
      return;
    }
    setSaving(true);
    setError(undefined);
    const clear = () => {
      sessionStorage.removeItem(storageKey);
      window.dispatchEvent(new Event(storageEvent));
    };
    try {
      sessionStorage.setItem(storageKey, serialized);
      window.dispatchEvent(new Event(storageEvent));
      await post(command.path, command.body);
      clear();
      message.success('操作已确认');
      onSaved();
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status < 500 && response.status !== 401 && response.status !== 403) clear();
      setError(response?.data?.message ?? '操作结果未确认，请核对记录或重试。');
    } finally {
      setSaving(false);
    }
  };
  return { pending, saving, error, send };
}
