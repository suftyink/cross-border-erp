package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.pdm.SkuCatalog;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.pdm.application.SkuDraftService;
import com.aidotnet.erp.pdm.application.SkuDraftService.SkuDraftView;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.application.SkuLifecycleService;
import com.aidotnet.erp.pdm.application.SkuBatchService;
import com.aidotnet.erp.pdm.infrastructure.SkuHistoryStore;
import com.aidotnet.erp.pdm.infrastructure.SkuMigrationIssueStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SkuLifecycleService lifecycle;
    private final SkuCatalog catalog;
    private final SkuBatchService batches;
    private final SkuHistoryStore history;
    private final SkuMigrationIssueStore issues;

    public SkuDraftController(SkuDraftService drafts, SkuLifecycleService lifecycle, SkuCatalog catalog,
                              SkuBatchService batches, SkuHistoryStore history,
                              SkuMigrationIssueStore issues) {
        this.drafts = drafts;
        this.lifecycle = lifecycle;
        this.catalog = catalog;
        this.batches = batches;
        this.history = history;
        this.issues = issues;
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

    @PostMapping("/{skuId}/enable")
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> enable(@PathVariable String skuId) {
        return Result.ok(drafts.enable(currentTenant(), skuId));
    }

    @PostMapping("/{skuId}/disable")
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> disable(@PathVariable String skuId) {
        return Result.ok(drafts.disable(currentTenant(), skuId));
    }

    @DeleteMapping("/{skuId}")
    @RequirePermission("pdm:product:write")
    public Result<Void> delete(@PathVariable String skuId) {
        lifecycle.delete(currentTenant(), skuId);
        return Result.ok(null);
    }

    @GetMapping("/eligible")
    public Result<List<SkuCatalog.Item>> eligible(@RequestParam(required = false) String keyword) {
        return Result.ok(catalog.findActive(currentTenant(), keyword));
    }

    @PostMapping("/validate")
    public Result<List<SkuCatalog.Check>> validate(@Valid @RequestBody ValidateRequest request) {
        return Result.ok(catalog.validate(currentTenant(), request.selections().stream()
                .map(selection -> new SkuCatalog.Selection(selection.skuId(), selection.sellerSku())).toList()));
    }

    @PostMapping("/batch-status")
    @RequirePermission("pdm:product:write")
    public Result<List<SkuBatchService.Outcome>> batch(@Valid @RequestBody BatchRequest request) {
        return Result.ok(batches.change(currentTenant(), request.skuIds(), request.action(), request.reason()));
    }

    @PostMapping("/save-and-enable")
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> createAndEnable(@Valid @RequestBody CreateDraftRequest request) {
        NewSpuRequest parent = request.newSpu();
        return Result.ok(drafts.createAndEnable(currentTenant(), new SkuDraftService.CreateDraft(request.spuId(), request.sellerSku(),
                request.title(), request.baseUnit(), parent == null ? null : new SkuDraftService.NewSpu(parent.sku(), parent.title(),
                parent.description(), parent.categoryId(), parent.brandId()))));
    }

    @PutMapping("/{skuId}/save-and-enable")
    @RequirePermission("pdm:product:write")
    public Result<SkuDraftView> updateAndEnable(@PathVariable String skuId, @Valid @RequestBody UpdateDraftRequest request) {
        return Result.ok(drafts.updateAndEnable(currentTenant(), skuId, new SkuDraftService.UpdateDraft(
                request.title(), request.baseUnit(), request.sellerSku(), request.spuId())));
    }

    @GetMapping("/{skuId}/history")
    public Result<List<SkuHistoryStore.Entry>> history(@PathVariable String skuId) {
        return Result.ok(history.list(currentTenant(), skuId));
    }

    @GetMapping("/migration-issues")
    public Result<List<SkuMigrationIssueStore.Issue>> migrationIssues() {
        return Result.ok(issues.list(currentTenant()));
    }

    private String currentTenant() { return PdmTenant.current(); }

    public record CreateDraftRequest(@Size(max = 64) String spuId, @Size(max = 128) String sellerSku,
                                      @Size(max = 512) String title, @Size(max = 32) String baseUnit,
                                      @Valid NewSpuRequest newSpu) {}
    public record NewSpuRequest(@Size(max = 128) String sku, @Size(max = 512) String title,
                                String description, @Size(max = 64) String categoryId, @Size(max = 64) String brandId) {}
    public record UpdateDraftRequest(@Size(max = 512) String title, @Size(max = 32) String baseUnit,
                                      @Size(max = 128) String sellerSku, @Size(max = 64) String spuId) {}
    public record SkuAccess(boolean canMaintain) {}
    public record SelectionRequest(@Size(max = 64) String skuId, @Size(max = 128) String sellerSku) {}
    public record ValidateRequest(@NotEmpty @Size(max = 200) List<@NotNull @Valid SelectionRequest> selections) {}
    public record BatchRequest(@NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 64) String> skuIds,
                                @NotBlank String action, @Size(max = 512) String reason) {}
}
