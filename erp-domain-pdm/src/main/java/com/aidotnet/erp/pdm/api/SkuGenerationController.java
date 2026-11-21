package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.pdm.application.SkuGenerationService;
import com.aidotnet.erp.pdm.domain.SkuGeneration.Preview;
import com.aidotnet.erp.pdm.domain.SkuGeneration.RequestedRow;
import com.aidotnet.erp.pdm.domain.Specification.Range;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pdm/api/in/v1/products/{spuId}/sku-generation")
public class SkuGenerationController {
    private final SkuGenerationService service;
    public SkuGenerationController(SkuGenerationService service) { this.service = service; }

    @PostMapping("/preview")
    @RequirePermission("pdm:product:write")
    public Result<Preview> preview(@PathVariable String spuId, @Valid @RequestBody PreviewRequest request) {
        String tenant = PdmTenant.current();
        return Result.ok(service.preview(tenant, actor(), spuId, request.dimensions() == null ? null
                : request.dimensions().stream().map(d -> new Range(d.attributeId(), d.valueIds())).toList()));
    }
    @PostMapping("/commit")
    @RequirePermission("pdm:product:write")
    public Result<SkuGenerationService.GenerationResult> commit(@PathVariable String spuId, @Valid @RequestBody CommitRequest request) {
        String tenant = PdmTenant.current();
        return Result.ok(service.commit(tenant, actor(), spuId, request.previewId(), request.rows().stream()
                .map(row -> new RequestedRow(row.combinationKey(), row.sellerSku(), row.title(), row.baseUnit())).toList()));
    }
    private String actor() { return ((ErpUserDetails) SecurityContextHolder.getContext().getAuthentication().getDetails()).userId(); }
    public record PreviewRequest(@Size(max = 20) List<@NotNull @Valid DimensionRequest> dimensions) {}
    public record DimensionRequest(@NotBlank @Size(max = 64) String attributeId,
                                   @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 64) String> valueIds) {}
    public record CommitRequest(@NotBlank @Size(max = 64) String previewId,
                                 @NotEmpty @Size(max = 200) List<@NotNull @Valid RowRequest> rows) {}
    public record RowRequest(@NotBlank @Size(max = 4096) String combinationKey, @Size(max = 128) String sellerSku,
                              @Size(max = 512) String title, @Size(max = 32) String baseUnit) {}
}
