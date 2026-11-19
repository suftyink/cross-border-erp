export interface SalesReturnOrder {
  returnId: string; recallId: string; recallLineId: string; salesOrderId: string; outboundId: string;
  warehouseId: string; sellerSku: string; expectedBatchNo: string; sourceReceiptId: string; purchaseOrderId: string;
  quantity: number; receivedQuantity: number; confirmedQuantity: number; pendingReceiptQuantity: number; status: string;
  createdBy: string; createdAt: string;
}
export interface SalesReturnReceipt {
  receiptId: string; returnId: string; batchNo: string; quantity: number; operatorId: string; receivedAt: string;
  verifiedBy?: string; verifiedAt?: string;
}
export interface SalesReturnDetail { order: SalesReturnOrder; receipts: SalesReturnReceipt[] }
export const salesReturnLabels: Record<string, string> = {
  AWAITING_RECEIPT: '待退货收货', PARTIALLY_RECEIVED: '部分实收', RECEIVED_PENDING_VERIFICATION: '已实收待核实', COMPLETED: '已核实完成',
};
