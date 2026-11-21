import { del, get, post, put } from '@/lib/api/client';
import type { SpecificationItem, SpecificationSelection } from './pdm-specification';
import type { ProductData } from './pdm-product-data';

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
  details: ProductData;
  specifications: SpecificationItem[];
  spuCode: string | null;
  spuTitle: string | null;
  categoryId: string | null;
  brandId: string | null;
  editable: boolean;
  everEnabled: boolean | null;
  identityLocked: boolean;
  identityEditable: boolean;
  deletable: boolean;
  historyReviewRequired: boolean;
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
  specifications?: SpecificationSelection[];
  spuId?: string;
  sellerSku?: string;
  title?: string;
  baseUnit?: string;
  newSpu?: NewSpuDraft;
}

export const skuDraftApi = {
  generateCode: () => get<string>(`${skuPath}/generated-code`),
  create: (data: CreateSkuDraft, enable = false) => post<SkuDraft>(enable ? `${skuPath}/save-and-enable` : skuPath, data),
  update: (skuId: string, data: Omit<CreateSkuDraft, 'newSpu'>, enable = false) =>
    put<SkuDraft>(`${skuPath}/${encodeURIComponent(skuId)}${enable ? '/save-and-enable' : ''}`, data),
  enable: (skuId: string) => post<SkuDraft>(`${skuPath}/${encodeURIComponent(skuId)}/enable`, {}),
  disable: (skuId: string) => post<SkuDraft>(`${skuPath}/${encodeURIComponent(skuId)}/disable`, {}),
  remove: (skuId: string) => del<void>(`${skuPath}/${encodeURIComponent(skuId)}`),
  batch: (skuIds: string[], action: 'ENABLE' | 'DISABLE') => post<SkuBatchOutcome[]>(`${skuPath}/batch-status`, { skuIds, action }),
};

export interface SkuBatchOutcome { skuId: string; success: boolean; code: string; message: string; status: string | null }
export interface SkuHistoryEntry {
  eventId: string; action: string; actorId: string; reason: string | null; occurredAt: string;
  before: Record<string, unknown> | null; after: Record<string, unknown> | null;
}
export interface SkuMigrationIssue { kind: string; id: string; code: string | null; message: string }

export const skuStatusLabels: Record<string, string> = {
  DRAFT: '草稿', ACTIVE: '已启用', INACTIVE: '已停用', APPROVED: '已审核', ARCHIVED: '已归档',
};
