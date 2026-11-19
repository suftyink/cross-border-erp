package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.wms.SalesOrderPicking.Work;
import com.aidotnet.erp.common.wms.SalesOrderPicking.ActualPick;
import com.aidotnet.erp.wms.application.SalesPickingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/sales-picking")
public class SalesPickingController {
    private static final String WRITE = "wms:pick:write";
    private final SalesPickingService service;
    public SalesPickingController(SalesPickingService service) { this.service = service; }

    @GetMapping("/work")
    public Result<WorkList> list(@RequestParam(required = false) String salesOrderId, Authentication auth) {
        ErpUserDetails user = current(auth);
        boolean canPick = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + WRITE)
                || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        boolean canShip = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_wms:ship:write")
                || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        boolean canPack = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_wms:carton:write")
                || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        boolean canFba = fbaReader(auth);
        List<Work> work = canPick || canShip || canPack || canFba ? service.list(user.tenantId(), salesOrderId) : List.of();
        if (!canPick && !canShip && !canPack) work = work.stream().filter(w -> w.sourceType().equals("FBA_SHIPMENT")).toList();
        return Result.ok(new WorkList(canPick, canShip, canPack, canFba, work));
    }
    @GetMapping("/{outboundId}")
    public Result<Work> get(@PathVariable UUID outboundId, Authentication auth) {
        var user = current(auth);
        boolean normalRead = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + WRITE)
                || a.getAuthority().equals("ROLE_wms:ship:write") || a.getAuthority().equals("ROLE_wms:carton:write") || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (!normalRead && !fbaReader(auth)) throw new AccessDeniedException("当前账号没有查看拣货作业的权限");
        var work = service.get(user.tenantId(), outboundId.toString());
        if (!normalRead && !work.sourceType().equals("FBA_SHIPMENT")) throw new AccessDeniedException("当前账号没有查看销售拣货作业的权限");
        return Result.ok(work);
    }
    @PostMapping("/{outboundId}/reallocate")
    @RequirePermission(WRITE)
    public Result<Work> reallocate(@PathVariable UUID outboundId, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.reallocate(user.tenantId(), outboundId.toString(), user.userId()));
    }
    @PostMapping("/{outboundId}/picks")
    @RequirePermission(WRITE)
    public Result<ActualPick> pick(@PathVariable UUID outboundId, @Valid @RequestBody PickRequest request, Authentication auth) {
        ErpUserDetails user = current(auth);
        return Result.ok(service.confirm(user.tenantId(), outboundId.toString(), user.userId(),
                new SalesPickingService.PickCommand(request.requestId().toString(), request.allocationId().toString(),
                        request.locationCode(), request.batchNo().toString(), request.quantity())));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    private boolean fbaReader(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_wms:fba-bind:write") || a.getAuthority().equals("ROLE_wms:fba-ship:write"));
    }
    public record WorkList(boolean canPick, boolean canShip, boolean canPack, boolean canFba, List<Work> orders) {}
    public record PickRequest(@NotNull UUID requestId, @NotNull UUID allocationId,
                              @NotBlank @Size(max = 128) String locationCode, @NotNull UUID batchNo, @Positive int quantity) {}
}
