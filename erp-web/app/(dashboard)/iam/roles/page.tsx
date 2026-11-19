'use client';

import { useState } from 'react';
import { Table, Button, Modal, Form, Input, Tag, Space, message, Card, Typography, Transfer } from 'antd';
import { PlusOutlined, EditOutlined } from '@ant-design/icons';
import { useApi } from '@/lib/hooks';
import { iamApi } from '@/lib/api';
import type { Role } from '@/types';

const { Title } = Typography;

const allPermissions = [
  'order:read', 'order:write', 'product:read', 'product:write',
  'inventory:read', 'inventory:write', 'finance:read', 'finance:write',
  'customer:read', 'customer:write', 'logistics:read', 'logistics:write',
  'system:read', 'system:write', 'report:read', 'ads:read', 'ads:write',
  'wms:receipt:write', 'wms:inspection:write', 'scm:supplier:tolerance:write', 'scm:purchase:amend',
  'wms:putaway:write', 'wms:location:write',
  'oms:warehouse:write', 'wms:pick:write',
  'fba:warehouse:write', 'wms:carton:write',
  'wms:ship:write',
  'crm:recall:close', 'crm:recall:write', 'crm:return:write', 'wms:return:write',
  'wms:quality-correction:write', 'wms:quality-correction:approve',
  'wms:stock-review:write', 'wms:stock-review:approve',
  'wms:quarantine:write', 'scm:purchase-return:write', 'wms:purchase-return:ship',
];

const permissionLabels: Record<string, string> = {
  'fba:warehouse:write': 'FBA · 下发本地仓储备货',
  'wms:carton:write': '仓储 · FBA 按内部箱号装箱',
  'crm:recall:close': '售后 · 召回明细异常结案',
  'crm:recall:write': '售后 · 批次召回与通知登记',
  'crm:return:write': '售后 · 销售退货建单与批次核实',
  'wms:return:write': '仓储 · 销售退货分批收货',
  'wms:quality-correction:write': '仓储 · 发起质检更正与实物退回',
  'wms:quality-correction:approve': '仓储 · 主管确认质检更正解除预占',
  'wms:stock-review:write': '仓储 · 录入缺拣复盘',
  'wms:stock-review:approve': '仓储 · 主管确认差异复核',
  'wms:ship:write': '仓储 · 销售打包称重与实际发货',
  'oms:warehouse:write': '订单 · 下发仓储作业',
  'wms:pick:write': '仓储 · 登记实际拣货',
  'wms:receipt:write': '仓储 · 确认采购收货',
  'wms:inspection:write': '仓储 · 采购批次质检',
  'wms:putaway:write': '仓储 · 确认良品上架',
  'wms:location:write': '仓储 · 管理库位',
  'wms:quarantine:write': '仓储 · 确认隔离入位',
  'scm:purchase-return:write': '采购 · 发起退供',
  'wms:purchase-return:ship': '仓储 · 确认退供出库',
  'scm:supplier:tolerance:write': '采购 · 设置供应商超收容差',
  'scm:purchase:amend': '采购 · 变更收齐采购单',
};

export default function RolesPage() {
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRole, setEditingRole] = useState<Role | null>(null);
  const [selectedPerms, setSelectedPerms] = useState<string[]>([]);
  const [form] = Form.useForm();
  const { data, mutate } = useApi<Role[]>('/iam/api/in/v1/roles');

  const handleCreate = () => { setEditingRole(null); form.resetFields(); setSelectedPerms([]); setModalOpen(true); };
  const handleEdit = (record: Role) => {
    setEditingRole(record);
    form.setFieldsValue({ name: record.name, code: record.code });
    setSelectedPerms(record.permIds ?? []);
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (!selectedPerms.length) { message.error('请选择权限'); return; }
      if (editingRole) { await iamApi.updateRolePermissions(editingRole.roleId, selectedPerms); message.success('角色权限更新成功，请重新登录'); }
      else { await iamApi.createRole({ ...values, permIds: selectedPerms }); message.success('角色创建成功'); }
      setModalOpen(false);
      mutate();
    } catch { message.error('操作失败'); }
  };

  const columns = [
    { title: '角色名', dataIndex: 'name', key: 'name' },
    { title: '权限', dataIndex: 'permIds', key: 'permIds', render: (perms: string[]) => (
      <span>{perms?.slice(0, 5).map((p) => <Tag key={p} color="blue" style={{ marginBottom: 4 }}>{p}</Tag>)}{perms?.length > 5 && <Tag>+{perms.length - 5}</Tag>}</span>
    )},
    { title: '操作', key: 'action', render: (_: unknown, record: Role) => <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>编辑</Button> },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Title level={4} style={{ margin: 0 }}>角色管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>新建角色</Button>
      </div>
      <Card style={{ borderRadius: 8 }}>
        <Table rowKey="roleId" columns={columns} dataSource={data ?? []} size="middle" pagination={{ pageSize: 20 }} />
      </Card>
      <Modal title={editingRole ? '编辑角色' : '新建角色'} open={modalOpen} onOk={handleSubmit} onCancel={() => setModalOpen(false)} width={640}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="角色名" rules={[{ required: true }]}><Input disabled={!!editingRole} /></Form.Item>
          <Form.Item name="code" label="角色编码" rules={[{ required: true }]}><Input disabled={!!editingRole} /></Form.Item>
        </Form>
        <div style={{ marginBottom: 16 }}>
          <div style={{ marginBottom: 8, fontWeight: 500 }}>权限分配</div>
          <Transfer dataSource={Array.from(new Set([...allPermissions, ...(editingRole?.permIds ?? [])]))
            .map((p) => ({ key: p, title: permissionLabels[p] ?? p }))}
            targetKeys={selectedPerms} onChange={(keys) => setSelectedPerms(keys as string[])} render={(item) => item.title} listStyle={{ width: 260, height: 320 }} />
        </div>
      </Modal>
    </div>
  );
}
