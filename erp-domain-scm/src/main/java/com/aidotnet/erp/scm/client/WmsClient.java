package com.aidotnet.erp.scm.client;

import com.aidotnet.erp.common.api.Result;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "erp-app", contextId = "wms-client-scm", path = "/wms/api/in/v1")
public interface WmsClient {

    @GetMapping("/warehouses/{warehouseId}")
    Result<Map<String, Object>> getWarehouse(@PathVariable String warehouseId);

    @GetMapping("/inventory/{sellerSku}/availability")
    Result<InventoryAvailabilityResponse> getAvailability(@PathVariable String sellerSku);

    record InventoryAvailabilityResponse(String sellerSku, int onHand, int reserved, int available) {}

}
