package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.pdm.application.ProductImageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/pdm/api/in/v1/images")
public class ProductImageController {
    private final ProductImageService images;
    public ProductImageController(ProductImageService images) {this.images=images;}
    @PostMapping("/upload") @RequirePermission("pdm:product:write")
    public Result<ProductImageService.UploadedImage> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String tenant=PdmTenant.current();
        String actor=((ErpUserDetails)SecurityContextHolder.getContext().getAuthentication().getDetails()).userId();
        return Result.ok(images.upload(tenant,actor,file));
    }
    @GetMapping("/{assetId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String assetId) throws Exception {
        var asset=images.asset(PdmTenant.current(),assetId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.contentType())).contentLength(asset.sizeBytes())
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .body(new InputStreamResource(images.read(asset)));
    }
    @GetMapping("/{assetId}/url")
    public Result<ProductImageService.ResolvedImage> resolve(@PathVariable String assetId,@RequestParam(defaultValue="300") int expirySeconds) throws Exception {
        return Result.ok(images.resolve(PdmTenant.current(),assetId,expirySeconds));
    }
}
