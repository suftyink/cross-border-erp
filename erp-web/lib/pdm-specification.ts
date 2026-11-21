import { isAxiosError } from 'axios';

export const specificationPath = '/pdm/api/in/v1/specification-attributes';
export const specificationOptionsPath = (spuId: string) => `/pdm/api/in/v1/products/${encodeURIComponent(spuId)}/specification-options`;
export interface SpecificationValue { valueId: string; attributeId: string; code: string; name: string; description: string | null }
export interface SpecificationAttribute { attributeId: string; code: string; name: string; description: string | null; values: SpecificationValue[] }
export interface SpecificationDimension { attributeId: string; attributeCode: string; attributeName: string; values: SpecificationValue[] }
export interface SpecificationOptions { spuId: string; dimensions: SpecificationDimension[] }
export interface SpecificationSelection { attributeId: string; valueId: string }
export interface SpecificationItem extends SpecificationSelection { attributeCode: string; attributeName: string; valueCode: string; valueName: string }
export const specificationText = (items: SpecificationItem[] | undefined) => items?.map(item => `${item.attributeName}：${item.valueName}`).join(' / ') || '未设置规格';
export const specificationError = (error: unknown) => isAxiosError<{ message?: string }>(error) ? error.response?.data.message ?? '操作失败，请重试' : '操作失败，请重试';
