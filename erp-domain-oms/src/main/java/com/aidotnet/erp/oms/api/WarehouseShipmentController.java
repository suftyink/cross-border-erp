package com.aidotnet.erp.oms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.oms.application.WarehouseShipmentService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/oms/api/in/v1/orders/{orderId}/warehouse-shipments")
public class WarehouseShipmentController {
    private final WarehouseShipmentService service;
    public WarehouseShipmentController(WarehouseShipmentService service) { this.service = service; }
    @GetMapping
    public Result<WarehouseShipmentService.Fulfillment> get(@PathVariable String orderId, Authentication auth) {
        return Result.ok(service.get(current(auth), orderId));
    }
    @PostMapping("/refresh")
    @RequirePermission("oms:warehouse:write")
    public Result<WarehouseShipmentService.Fulfillment> refresh(@PathVariable String orderId, Authentication auth) {
        return Result.ok(service.refresh(current(auth), orderId));
    }
    private String current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user.tenantId();
    }
}
