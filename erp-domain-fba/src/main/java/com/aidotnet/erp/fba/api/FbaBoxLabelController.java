package com.aidotnet.erp.fba.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.fba.FbaBoxLabels;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.fba.application.FbaBoxLabelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fba/api/in/v1/shipments/{shipmentId}/box-labels")
public class FbaBoxLabelController {
    private final FbaBoxLabelService service;
    public FbaBoxLabelController(FbaBoxLabelService service) { this.service = service; }
    @GetMapping
    public Result<FbaBoxLabels.Plan> get(@PathVariable UUID shipmentId, Authentication auth) {
        var user = current(auth);
        if (auth.getAuthorities().stream().noneMatch(a -> List.of("ROLE_SUPER_ADMIN", "ROLE_fba:label:write", "ROLE_fba:warehouse:write",
                "ROLE_wms:fba-bind:write", "ROLE_wms:fba-ship:write", "ROLE_wms:pick:write", "ROLE_wms:carton:write").contains(a.getAuthority()))) throw new AccessDeniedException("没有箱标查询权限");
        return Result.ok(service.find(user.tenantId(), shipmentId.toString()));
    }
    @PostMapping
    @RequirePermission("fba:label:write")
    public Result<FbaBoxLabels.Plan> register(@PathVariable UUID shipmentId, @Valid @RequestBody Register request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.register(user.tenantId(), shipmentId.toString(), user.userId(), request.amazonShipmentId(), request.boxNumbers()));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user) || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Register(@NotBlank @Size(max = 128) String amazonShipmentId,
                           @NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._-]+") String> boxNumbers) {}
}
