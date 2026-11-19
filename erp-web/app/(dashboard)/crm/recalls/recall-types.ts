import type { ShipmentTrace } from '@/types/warehouse-shipment';
import type { SalesReturnOrder, SalesReturnReceipt } from '@/components/sales-return-types';
export interface Recall { recallId: string; batchNo: string; reason: string; createdBy: string; createdAt: string }
export interface RecallLine {
  lineId: string; recallId: string; salesOrderId: string; outboundId: string; sellerSku: string; expectedQuantity: number;
  authorizedQuantity: number; receivedQuantity: number; confirmedQuantity: number; unidentifiedQuantity: number; pendingQuantity: number; status: string; closeReason?: string; closedBy?: string; closedAt?: string;
  closedExpectedQuantity?: number; closedReceivedQuantity?: number; closedConfirmedQuantity?: number; closedUnidentifiedQuantity?: number;
}
export interface Notification { notificationId: string; lineId: string; channel: string; content: string; operatorId: string; notifiedAt: string }
export interface RecallDetail { recall: Recall; lines: RecallLine[]; sources: ShipmentTrace[]; notifications: Notification[] }
export type RecallAction = { kind: 'create'; batchNo: string } | { kind: 'notify'; line: RecallLine } | { kind: 'return'; line: RecallLine }
  | { kind: 'close'; line: RecallLine } | { kind: 'verify'; order: SalesReturnOrder; receipt: SalesReturnReceipt };
export const recallEndpoint = '/crm/api/in/v1/batch-recalls';
export const returnEndpoint = '/crm/api/in/v1/sales-returns';
export const recallStatus: Record<string, string> = {
  EXCEPTION_CLOSED: '异常结案', PENDING_NOTIFICATION: '待通知', NOTIFIED: '已通知待退回', RECEIVED_PENDING_VERIFICATION: '已实收待核实', PARTIALLY_RETURNED: '部分已确认召回', COMPLETED: '召回完成',
};
export const channels = { PLATFORM: '平台站内', EMAIL: '邮件', PHONE: '电话', OTHER: '其他' };
