export const qualityEndpoint = '/wms/api/in/v1/quality-corrections';
export interface Correction {
  correctionId: string; batchNo: string; receiptId: string; purchaseOrderId: string;
  warehouseId: string; sellerSku: string; quantity: number; reason: string; status: string;
  originalInspectionId: string; originalInspection: string; createdBy: string; createdAt: string;
  releasedBy?: string; releasedAt?: string; reversedQuantity: number; directPendingQuantity: number; excludedMissingQuantity: number;
  withdrawnBy?: string; withdrawnAt?: string; goodQuantity?: number; defectiveQuantity?: number;
  inspectedBy?: string; inspectedAt?: string;
}
export interface Placement { transactionId: string; locationId: string; locationCode: string; quantity: number; reversedQuantity: number }
export interface Allocation {
  allocationId: string; outboundId: string; sellerSku: string; locationCode: string; batchNo: string;
  plannedQuantity: number; pickedQuantity: number; shortQuantity: number; cancelledQuantity: number;
}
export interface AffectedPick { requestId: string; outboundId: string; allocationId: string; locationCode: string; batchNo: string; quantity: number; returnedQuantity: number; shippedQuantity: number }
export interface CorrectionDetail { correction: Correction; placements: Placement[]; allocations: Allocation[]; picks: AffectedPick[]; pendingReviewQuantity: number; reviewIds: string[] }
export interface CorrectionWork { canCorrect: boolean; canApprove: boolean; canInspect: boolean; cases: Correction[] }
export type CorrectionAction = { kind: 'pause' } | { kind: 'reverse'; placement: Placement; correction: Correction }
  | { kind: 'unpick'; pick: AffectedPick; correction: Correction } | { kind: 'inspection'; correction: Correction };
export type CorrectionAttempt = { path: string; body: unknown; label: string; permission: 'canCorrect' | 'canApprove' | 'canInspect' };
export const correctionLabels: Record<string, string> = { PAUSED: '已暂停待退回', PENDING_INSPECTION: '待重新全检', COMPLETED: '更正已完成' };
