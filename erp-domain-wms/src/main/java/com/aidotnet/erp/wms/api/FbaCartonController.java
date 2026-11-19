package com.aidotnet.erp.wms.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.common.tenant.TenantContext;
import com.aidotnet.erp.wms.application.FbaCartonService;
import com.aidotnet.erp.wms.infrastructure.mapper.FbaCartonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wms/api/in/v1/fba-cartons")
public class FbaCartonController {
    private final FbaCartonService service;
    public FbaCartonController(FbaCartonService service) { this.service = service; }

    @GetMapping
    public Result<CartonList> list(@RequestParam(required = false) String outboundId,
            @RequestParam(required = false) String cartonCode, @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String purchaseOrderId, Authentication auth) {
        var user = current(auth);
        boolean pack = permitted(auth, "wms:carton:write");
        boolean bind = permitted(auth, "wms:fba-bind:write");
        boolean read = pack || bind || permitted(auth, "wms:fba-ship:write") || permitted(auth, "fba:label:write")
                || permitted(auth, "wms:pick:write") || permitted(auth, "fba:warehouse:write");
        return Result.ok(new CartonList(pack, bind, read ? service.list(user.tenantId(),
                new FbaCartonMapper.Filter(outboundId, cartonCode, batchNo, purchaseOrderId)) : List.of()));
    }

    @PostMapping("/{outboundId}")
    @RequirePermission("wms:carton:write")
    public Result<FbaCartonService.Carton> pack(@PathVariable UUID outboundId, @Valid @RequestBody Pack request, Authentication auth) {
        var user = current(auth);
        return Result.ok(service.pack(user.tenantId(), outboundId.toString(), user.userId(), new FbaCartonService.Command(
                request.requestId().toString(), request.cartonCode(), request.lines().stream().map(l ->
                new FbaCartonService.PackLine(l.pickId().toString(), l.sellerSku(), l.batchNo().toString(), l.quantity())).toList())));
    }
    private boolean permitted(Authentication auth, String permission) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + permission) || a.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
    private ErpUserDetails current(Authentication auth) {
        if (auth == null || !(auth.getDetails() instanceof ErpUserDetails user)
                || !user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("请使用当前租户的登录账号");
        return user;
    }
    public record Pack(@NotNull UUID requestId, @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]*") String cartonCode,
                       @NotEmpty @Size(max = 200) List<@NotNull @Valid Line> lines) {}
    public record Line(@NotNull UUID pickId, @NotBlank @Size(max = 128) String sellerSku, @NotNull UUID batchNo, @Positive int quantity) {}
    public record CartonList(boolean canPack, boolean canBind, List<FbaCartonService.Carton> cartons) {}
}
