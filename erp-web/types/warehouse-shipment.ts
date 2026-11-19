export interface ShipmentLine {
  pickId: string; allocationId: string; outboundLineId: string; salesLineId: string;
  sellerSku: string; locationId: string; locationCode: string; batchNo: string;
  receiptId: string; purchaseOrderId: string; quantity: number;
}
export interface WarehouseShipment {
  packageId: string; outboundId: string; salesOrderId: string; warehouseId: string;
  status: string; weightKg?: number; carrierCode?: string; trackingNo?: string;
  shippedAt?: string; operatorId?: string; omsSyncStatus: string; syncError?: string;
  lines: ShipmentLine[];
}
export interface ShipmentTrace extends Omit<ShipmentLine, 'allocationId' | 'outboundLineId'> {
  packageId: string; outboundId: string; salesOrderId: string; warehouseId: string;
  shippedAt: string; operatorId: string;
}
