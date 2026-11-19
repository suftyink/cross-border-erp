export interface ShortAllocation {
  allocationId: string; sellerSku: string; locationCode: string; batchNo: string;
  plannedQuantity: number; pickedQuantity: number; shortQuantity: number;
}
export interface ShortPickSource {
  outboundId: string; allowBatchSubstitution: boolean; allocations: ShortAllocation[];
}
export interface ShortPickCase {
  shortPickId: string; outboundId: string; allocationId: string; warehouseId: string;
  sellerSku: string; locationId: string; locationCode: string; batchNo: string; receiptId: string; purchaseOrderId: string;
  shortQuantity: number; allowBatchSubstitution: boolean; reason: string; reportedBy: string; reportedAt: string;
  replacementAllocationId?: string; replacementBatchNo?: string; replacedBy?: string; replacedAt?: string;
  foundQuantity?: number; missingQuantity?: number; countedBy?: string; countedAt?: string; countRemark?: string;
  confirmedBy?: string; confirmedAt?: string; pendingReview: number; status: string;
}
export type ShortPickAction = { kind: 'report'; source: ShortPickSource }
  | { kind: 'replace' | 'count' | 'confirm'; review: ShortPickCase };
export type ShortPickAttempt = { path: string; body: Record<string, unknown>; label: string };
export const shortPickEndpoint = '/wms/api/in/v1/short-picks';
