'use client';

import { Button, message } from 'antd';
import type { PurchaseReceipt } from '@/types';

export function ReceiptLabels({ receipt }: { receipt: PurchaseReceipt }) {
  const printLabels = () => {
    const printWindow = window.open('', '_blank');
    if (!printWindow) {
      message.warning('请允许打开打印窗口后重试');
      return;
    }
    printWindow.opener = null;
    const doc = printWindow.document;
    doc.title = '收货批次标签';
    const style = doc.createElement('style');
    style.textContent = 'body{font:14px sans-serif}section{border:1px solid;padding:10px;margin:8px 0;break-inside:avoid}p{margin:4px 0}';
    doc.head.append(style);
    for (const line of receipt.lines) {
      const label = doc.createElement('section');
      const heading = doc.createElement('strong');
      heading.textContent = line.sellerSku;
      label.append(heading);
      for (const text of [
        `批次 ${line.batchNo}`, `实收 ${line.quantity} 件`,
        `采购单 ${receipt.purchaseOrderId}`, `采购明细 ${line.purchaseOrderLineId}`,
      ]) {
        const row = doc.createElement('p');
        row.textContent = text;
        label.append(row);
      }
      doc.body.append(label);
    }
    printWindow.print();
  };
  return <Button onClick={printLabels}>打印批次标签</Button>;
}
