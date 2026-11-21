package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.security.ErpUserDetails;
import com.aidotnet.erp.common.tenant.TenantContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

final class PdmTenant {
    private PdmTenant() {}

    static String current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof ErpUserDetails user)) {
            throw new BizException("UNAUTHORIZED", "请先登录");
        }
        if (!user.tenantId().equals(TenantContext.getTenantId())) throw new AccessDeniedException("登录账号与当前租户不一致");
        return user.tenantId();
    }
}
