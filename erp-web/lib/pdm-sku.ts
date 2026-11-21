import { get, post, put } from '@/lib/api/client';

export const skuPath = '/pdm/api/in/v1/skus';
export const spuPath = '/pdm/api/in/v1/products';

export interface SpuOption {
  spuId: string;
  sku: string | null;
  title: string | null;
  description: string | null;
  categoryId: string | null;
  brandId: string | null;
  status: string;
}

export interface SkuRecord {
  skuId: string;
  spuId: string;
  sellerSku: string;
  title: string | null;
  baseUnit: string | null;
  status: string;
}

export interface SkuDraft extends SkuRecord {
  spuCode: string | null;
  spuTitle: string | null;
  categoryId: string | null;
  brandId: string | null;
  editable: boolean;
  weightKg: number | null;
  declaredValue: number | null;
  currency: string | null;
  missingFields: string[];
  createdAt: string;
  updatedAt: string;
}

export interface NewSpuDraft {
  sku?: string;
  title?: string;
  description?: string;
  categoryId?: string;
  brandId?: string;
}

export interface CreateSkuDraft {
  spuId?: string;
  sellerSku?: string;
  title?: string;
  baseUnit?: string;
  newSpu?: NewSpuDraft;
}

export const skuDraftApi = {
  generateCode: () => get<string>(`${skuPath}/generated-code`),
  create: (data: CreateSkuDraft) => post<SkuDraft>(skuPath, data),
  update: (skuId: string, data: Pick<CreateSkuDraft, 'title' | 'baseUnit'>) =>
    put<SkuDraft>(`${skuPath}/${encodeURIComponent(skuId)}`, data),
};

export const skuStatusLabels: Record<string, string> = {
  DRAFT: '草稿', ACTIVE: '已启用', INACTIVE: '已停用', APPROVED: '已审核', ARCHIVED: '已归档',
};
