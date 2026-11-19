package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.WarehouseShipments;
import com.aidotnet.erp.wms.application.SalesDispatchService;
import com.aidotnet.erp.wms.application.SalesShipmentDelivery;
import com.aidotnet.erp.wms.infrastructure.mapper.SalesDispatchMapper.TraceFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/sales-dispatch")
public class SalesDispatchController {
    private static final String WRITE = "wms:ship:write";
    private final SalesDispatchService shipments;
    private final SalesShipmentDelivery delivery;
    public SalesDispatchController(SalesDispatchService shipments, SalesShipmentDelivery delivery) {
        this.shipments = shipments;
        this.delivery = delivery;
    }
    @GetMapping("/outbounds/{outboundId}/packages")
    public Result<List<WarehouseShipments.Shipment>> list(@PathVariable UUID outboundId, Authentication auth) {
        return Result.ok(shipments.forOutbound(current(auth).tenantId(), outboundId.toString()));
    }
    @PostMapping("/outbounds/{outboundId}/packages")
    @RequirePermission(WRITE)
    public Result<WarehouseShipments.Shipment> pack(@PathVariable UUID outboundId, @Valid @RequestBody PackRequest request, Authentication auth) {
        return Result.ok(shipments.pack(current(auth).tenantId(), outboundId.toString(), request.packageId().toString(),
                request.lines().stream().map(l -> new SalesDispatchService.PackLine(l.pickId().toString(), l.quantity())).toList()));
    }
    @PostMapping("/packages/{packageId}/weight")
    @RequirePermission(WRITE)
    public Result<WarehouseShipments.Shipment> weigh(@PathVariable UUID packageId, @Valid @RequestBody WeightRequest request, Authentication auth) {
        return Result.ok(shipments.weigh(current(auth).tenantId(), packageId.toString(), request.weightKg()));
    }
    @PostMapping("/packages/{packageId}/ship")
    @RequirePermission(WRITE)
    public Result<WarehouseShipments.Shipment> ship(@PathVariable UUID packageId, @Valid @RequestBody ShipRequest request, Authentication auth) {
        var user = current(auth);
        shipments.ship(user.tenantId(), packageId.toString(), user.userId(), request.carrierCode(), request.trackingNo());
        return Result.ok(delivery.sync(user.tenantId(), packageId.toString()));
    }
    @PostMapping("/packages/{packageId}/sync")
    @RequirePermission(WRITE)
    public Result<WarehouseShipments.Shipment> sync(@PathVariable UUID packageId, Authentication auth) {
        return Result.ok(delivery.sync(current(auth).tenantId(), packageId.toString()));
    }
    @GetMapping("/packages/{packageId}")
    public Result<WarehouseShipments.Shipment> get(@PathVariable UUID packageId, Authentication auth) {
        return Result.ok(shipments.get(current(auth).tenantId(), packageId.toString()));
    }
    @GetMapping("/trace")
    public Result<List<WarehouseShipments.Trace>> trace(@RequestParam(required = false) String purchaseOrderId,
            @RequestParam(required = false) String batchNo, @RequestParam(required = false) String salesOrderId,
            @RequestParam(required = false) String outboundId, @RequestParam(required = false) String packageId,
            @RequestParam(required = false) String warehouseId, @RequestParam(required = false) String locationId,
            @RequestParam(required = false) String sellerSku, Authentication auth) {
        return Result.ok(shipments.trace(current(auth).tenantId(), new TraceFilter(purchaseOrderId, batchNo, salesOrderId,
                outboundId, packageId, warehouseId, locationId, sellerSku)));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record PackRequest(@NotNull UUID packageId, @NotEmpty @Size(max = 200) List<@NotNull @Valid PackLine> lines) {}
    public record PackLine(@NotNull UUID pickId, @Positive int quantity) {}
    public record WeightRequest(@NotNull @DecimalMin("0.001") @Digits(integer = 7, fraction = 3) BigDecimal weightKg) {}
    public record ShipRequest(@NotBlank @Size(max = 64) String carrierCode, @NotBlank @Size(max = 128) String trackingNo) {}
}
