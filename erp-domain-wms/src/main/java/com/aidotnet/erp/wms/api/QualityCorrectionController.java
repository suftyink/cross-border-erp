package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.QualityCorrectionService;
import com.aidotnet.erp.wms.domain.QualityCorrection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/quality-corrections")
public class QualityCorrectionController {
    private static final String WRITE = "wms:quality-correction:write";
    private static final String APPROVE = "wms:quality-correction:approve";
    private static final String INSPECT = "wms:inspection:write";
    private final QualityCorrectionService service;
    public QualityCorrectionController(QualityCorrectionService service) { this.service = service; }

    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String batchNo, Authentication auth) {
        var user = current(auth);
        boolean correct = allowed(auth, WRITE), approve = allowed(auth, APPROVE), inspect = allowed(auth, INSPECT);
        return Result.ok(new Work(correct, approve, inspect, correct || approve || inspect ? service.list(user.tenantId(), batchNo) : List.of()));
    }
    @GetMapping("/{id}")
    public Result<QualityCorrectionService.Detail> get(@PathVariable UUID id, Authentication auth) {
        var user = current(auth);
        if (!allowed(auth, WRITE) && !allowed(auth, APPROVE) && !allowed(auth, INSPECT)) throw new AccessDeniedException("无质检更正查询权限");
        return Result.ok(service.get(user.tenantId(), id.toString()));
    }
    @PostMapping
    @RequirePermission(WRITE)
    public Result<QualityCorrectionService.Detail> pause(@Valid @RequestBody Pause request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.pause(user.tenantId(), request.correctionId().toString(), request.batchNo().toString(), request.reason(), user.userId()));
    }
    @PostMapping("/{id}/release")
    @RequirePermission(APPROVE)
    public Result<QualityCorrectionService.Detail> release(@PathVariable UUID id, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.release(user.tenantId(), id.toString(), user.userId()));
    }
    @PostMapping("/{id}/reverse-putaway")
    @RequirePermission(WRITE)
    public Result<QualityCorrectionService.Detail> reverse(@PathVariable UUID id, @Valid @RequestBody Reverse request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.reverse(user.tenantId(), id.toString(), user.userId(), new QualityCorrectionService.Reverse(
                request.requestId().toString(), request.putawayTransactionId().toString(), request.locationCode(), request.quantity())));
    }
    @PostMapping("/{id}/unpick")
    @RequirePermission(WRITE)
    public Result<QualityCorrectionService.Detail> unpick(@PathVariable UUID id, @Valid @RequestBody Unpick request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.unpick(user.tenantId(), id.toString(), user.userId(), new QualityCorrectionService.Unpick(
                request.requestId().toString(), request.pickId().toString(), request.locationCode(), request.batchNo().toString())));
    }
    @PostMapping("/{id}/withdraw")
    @RequirePermission(INSPECT)
    public Result<QualityCorrectionService.Detail> withdraw(@PathVariable UUID id, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.withdraw(user.tenantId(), id.toString(), user.userId()));
    }
    @PostMapping("/{id}/inspection")
    @RequirePermission(INSPECT)
    public Result<QualityCorrectionService.Detail> inspect(@PathVariable UUID id, @Valid @RequestBody Inspection request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.inspect(user.tenantId(), id.toString(), user.userId(), request.goodQuantity(), request.defectiveQuantity()));
    }
    private boolean allowed(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + permission) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user) || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Work(boolean canCorrect, boolean canApprove, boolean canInspect, List<QualityCorrection> cases) {}
    public record Pause(@NotNull UUID correctionId, @NotNull UUID batchNo, @NotBlank @Size(max = 500) String reason) {}
    public record Reverse(@NotNull UUID requestId, @NotNull UUID putawayTransactionId, @NotBlank @Size(max = 128) String locationCode, @Positive int quantity) {}
    public record Inspection(@NotNull @PositiveOrZero Integer goodQuantity, @NotNull @PositiveOrZero Integer defectiveQuantity) {}
    public record Unpick(@NotNull UUID requestId, @NotNull UUID pickId, @NotBlank @Size(max = 128) String locationCode, @NotNull UUID batchNo) {}
}
