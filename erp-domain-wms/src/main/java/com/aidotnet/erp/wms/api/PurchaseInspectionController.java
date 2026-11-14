package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.PurchaseInspectionService;
import com.aidotnet.erp.wms.application.PurchaseInspectionService.Detail;
import com.aidotnet.erp.wms.domain.PurchaseInspection.RecheckMethod;
import com.aidotnet.erp.wms.domain.PurchaseInspectionBatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wms/api/in/v1/purchase-inspections")
public class PurchaseInspectionController {
    private static final String WRITE = "wms:inspection:write";
    private final PurchaseInspectionService inspections;

    public PurchaseInspectionController(PurchaseInspectionService inspections) {
        this.inspections = inspections;
    }

    @GetMapping("/work")
    public Result<Work> work(@RequestParam(required = false) String purchaseOrderId, Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        boolean allowed = authentication.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_" + WRITE) || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
        return Result.ok(new Work(allowed, allowed ? inspections.work(user.tenantId(), purchaseOrderId) : List.of()));
    }

    @GetMapping("/{batchNo}")
    public Result<Detail> detail(@PathVariable UUID batchNo, Authentication authentication) {
        return Result.ok(inspections.detail(currentUser(authentication).tenantId(), batchNo.toString()));
    }

    @PostMapping("/{batchNo}/sample")
    @RequirePermission(WRITE)
    public Result<Detail> sample(@PathVariable UUID batchNo, @Valid @RequestBody SampleRequest request,
                                 Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(inspections.sample(user.tenantId(), batchNo.toString(), user.userId(),
                request.checkedQuantity(), request.passedQuantity(), request.failedQuantity()));
    }

    @PostMapping("/{batchNo}/recheck")
    @RequirePermission(WRITE)
    public Result<Detail> recheck(@PathVariable UUID batchNo, @Valid @RequestBody RecheckRequest request,
                                  Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(inspections.recheck(user.tenantId(), batchNo.toString(), user.userId(), request.method(),
                request.checkedQuantity(), request.passedQuantity(), request.failedQuantity()));
    }

    @PostMapping("/{batchNo}/disposition")
    @RequirePermission(WRITE)
    public Result<Detail> dispose(@PathVariable UUID batchNo, @Valid @RequestBody DispositionRequest request,
                                  Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(inspections.dispose(user.tenantId(), batchNo.toString(), user.userId(),
                request.goodQuantity(), request.defectiveQuantity()));
    }

    private ErpUserDetails currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("质检查询和操作需要使用当前租户的登录账号");
        }
        return user;
    }

    public record Work(boolean canInspect, List<PurchaseInspectionBatch> batches) {}

    public record SampleRequest(@NotNull @Positive Integer checkedQuantity,
                                @NotNull @PositiveOrZero Integer passedQuantity,
                                @NotNull @PositiveOrZero Integer failedQuantity) {}

    public record RecheckRequest(@NotNull RecheckMethod method, @NotNull @Positive Integer checkedQuantity,
                                 @NotNull @PositiveOrZero Integer passedQuantity,
                                 @NotNull @PositiveOrZero Integer failedQuantity) {}

    public record DispositionRequest(@NotNull @PositiveOrZero Integer goodQuantity,
                                     @NotNull @PositiveOrZero Integer defectiveQuantity) {}
}
