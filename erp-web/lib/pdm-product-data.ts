import apiClient, { get, put } from '@/lib/api/client';
import type { Result } from '@/types';

export interface Dimensions { lengthCm?: string | null; widthCm?: string | null; heightCm?: string | null }
export interface ProductImageReference { assetId?: string | null; url?: string | null }
export interface ProductData {
  weightKg?: string | null; declaredValue?: string | null; currency?: string | null; description?: string | null;
  barcodes: { type: string; value: string }[]; productDimensionsCm?: Dimensions | null;
  packageWeightKg?: string | null; packageDimensionsCm?: Dimensions | null; unitsPerCarton?: number | null;
  cartonDimensionsCm?: Dimensions | null; cartonWeightKg?: string | null;
  declaration?: { nameChinese?: string | null; nameEnglish?: string | null; hsCode?: string | null; countryOfOrigin?: string | null; material?: string | null; usage?: string | null } | null;
  remarks?: string | null; images: ProductImageReference[];
}
export interface ProductUsageCheck { valid: boolean; code: string; message: string; missingFields: string[] }
export type ProductUsage = 'FREIGHT' | 'PACKAGING' | 'DECLARATION';
export const productImagePath = '/pdm/api/in/v1/images';
export const productDetailsPath = (id: string) => `/pdm/api/in/v1/skus/${encodeURIComponent(id)}/details`;
export const productDataApi = {
  get: (id: string) => get<ProductData>(productDetailsPath(id)),
  replace: (id: string, data: ProductData) => put<ProductData>(productDetailsPath(id), data),
  check: (id: string, usage: ProductUsage) => get<ProductUsageCheck>(`/pdm/api/in/v1/skus/${encodeURIComponent(id)}/readiness`, { usage }),
  upload: async (file: File) => {
    const data = new FormData(); data.append('file', file);
    return (await apiClient.post<Result<{ assetId: string }>>(`${productImagePath}/upload`, data, { headers: { 'Content-Type': undefined } })).data.data;
  },
};
