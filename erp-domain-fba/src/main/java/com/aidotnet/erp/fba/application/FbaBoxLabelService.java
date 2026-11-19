package com.aidotnet.erp.fba.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.fba.FbaBoxLabels;
import com.aidotnet.erp.fba.infrastructure.FbaShipmentRepository;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaBoxLabelMapper;
import com.aidotnet.erp.fba.infrastructure.mapper.FbaWarehouseMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FbaBoxLabelService implements FbaBoxLabels {
    private final FbaBoxLabelMapper labels;
    private final FbaWarehouseMapper locks;
    private final FbaShipmentRepository shipments;
    public FbaBoxLabelService(FbaBoxLabelMapper labels, FbaWarehouseMapper locks, FbaShipmentRepository shipments) {
        this.labels = labels; this.locks = locks; this.shipments = shipments;
    }

    @Transactional
    public Plan register(String tenant, String shipment, String operator, String amazon, List<String> numbers) {
        if (locks.lockShipment(tenant, shipment) == null) throw new BizException("FBA_SHIPMENT_NOT_FOUND", "FBA 备货单不存在");
        amazon = amazon.strip();
        List<String> normalized = numbers.stream().map(String::strip).sorted().toList();
        if (normalized.isEmpty() || normalized.stream().anyMatch(String::isBlank) || normalized.stream().distinct().count() != normalized.size()) {
            throw new BizException("FBA_LABEL_NUMBERS_INVALID", "箱号不能为空或重复，每个 FBA 箱号只登记一次");
        }
        Plan previous = find(tenant, shipment);
        if (previous != null) {
            if (previous.amazonShipmentId().equals(amazon) && previous.boxNumbers().equals(normalized)) return previous;
            throw new BizException("FBA_LABEL_PLAN_CONFLICT", "该货件已登记完整箱号清单，请按原清单核对");
        }
        var source = shipments.find(tenant, shipment).orElseThrow();
        if (!List.of("DRAFT", "SUBMITTED").contains(source.status().name())) throw new BizException("FBA_LABEL_STATUS_INVALID", "当前备货单不允许登记箱标");
        if (!source.amazonShipmentId().startsWith("LOCAL-") && !source.amazonShipmentId().equals(amazon)) {
            throw new BizException("FBA_LABEL_SHIPMENT_MISMATCH", "Amazon 货件号与当前备货单不符");
        }
        var other = shipments.findByAmazonShipmentId(tenant, amazon);
        if (other.isPresent() && !other.get().fbaShipmentId().equals(shipment)) throw new BizException("FBA_LABEL_SHIPMENT_MISMATCH", "Amazon 货件号已属于其他备货单");
        var registration = new FbaBoxLabelMapper.Registration(shipment, amazon, normalized.size(), operator,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        if (labels.insert(tenant, registration) != 1) throw new BizException("FBA_LABEL_PLAN_CONFLICT", "货件箱号登记冲突，请刷新核对");
        for (String number : normalized) {
            if (labels.insertNumber(tenant, shipment, number) != 1) throw new BizException("FBA_LABEL_NUMBER_CONFLICT", "FBA 箱号已属于其他货件");
        }
        labels.updateShipment(tenant, shipment, amazon, normalized.size());
        return find(tenant, shipment);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan find(String tenant, String shipment) {
        var r = labels.find(tenant, shipment);
        return r == null ? null : new Plan(shipment, r.amazonShipmentId(), labels.numbers(tenant, shipment), r.registeredBy(), r.registeredAt());
    }
}
