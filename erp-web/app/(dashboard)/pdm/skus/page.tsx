'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import { Typography } from 'antd';
import { SkuManagement } from '@/components/pdm/sku-management';

function SkuContent() {
  const search = useSearchParams();
  return <SkuManagement spuId={search.get('spuId') ?? undefined} initialKeyword={search.get('keyword') ?? ''} />;
}

export default function SkusPage() {
  return <Suspense fallback={<Typography.Text>正在加载 SKU 管理…</Typography.Text>}><SkuContent /></Suspense>;
}
