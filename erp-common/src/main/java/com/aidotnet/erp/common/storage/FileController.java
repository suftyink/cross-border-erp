package com.aidotnet.erp.common.storage;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.security.ErpUserDetails;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

@RestController
@RequestMapping("/platform/sys/api/in/v1/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private final MinioStorageService storageService;

    public FileController(MinioStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "module", defaultValue = "general") String module) throws Exception {
        String tenantId = currentTenant();
        if(!module.matches("[A-Za-z0-9_-]{1,64}") || module.equals("pdm-images")) throw new BizException("FILE_MODULE_INVALID","模块名无效；商品图片请使用商品上传入口");
        String objectName = storageService.uploadTenantFile(module, file.getOriginalFilename(),
                file.getInputStream(), file.getSize(), file.getContentType());
        String presignedUrl = storageService.getPresignedUrl(objectName, Duration.ofHours(1));
        log.info("File uploaded: tenant={}, module={}, object={}", tenantId, module, objectName);
        return Result.ok(Map.of(
                "objectName", objectName,
                "url", presignedUrl,
                "module", module,
                "originalName", file.getOriginalFilename() != null ? file.getOriginalFilename() : ""
        ));
    }

    @GetMapping("/download/{*objectName}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String objectName) throws Exception {
        objectName=ownedObject(objectName);
        InputStream stream = storageService.download(objectName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(objectName.substring(objectName.lastIndexOf('/')+1),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }

    @GetMapping("/presigned-url/{*objectName}")
    public Result<Map<String, String>> getPresignedUrl(
            @PathVariable String objectName,
            @RequestParam(value = "expiryMinutes", defaultValue = "60") int expiryMinutes) throws Exception {
        objectName=ownedObject(objectName);
        if(expiryMinutes<1 || expiryMinutes>60) throw new BizException("FILE_EXPIRY_INVALID","地址有效期应为 1 至 60 分钟");
        String url = storageService.getPresignedUrl(objectName, Duration.ofMinutes(expiryMinutes));
        return Result.ok(Map.of("url", url, "objectName", objectName));
    }

    @DeleteMapping("/{*objectName}")
    public Result<Void> delete(@PathVariable String objectName) throws Exception {
        objectName=ownedObject(objectName);
        if(objectName.contains("/pdm-images/")) throw new BizException("FILE_MANAGED_OBJECT","商品图片保留供历史引用，移除图片请在商品资料中解除引用");
        storageService.delete(objectName);
        log.info("File deleted: object={}", objectName);
        return Result.ok(null);
    }
    private String currentTenant() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getDetails() instanceof ErpUserDetails user)) throw new BizException("UNAUTHORIZED","请先登录");
        if(!user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("登录账号与租户不一致");
        return user.tenantId();
    }
    private String ownedObject(String name) {
        String tenant=currentTenant(),key=name.startsWith("/")?name.substring(1):name;
        if(!key.startsWith(tenant+"/") || key.contains("\\") || java.util.Arrays.stream(key.split("/",-1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) {
            throw new AccessDeniedException("文件不属于当前租户或路径无效");
        }
        return key;
    }
}
