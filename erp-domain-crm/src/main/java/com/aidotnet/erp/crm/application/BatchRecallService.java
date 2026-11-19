package com.aidotnet.erp.crm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.common.wms.WarehouseShipments.Trace;
import com.aidotnet.erp.crm.domain.BatchRecall;
import com.aidotnet.erp.crm.infrastructure.mapper.BatchRecallMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchRecallService {
    private final BatchRecallMapper recalls;
    private final WarehouseShipments shipments;
    public BatchRecallService(BatchRecallMapper recalls, WarehouseShipments shipments) { this.recalls = recalls; this.shipments = shipments; }

    public List<Trace> affected(String tenant, String batch) { return shipments.forBatch(tenant, batch); }
    public List<BatchRecall> list(String tenant, String batch) { return recalls.list(tenant, batch); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BatchRecall.Detail get(String tenant, String id) {
        BatchRecall r = require(tenant, id);
        return new BatchRecall.Detail(r, recalls.lines(tenant, id), recalls.sources(tenant, id), recalls.notifications(tenant, id));
    }

    @Transactional
    public BatchRecall.Detail create(String tenant, String id, String batch, String reason, String operator) {
        recalls.ensureScope(tenant, batch);
        recalls.lockScope(tenant, batch);
        var existing = recalls.recall(tenant, id);
        if (existing != null) {
            if (existing.batchNo().equals(batch) && existing.reason().equals(reason.strip())) return get(tenant, id);
            throw conflict();
        }
        List<Trace> sources = affected(tenant, batch).stream()
                .filter(s -> !recalls.sourceClaimed(tenant, s.packageId(), s.pickId())).toList();
        if (sources.isEmpty()) throw new BizException("RECALL_NO_SHIPMENTS", "该批次没有未纳入召回的实际发货记录");
        recalls.insertRecall(tenant, new BatchRecall(id, batch, reason.strip(), operator, Instant.now()));
        for (var group : sources.stream().collect(Collectors.groupingBy(s -> new Scope(s.salesOrderId(), s.outboundId(), s.sellerSku()))).values()) {
            Trace first = group.get(0);
            int quantity = group.stream().mapToInt(Trace::quantity).reduce(0, Math::addExact);
            String lineId = UUID.randomUUID().toString();
            recalls.insertLine(tenant, new BatchRecall.Line(lineId, id, first.salesOrderId(), first.outboundId(), first.warehouseId(),
                    first.sellerSku(), batch, first.receiptId(), first.purchaseOrderId(), quantity, 0, 0, 0, 0));
            group.forEach(source -> recalls.insertSource(tenant, id, lineId, source));
        }
        return get(tenant, id);
    }

    @Transactional
    public BatchRecall.Detail notify(String tenant, String id, String lineId, String notificationId,
                                     BatchRecall.Channel channel, String content, String operator) {
        requireLine(tenant, id, lineId);
        var previous = recalls.notification(tenant, notificationId);
        if (previous != null) {
            if (previous.lineId().equals(lineId) && previous.channel() == channel && previous.content().equals(content.strip())) return get(tenant, id);
            throw conflict();
        }
        recalls.insertNotification(tenant, new BatchRecall.Notification(notificationId, lineId, channel, content.strip(), operator, Instant.now()));
        return get(tenant, id);
    }

    private BatchRecall require(String tenant, String id) {
        var recall = recalls.recall(tenant, id);
        if (recall == null) throw new BizException("RECALL_NOT_FOUND", "召回单不存在");
        return recall;
    }
    private void requireLine(String tenant, String id, String line) {
        String recall = recalls.lockLine(tenant, line);
        if (!id.equals(recall)) throw new BizException("RECALL_LINE_NOT_FOUND", "明细不属于当前召回单");
    }
    private BizException conflict() { return new BizException("RECALL_REQUEST_CONFLICT", "该操作编号已用于不同的召回内容"); }
    private record Scope(String salesOrderId, String outboundId, String sellerSku) {}
}
