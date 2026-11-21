package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.pdm.ProductData;
import com.aidotnet.erp.common.pdm.SkuCatalog;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.pdm.application.SkuDetailsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pdm/api/in/v1/skus/{skuId}")
public class SkuDetailsController {
    private final SkuDetailsService details;
    private final SkuCatalog catalog;
    public SkuDetailsController(SkuDetailsService details,SkuCatalog catalog) { this.details=details;this.catalog=catalog; }
    @GetMapping("/details")
    public Result<ProductData> get(@PathVariable String skuId) { return Result.ok(details.get(PdmTenant.current(),skuId)); }
    @PutMapping("/details") @RequirePermission("pdm:product:write")
    public Result<ProductData> replace(@PathVariable String skuId,@Valid @RequestBody ProductData data) {
        return Result.ok(details.replace(PdmTenant.current(),skuId,data));
    }
    @GetMapping("/readiness")
    public Result<SkuCatalog.Check> readiness(@PathVariable String skuId,@RequestParam String usage) {
        String tenant=PdmTenant.current();SkuCatalog.Usage stage;
        try {stage=SkuCatalog.Usage.valueOf(usage);} catch(IllegalArgumentException error) {throw new BizException("USAGE_INVALID","使用阶段无效");}
        return Result.ok(catalog.checkUsage(tenant,java.util.List.of(new SkuCatalog.Selection(skuId,null)),stage).get(0));
    }
}
