package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.FbaCartonBindingService;
import com.aidotnet.erp.wms.application.FbaDispatchService;
import com.aidotnet.erp.wms.application.FbaShipmentDelivery;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaDispatchMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/fba-dispatch")
public class FbaDispatchController {
    private final FbaCartonBindingService bindings;
    private final FbaDispatchService dispatch;
    private final FbaShipmentDelivery delivery;
    public FbaDispatchController(FbaCartonBindingService bindings, FbaDispatchService dispatch, FbaShipmentDelivery delivery) {
        this.bindings = bindings; this.dispatch = dispatch; this.delivery = delivery;
    }
    @GetMapping("/{outboundId}")
    public Result<Work> get(@PathVariable UUID outboundId, Authentication auth) {
        var user = current(auth);
        if (auth.getAuthorities().stream().noneMatch(a -> java.util.List.of("ROLE_SUPER_ADMIN", "ROLE_wms:fba-bind:write", "ROLE_wms:fba-ship:write",
                "ROLE_wms:pick:write", "ROLE_wms:carton:write", "ROLE_fba:warehouse:write", "ROLE_fba:label:write").contains(a.getAuthority()))) throw new AccessDeniedException("没有 FBA 出库查询权限");
        return Result.ok(new Work(allowed(auth, "wms:fba-bind:write"), allowed(auth, "wms:fba-ship:write"), dispatch.prepare(user.tenantId(), outboundId.toString())));
    }
    @PostMapping("/{outboundId}/ship")
    @RequirePermission("wms:fba-ship:write")
    public Result<FbaDispatchMapper.DispatchRecord> ship(@PathVariable UUID outboundId, @Valid @RequestBody Ship request, Authentication auth) {
        var user = current(auth);
        dispatch.ship(user.tenantId(), outboundId.toString(), user.userId(), request.carrierCode(), request.trackingNo());
        return Result.ok(delivery.sync(user.tenantId(), outboundId.toString()));
    }
    @PostMapping("/{outboundId}/sync")
    @RequirePermission("wms:fba-ship:write")
    public Result<FbaDispatchMapper.DispatchRecord> sync(@PathVariable UUID outboundId, Authentication auth) {
        return Result.ok(delivery.sync(current(auth).tenantId(), outboundId.toString()));
    }
    @PostMapping("/{outboundId}/bindings")
    @RequirePermission("wms:fba-bind:write")
    public Result<Void> bind(@PathVariable UUID outboundId, @Valid @RequestBody Bind request, Authentication auth) {
        var user = current(auth);
        bindings.bind(user.tenantId(), outboundId.toString(), user.userId(), request.cartonCode(), request.fbaBoxNumber());
        return Result.ok(null);
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user) || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    private boolean allowed(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    public record Work(boolean canBind, boolean canShip, FbaDispatchService.Preparation preparation) {}
    public record Ship(@NotBlank @Size(max = 64) String carrierCode, @NotBlank @Size(max = 128) String trackingNo,
                       @NotNull @AssertTrue(message = "请确认整单货物已实际交运") Boolean confirmDispatch) {}
    public record Bind(@NotBlank @Size(max = 64) String cartonCode, @NotBlank @Size(max = 128) String fbaBoxNumber,
                       @NotNull @AssertTrue(message = "请确认已把对应 FBA 箱标贴到该内部箱") Boolean labelApplied) {}
}
