package com.aidotnet.erp.app;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ErpAppTest
@AutoConfigureMockMvc
class PurchaseReceiptApiTests {

    private static final String PURCHASE_ORDERS = "/scm/api/in/v1/purchase-orders";
    private static final String RECEIPTS = "/wms/api/in/v1/purchase-receipts";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwtProvider;

    @Test
    void confirmsTwoDeliveriesAndDoesNotPostTheSameReceiptTwice() throws Exception {
        String tenant = "receipt-" + UUID.randomUUID();
        String warehouse = id(call(tenant, post("/wms/api/in/v1/warehouses")
                .content("""
                        {"code":"RCV","name":"Receiving Warehouse","countryCode":"US"}
                        """)), "/data/warehouseId");
        String supplier = id(call(tenant, post("/scm/api/in/v1/suppliers")
                .content("""
                        {"name":"Receipt supplier","countryCode":"CN"}
                        """)), "/data/supplier/supplierId");
        String po = id(call(tenant, post(PURCHASE_ORDERS).content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                  {"lineId":"L1","sellerSku":"MUG-01","quantity":240,"unitCost":10}]}
                """.formatted(supplier))), "/data/poId");
        call(tenant, patch(PURCHASE_ORDERS + "/{po}/submit", po)).andExpect(status().isOk());
        call(tenant, patch(PURCHASE_ORDERS + "/{po}/approve", po)).andExpect(status().isOk());

        String firstId = UUID.randomUUID().toString();
        String firstBody = """
                {"receiptId":"%s","warehouseId":"%s","receipts":[
                  {"lineId":"L1","quantity":60,"supplierBatchNo":"S01"},
                  {"lineId":"L1","quantity":40,"supplierBatchNo":"S02"}]}
                """.formatted(firstId, warehouse);
        String otherTenant = "receipt-" + UUID.randomUUID();
        mvc.perform(post(RECEIPTS + "/{po}", po)
                        .header("X-Tenant-Id", otherTenant)
                        .header("Authorization", authorization(tenant, Set.of("wms:receipt:write")))
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isForbidden());
        mvc.perform(get(RECEIPTS + "/work")
                        .header("X-Tenant-Id", tenant)
                        .header("Authorization", authorization(tenant, Set.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canReceive").value(false))
                .andExpect(jsonPath("$.data.purchaseOrders").isEmpty());
        mvc.perform(post(RECEIPTS + "/{po}", po)
                        .header("X-Tenant-Id", tenant)
                        .header("Authorization", authorization(tenant, Set.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(tenant, get(RECEIPTS + "/work"))
                .andExpect(jsonPath("$.data.purchaseOrders[0].poId").value(po));
        String firstBatch = id(call(tenant, post(RECEIPTS + "/{po}", po).content(firstBody))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].purchaseOrderLineId").value("L1"))
                .andExpect(jsonPath("$.data.lines[0].supplierBatchNo").value("S01"))
                .andExpect(jsonPath("$.data.lines[1].supplierBatchNo").value("S02")), "/data/lines/0/batchNo");
        call(tenant, patch(PURCHASE_ORDERS + "/{po}/receive", po).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(100));

        call(tenant, post(RECEIPTS + "/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":140}]}
                """.formatted(UUID.randomUUID(), warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].batchNo").value(not(firstBatch)));
        call(tenant, get(PURCHASE_ORDERS + "/{po}", po))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(240));
        call(tenant, get(RECEIPTS).param("purchaseOrderId", po))
                .andExpect(jsonPath("$.data.length()").value(2));
        call(otherTenant, get(RECEIPTS).param("purchaseOrderId", po))
                .andExpect(jsonPath("$.data").isEmpty());
        call(tenant, post(RECEIPTS + "/{po}", po).content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].batchNo").value(firstBatch));
        call(tenant, post(RECEIPTS + "/{po}", po).content(firstBody.replace("S01", "S03")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_CONFLICT"));
        call(tenant, get(RECEIPTS + "/{id}/movements", firstId))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].quantity").value(60))
                .andExpect(jsonPath("$.data[0].batchNo").value(firstBatch))
                .andExpect(jsonPath("$.data[0].toStatus").value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data[0].operatorId").value("warehouse-worker"));
        call(tenant, get("/wms/api/in/v1/warehouses/{warehouse}/inventory", warehouse))
                .andExpect(jsonPath("$.data[0].onHand").value(240))
                .andExpect(jsonPath("$.data[0].frozen").value(240))
                .andExpect(jsonPath("$.data[0].available").value(0));
    }

    private ResultActions call(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", tenant)
                .header("Authorization", authorization(tenant, Set.of("wms:receipt:write")))
                .contentType(MediaType.APPLICATION_JSON));
    }

    private String authorization(String tenant, Set<String> permissions) {
        return "Bearer " + jwtProvider.createToken(tenant, "warehouse-worker", "worker", permissions);
    }

    private String id(ResultActions response, String pointer) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at(pointer).asText();
    }
}
