package com.aidotnet.erp.oms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.SalesOrderPicking;
import com.aidotnet.erp.oms.application.OrderWarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/oms/api/in/v1/orders")
public class OrderWarehouseController {
    private final OrderWarehouseService service;
    public OrderWarehouseController(OrderWarehouseService service) { this.service = service; }
    @GetMapping("/warehouse-work-access")
    public Result<Access> access(Authentication auth) {
        boolean allowed = auth != null && auth.getDetails() instanceof ErpUserDetails user
                && user.tenantId().equals(TenantContext.getTenantId())
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_oms:warehouse:write")
                    || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        return Result.ok(new Access(allowed));
    }
    @PostMapping("/{orderId}/warehouse-work")
    @RequirePermission("oms:warehouse:write")
    public Result<SalesOrderPicking.Work> dispatch(@PathVariable String orderId, @Valid @RequestBody Request request, Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return Result.ok(service.dispatch(user.tenantId(), orderId, request.warehouseId(), user.userId(),
                Boolean.TRUE.equals(request.allowBatchSubstitution())));
    }
    public record Request(@NotBlank @Size(max = 64) String warehouseId, Boolean allowBatchSubstitution) {}
    public record Access(boolean canDispatch) {}
}
