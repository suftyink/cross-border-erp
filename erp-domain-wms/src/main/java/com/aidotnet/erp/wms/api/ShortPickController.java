package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.ShortPickService;
import com.aidotnet.erp.wms.domain.BatchStock;
import com.aidotnet.erp.wms.domain.ShortPickCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/short-picks")
public class ShortPickController {
    private static final String PICK = "wms:pick:write";
    private static final String COUNT = "wms:stock-review:write";
    private static final String APPROVE = "wms:stock-review:approve";
    private final ShortPickService service;
    public ShortPickController(ShortPickService service) { this.service = service; }

    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String outboundId, Authentication auth) {
        var user = current(auth);
        boolean pick = allowed(auth, PICK), count = allowed(auth, COUNT), approve = allowed(auth, APPROVE);
        return Result.ok(new Work(pick, count, approve, pick || count || approve ? service.list(user.tenantId(), outboundId) : List.of()));
    }
    @GetMapping("/{id}")
    public Result<ShortPickCase> get(@PathVariable UUID id, Authentication auth) {
        var user = current(auth);
        if (!allowed(auth, PICK) && !allowed(auth, COUNT) && !allowed(auth, APPROVE)) throw new AccessDeniedException("无缺拣复核查询权限");
        return Result.ok(service.get(user.tenantId(), id.toString()));
    }
    @PostMapping("/outbounds/{outboundId}")
    @RequirePermission(PICK)
    public Result<ShortPickCase> report(@PathVariable UUID outboundId, @Valid @RequestBody Report request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.report(user.tenantId(), outboundId.toString(), user.userId(),
                new ShortPickService.Report(request.shortPickId().toString(), request.allocationId().toString(), request.quantity(), request.reason())));
    }
    @GetMapping("/{id}/replacement-candidates")
    @RequirePermission(PICK)
    public Result<List<BatchStock>> candidates(@PathVariable UUID id, Authentication auth) {
        return Result.ok(service.candidates(current(auth).tenantId(), id.toString()));
    }
    @PostMapping("/{id}/replace")
    @RequirePermission(PICK)
    public Result<ShortPickCase> replace(@PathVariable UUID id, @Valid @RequestBody Replacement request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.replace(user.tenantId(), id.toString(), user.userId(),
                new ShortPickService.Replacement(request.requestId().toString(), request.locationCode(), request.batchNo().toString())));
    }
    @PostMapping("/{id}/count")
    @RequirePermission(COUNT)
    public Result<ShortPickCase> count(@PathVariable UUID id, @Valid @RequestBody Count request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.count(user.tenantId(), id.toString(), user.userId(), request.foundQuantity(), request.missingQuantity(), request.remark()));
    }
    @PostMapping("/{id}/confirm")
    @RequirePermission(APPROVE)
    public Result<ShortPickCase> confirm(@PathVariable UUID id, @Valid @RequestBody Count request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.confirm(user.tenantId(), id.toString(), user.userId(), request.foundQuantity(), request.missingQuantity()));
    }
    private boolean allowed(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + permission) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Work(boolean canReport, boolean canCount, boolean canApprove, List<ShortPickCase> cases) {}
    public record Report(@NotNull UUID shortPickId, @NotNull UUID allocationId, @Positive int quantity, @NotBlank @Size(max = 500) String reason) {}
    public record Replacement(@NotNull UUID requestId, @NotBlank @Size(max = 128) String locationCode, @NotNull UUID batchNo) {}
    public record Count(@NotNull @PositiveOrZero Integer foundQuantity, @NotNull @PositiveOrZero Integer missingQuantity, @Size(max = 500) String remark) {}
}
