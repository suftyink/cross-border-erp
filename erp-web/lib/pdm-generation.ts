import { post } from '@/lib/api/client';
import type { SpecificationItem } from './pdm-specification';
import type { SkuDraft } from './pdm-sku';

export interface GenerationRow {
  combinationKey: string; specifications: SpecificationItem[]; sellerSku: string; title: string; baseUnit: string;
  existing: { skuId: string; sellerSku: string; status: string } | null;
}
export interface GenerationPreview { previewId: string; spuId: string; expiresAt: string; rows: GenerationRow[] }
export interface GenerationCommit {
  previewId: string; rows: { combinationKey: string; sellerSku: string; title: string; baseUnit: string }[];
}
export interface GenerationResult { previewId: string; created: SkuDraft[] }
const path = (spuId: string) => `/pdm/api/in/v1/products/${encodeURIComponent(spuId)}/sku-generation`;
export const generationApi = {
  preview: (spuId: string, dimensions: { attributeId: string; valueIds: string[] }[]) => post<GenerationPreview>(`${path(spuId)}/preview`, { dimensions }),
  commit: (spuId: string, data: GenerationCommit) => post<GenerationResult>(`${path(spuId)}/commit`, data),
};
