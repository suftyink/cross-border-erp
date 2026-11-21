package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 每个 SKU 独立提交，失败不会伪报整批成功，也不回滚其他已成功的项目。 */
@Service
public class SkuBatchService {
    private final SkuLifecycleService lifecycle;

    public SkuBatchService(SkuLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    public List<Outcome> change(String tenant, List<String> skuIds, String action, String reason) {
        String operation = action.strip().toUpperCase(Locale.ROOT);
        if (!operation.equals("ENABLE") && !operation.equals("DISABLE")) throw new BizException("SKU_ACTION_INVALID", "只支持启用或停用");
        List<Outcome> results = new ArrayList<>();
        for (String id : skuIds.stream().distinct().toList()) {
            try {
                var sku = operation.equals("ENABLE") ? lifecycle.enable(tenant, id, reason) : lifecycle.disable(tenant, id, reason);
                results.add(new Outcome(id, true, "SUCCESS", "操作成功", sku.status().name()));
            } catch (BizException error) {
                results.add(new Outcome(id, false, error.getCode(), error.getMessage(), null));
            }
        }
        return List.copyOf(results);
    }

    public record Outcome(String skuId, boolean success, String code, String message, String status) {}
}
