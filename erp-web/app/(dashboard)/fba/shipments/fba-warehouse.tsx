'use client';

import { useRef, useState } from 'react';
import { Alert, Button, Card, Form, Modal, Select, Space, Table, Tag } from 'antd';
import Link from 'next/link';
import { isAxiosError } from 'axios';
import { useApi } from '@/lib/hooks';
import { post } from '@/lib/api/client';
import type { Warehouse } from '@/types';
import { FbaBoxLabels, type BoxLabelPlan } from './fba-box-labels';

interface Work { outboundId: string; status: string; warehouseId: string; requiredQuantity: number }
interface Row {
  shipment: { fbaShipmentId: string; amazonShipmentId: string; destinationFc: string; plannedQuantity: number; status: string };
  warehouseWork: Work | null;
  boxLabelPlan?: BoxLabelPlan | null;
  warehouseShipment?: { quantity: number; cartonCount: number; trackingNo: string; shippedAt: string } | null;
}
const states: Record<string, string> = { DRAFT: '草稿', SUBMITTED: '已提交', PACKED: '已打包', SHIPPED: '已发货', CLOSED: '已关闭', CANCELLED: '已取消' };

export function FbaWarehouse() {
  const work = useApi<{ canDispatch: boolean; canRegisterLabels: boolean; orders: Row[] }>('/fba/api/in/v1/warehouse-work');
  const warehouses = useApi<Warehouse[]>('/wms/api/in/v1/warehouses');
  const [selected, setSelected] = useState<Row>();
  const [labelRow, setLabelRow] = useState<Row>();
  const [warehouse, setWarehouse] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const submitting = useRef(false);
  const dispatch = async () => {
    if (!selected || !warehouse || submitting.current) return;
    submitting.current = true; setSaving(true); setError(undefined);
    try {
      await post(`/fba/api/in/v1/shipments/${selected.shipment.fbaShipmentId}/warehouse-work`, { warehouseId: warehouse });
      setSelected(undefined); await work.mutate();
    } catch (failure) {
      setError(isAxiosError(failure) ? failure.response?.data?.message ?? '下发结果未确认，请重试同一备货单和仓库' : '下发失败，请重试');
    } finally { submitting.current = false; setSaving(false); }
  };
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <Alert showIcon type="info" message="已提交的 FBA 备货单可下发本地仓库。仓管按实际批次拣货、按内部箱号装箱；装箱仍保留在手和预占。" />
    {(work.error || warehouses.error) && <Alert type="error" showIcon message="备货或仓库加载失败" action={<Button onClick={() => { void work.mutate(); void warehouses.mutate(); }}>重试加载</Button>} />}
    {error && <Alert showIcon type="error" message={error} />}
    {work.data?.canDispatch === false && <Alert type="info" message="当前账号可查看已授权的备货信息，没有下发仓储权限" />}
    <Table<Row> rowKey={(r) => r.shipment.fbaShipmentId} dataSource={work.data?.orders ?? []} loading={work.isLoading} scroll={{ x: 'max-content' }} columns={[
      { title: '备货单', render: (_, r) => r.shipment.fbaShipmentId },
      { title: 'Amazon 货件', render: (_, r) => r.shipment.amazonShipmentId },
      { title: '目的仓', render: (_, r) => r.shipment.destinationFc },
      { title: '计划件数', render: (_, r) => r.shipment.plannedQuantity },
      { title: 'FBA 状态', render: (_, r) => <Tag>{states[r.shipment.status] ?? r.shipment.status}</Tag> },
      { title: 'Seller Central 箱号', render: (_, r) => r.boxLabelPlan ? `${r.boxLabelPlan.boxNumbers.length} 箱已登记` : '尚未登记' },
      { title: '本地仓实际出库', render: (_, r) => r.warehouseShipment
        ? `${r.warehouseShipment.cartonCount} 箱 / ${r.warehouseShipment.quantity} 件 · ${r.warehouseShipment.trackingNo}` : '尚未收到仓库出库结果' },
      { title: '本地仓储作业', render: (_, r) => r.warehouseWork ? <Link href={`/wms/operations?outboundId=${r.warehouseWork.outboundId}`}>查看拣货与装箱</Link> : '尚未下发' },
      { title: '操作', render: (_, r) => <Space wrap>
        {!r.warehouseWork && r.shipment.status === 'SUBMITTED' && work.data?.canDispatch
          && <Button onClick={() => { setSelected(r); setWarehouse(undefined); setError(undefined); }}>下发仓储</Button>}
        {(r.boxLabelPlan || work.data?.canRegisterLabels && ['DRAFT', 'SUBMITTED'].includes(r.shipment.status))
          && <Button onClick={() => setLabelRow(r)}>{r.boxLabelPlan ? '查看箱号清单' : '登记 Seller Central 箱号'}</Button>}
      </Space> },
    ]} />
    {labelRow && <FbaBoxLabels shipment={labelRow.shipment} plan={labelRow.boxLabelPlan} onClose={() => setLabelRow(undefined)} onSaved={() => work.mutate()} />}
    <Modal title="下发 FBA 仓储作业" open={!!selected} onCancel={() => !saving && setSelected(undefined)} maskClosable={false}
      okText="确认下发" onOk={() => void dispatch()} confirmLoading={saving} okButtonProps={{ disabled: !warehouse }} cancelButtonProps={{ disabled: saving }}>
      {error && <Alert type="error" message={error} />}
      <Form layout="vertical"><Form.Item label="本地发货仓库" required>
        <Select aria-label="本地发货仓库" value={warehouse} onChange={setWarehouse} options={(warehouses.data ?? []).map(w => ({ value: w.warehouseId, label: w.name }))} />
      </Form.Item></Form>
    </Modal>
  </Space>;
}
