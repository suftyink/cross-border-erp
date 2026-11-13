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
  'wms:receipt:write',
];

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
            .map((p) => ({ key: p, title: p === 'wms:receipt:write' ? '仓储 · 确认采购收货' : p }))}
            targetKeys={selectedPerms} onChange={(keys) => setSelectedPerms(keys as string[])} render={(item) => item.title} listStyle={{ width: 260, height: 320 }} />
        </div>
      </Modal>
    </div>
  );
}
