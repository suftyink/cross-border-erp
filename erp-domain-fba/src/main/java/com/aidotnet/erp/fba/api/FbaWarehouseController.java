package com.aidotnet.erp.fba.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.SalesOrderPicking.Work;
import com.aidotnet.erp.fba.application.FbaWarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fba/api/in/v1")
public class FbaWarehouseController {
    private final FbaWarehouseService service;
    public FbaWarehouseController(FbaWarehouseService service) { this.service = service; }

    @GetMapping("/warehouse-work")
    public Result<WorkList> list(Authentication auth) {
        var user = current(auth);
        boolean dispatch = permitted(auth, "fba:warehouse:write");
        boolean read = dispatch || permitted(auth, "wms:pick:write") || permitted(auth, "wms:carton:write");
        return Result.ok(new WorkList(dispatch, read ? service.list(user.tenantId()) : List.of()));
    }

    @GetMapping("/shipments/{shipmentId}/warehouse-work")
    @RequirePermission("fba:warehouse:write")
    public Result<Work> get(@PathVariable UUID shipmentId, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.get(user.tenantId(), shipmentId.toString()));
    }

    @PostMapping("/shipments/{shipmentId}/warehouse-work")
    @RequirePermission("fba:warehouse:write")
    public Result<Work> dispatch(@PathVariable UUID shipmentId, @Valid @RequestBody Dispatch request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.dispatch(user.tenantId(), shipmentId.toString(), request.warehouseId().toString(), user.userId()));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    private boolean permitted(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + permission) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    public record WorkList(boolean canDispatch, List<FbaWarehouseService.Row> orders) {}
    public record Dispatch(@NotNull UUID warehouseId) {}
}
