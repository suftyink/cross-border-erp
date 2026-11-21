package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.pdm.application.SkuDraftService;
import com.aidotnet.erp.pdm.application.SkuDraftService.SkuDraftView;
import com.aidotnet.erp.pdm.domain.SkuCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pdm/api/in/v1/skus")
public class SkuDraftController {
    private final SkuDraftService drafts;

    public SkuDraftController(SkuDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    public Result<List<SkuDraftView>> list(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String spuId,
                                           @RequestParam(required = false) String status) {
        return Result.ok(drafts.list(currentTenant(), keyword, spuId, status));
    }

    @GetMapping("/access")
    public Result<SkuAccess> access() {
        currentTenant();
        boolean canMaintain = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_pdm:product:write")
                        || authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
        return Result.ok(new SkuAccess(canMaintain));
    }

    @GetMapping("/generated-code")
    @RequirePermission("pdm:product:write")
    public Result<String> generateCode() {
        currentTenant();
        return Result.ok(SkuCode.generate());
    }

    @GetMapping("/{skuId}")
    public Result<SkuDraftView> get(@PathVariable String skuId) {
        return Result.ok(drafts.get(currentTenant(), skuId));
    }

    @PostMapping
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> create(@Valid @RequestBody CreateDraftRequest request) {
        NewSpuRequest parent = request.newSpu();
        return Result.ok(drafts.create(currentTenant(), new SkuDraftService.CreateDraft(request.spuId(),
                request.sellerSku(), request.title(), request.baseUnit(), parent == null ? null
                : new SkuDraftService.NewSpu(parent.sku(), parent.title(), parent.description(), parent.categoryId(), parent.brandId()))));
    }

    @PutMapping("/{skuId}")
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> update(@PathVariable String skuId, @Valid @RequestBody UpdateDraftRequest request) {
        return Result.ok(drafts.update(currentTenant(), skuId, new SkuDraftService.UpdateDraft(
                request.title(), request.baseUnit(), request.sellerSku(), request.spuId())));
    }

    private String currentTenant() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)) {
            throw new BizException("UNAUTHORIZED", "请先登录");
        }
        if (!user.tenantId().equals(TenantContext.getTenantId())) {
            throw new AccessDeniedException("登录账号与当前租户不一致");
        }
        return user.tenantId();
    }

    public record CreateDraftRequest(@Size(max = 64) String spuId, @Size(max = 128) String sellerSku,
                                      @Size(max = 512) String title, @Size(max = 32) String baseUnit,
                                      @Valid NewSpuRequest newSpu) {}
    public record NewSpuRequest(@Size(max = 128) String sku, @Size(max = 512) String title,
                                String description, @Size(max = 64) String categoryId, @Size(max = 64) String brandId) {}
    public record UpdateDraftRequest(@Size(max = 512) String title, @Size(max = 32) String baseUnit,
                                      @Size(max = 128) String sellerSku, @Size(max = 64) String spuId) {}
    public record SkuAccess(boolean canMaintain) {}
}
