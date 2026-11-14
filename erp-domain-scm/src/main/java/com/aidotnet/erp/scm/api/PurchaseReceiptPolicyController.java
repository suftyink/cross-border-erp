package com.aidotnet.erp.scm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.scm.application.PurchaseAmendmentService;
import com.aidotnet.erp.scm.application.PurchaseAmendmentService.Revision;
import com.aidotnet.erp.scm.application.PurchaseService;
import com.aidotnet.erp.scm.domain.PurchaseAmendmentLine;
import com.aidotnet.erp.scm.domain.PurchaseOrder;
import com.aidotnet.erp.scm.domain.SupplierProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/scm/api/in/v1")
public class PurchaseReceiptPolicyController {

    private static final String CONFIGURE = "scm:supplier:tolerance:write";
    private static final String AMEND = "scm:purchase:amend";
    private final PurchaseService purchases;
    private final PurchaseAmendmentService amendments;

    public PurchaseReceiptPolicyController(PurchaseService purchases, PurchaseAmendmentService amendments) {
        this.purchases = purchases;
        this.amendments = amendments;
    }

    @GetMapping("/purchase-receipt-permissions")
    public Result<ReceiptPermissions> permissions(Authentication authentication) {
        currentUser(authentication);
        return Result.ok(new ReceiptPermissions(hasPermission(authentication, CONFIGURE), hasPermission(authentication, AMEND)));
    }

    @PatchMapping("/suppliers/{supplierId}/receipt-tolerance")
    @RequirePermission(CONFIGURE)
    public Result<SupplierProfile> updateTolerance(@PathVariable String supplierId,
            @Valid @RequestBody ToleranceRequest request, Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(purchases.updateSupplierReceiptTolerance(user.tenantId(), supplierId, request.percent(), user.userId()));
    }

    @PostMapping("/purchase-orders/{poId}/amendments")
    @RequirePermission(AMEND)
    public Result<PurchaseOrder> amend(@PathVariable String poId, @Valid @RequestBody AmendmentRequest request,
                                      Authentication authentication) {
        ErpUserDetails user = currentUser(authentication);
        return Result.ok(amendments.amend(user.tenantId(), poId, user.userId(), request.reason().strip(),
                request.lines().stream().map(line -> new Revision(line.lineId(), line.quantity())).toList()));
    }

    @GetMapping("/purchase-orders/{poId}/amendments")
    public Result<List<PurchaseAmendmentLine>> history(@PathVariable String poId, Authentication authentication) {
        return Result.ok(amendments.history(currentUser(authentication).tenantId(), poId));
    }

    private boolean hasPermission(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("ROLE_" + permission) || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }

    private ErpUserDetails currentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("采购操作需要使用当前租户的登录账号");
        }
        return user;
    }

    public record ReceiptPermissions(boolean canConfigure, boolean canAmend) {}
    public record ToleranceRequest(@NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                                   BigDecimal percent) {}
    public record AmendmentRequest(@NotBlank @Size(max = 500) String reason,
                                   @NotEmpty List<@NotNull @Valid AmendmentLineRequest> lines) {}
    public record AmendmentLineRequest(@NotBlank @Size(max = 64) String lineId, @Positive int quantity) {}
}
