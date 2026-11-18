package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
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
class PurchaseInspectionApiTests {
    private static final String RETURNS = "/wms/api/in/v1/purchase-returns";
    private static final String ISOLATE = "wms:quarantine:write";
    private static final String REQUEST_RETURN = "scm:purchase-return:write";
    private static final String SHIP_RETURN = "wms:purchase-return:ship";

    private static final String INSPECTIONS = "/wms/api/in/v1/purchase-inspections";
    private static final String RECEIPTS = "/wms/api/in/v1/purchase-receipts";
    private static final String ORDERS = "/scm/api/in/v1/purchase-orders";
    private static final String QUALITY = "wms:inspection:write";
    private static final String RECEIVE = "wms:receipt:write";
    private static final String PUTAWAY = "wms:putaway:write";
    private static final String PUTAWAYS = "/wms/api/in/v1/purchase-putaways";
    private static final String LEDGER = "/wms/api/in/v1/inventory-ledger";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void passingSampleDoesNotReleaseStockAndTheWholeBatchMovesToPendingPutaway() throws Exception {
        Fixture f = fixture(240);
        JsonNode receipt = receive(f, """
                [{"lineId":"L1","quantity":240,"supplierBatchNo":"S01"}]
                """);
        String batch = receipt.at("/lines/0/batchNo").asText();
        call(f, get(INSPECTIONS + "/work"), RECEIVE)
                .andExpect(jsonPath("$.data.canInspect").value(false))
                .andExpect(jsonPath("$.data.batches").isEmpty());
        call(f, get(INSPECTIONS + "/work"), QUALITY)
                .andExpect(jsonPath("$.data.batches[0].batchNo").value(batch))
                .andExpect(jsonPath("$.data.batches[0].status").value("PENDING_SAMPLE"));
        call(new Fixture("other-tenant", null, null), get(INSPECTIONS + "/{batch}", batch), QUALITY)
                .andExpect(jsonPath("$.code").value("INSPECTION_BATCH_NOT_FOUND"));
        call(f, post("/wms/api/in/v1/quality-checks").content("""
                {"warehouseId":"%s","inboundOrderId":"%s","sellerSku":"MUG-01",
                 "sampleQuantity":20,"passQuantity":20,"failQuantity":0,"inspector":"legacy"}
                """.formatted(f.warehouse(), receipt.path("receiptId").asText())))
                .andExpect(jsonPath("$.code").value("PURCHASE_BATCH_INSPECTION_REQUIRED"));
        inventory(f).andExpect(jsonPath("$.data[0].pendingInspection").value(240));
        sample(f, batch, "sample", 20, 20, 1).andExpect(status().isBadRequest());
        sample(f, batch, "sample", 241, 241, 0).andExpect(status().isBadRequest());
        sample(f, batch, "sample", 20, 20, 0).andExpect(status().isOk());
        inventory(f).andExpect(jsonPath("$.data[0].pendingInspection").value(240))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(0))
                .andExpect(jsonPath("$.data[0].available").value(0));

        disposition(f, batch, 240, 0, RECEIVE).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(INSPECTIONS + "/{batch}/disposition", batch).content("""
                {"goodQuantity":239,"defectiveQuantity":0}
                """), QUALITY).andExpect(status().isBadRequest());
        String checkId = data(disposition(f, batch, 240, 0, QUALITY)).path("inspectionId").asText();
        disposition(f, batch, 240, 0, QUALITY).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inspectionId").value(checkId));
        call(f, get(INSPECTIONS + "/{batch}", batch), QUALITY)
                .andExpect(jsonPath("$.data.sample.checkedQuantity").value(20))
                .andExpect(jsonPath("$.data.sample.passedQuantity").value(20))
                .andExpect(jsonPath("$.data.pendingPutaway").value(240))
                .andExpect(jsonPath("$.data.pendingInspection").value(0));
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(240))
                .andExpect(jsonPath("$.data[0].frozen").value(240))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(0))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(240))
                .andExpect(jsonPath("$.data[0].available").value(0));
        String stockCheck = "/wms/api/in/v1/warehouses/{id}/stock-checks";
        String stockCheckId = data(call(f, post(stockCheck, f.warehouse()).content("""
                {"sellerSku":"MUG-01","actualQuantity":239,"checkedBy":"checker"}
                """))).path("checkId").asText();
        call(f, patch(stockCheck + "/{checkId}/adjust", f.warehouse(), stockCheckId))
                .andExpect(jsonPath("$.code").value("STOCK_CHECK_BELOW_COMMITTED_INVENTORY"));
        call(f, get("/wms/api/in/v1/warehouses/{id}/inventory-transactions", f.warehouse()))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].referenceId").value(checkId))
                .andExpect(jsonPath("$.data[0].batchNo").value(batch))
                .andExpect(jsonPath("$.data[0].beforeOnHand").value(240))
                .andExpect(jsonPath("$.data[0].afterOnHand").value(240))
                .andExpect(jsonPath("$.data[0].pendingInspectionDelta").value(-240))
                .andExpect(jsonPath("$.data[0].pendingPutawayDelta").value(240))
                .andExpect(jsonPath("$.data[0].operatorId").value("quality-worker"));
    }

    @Test
    void fullInspectionAfterFailedSampleAffectsOnlyTheSelectedBatch() throws Exception {
        Fixture f = fixture(240);
        String batch = receive(f, """
                [{"lineId":"L1","quantity":100,"supplierBatchNo":"S01"}]
                """).at("/lines/0/batchNo").asText();
        JsonNode second = receive(f, """
                [{"lineId":"L1","quantity":60,"supplierBatchNo":"S02"},
                 {"lineId":"L1","quantity":80,"supplierBatchNo":"S03"}]
                """);
        call(f, get(INSPECTIONS + "/work").param("purchaseOrderId", f.purchaseOrder()), QUALITY)
                .andExpect(jsonPath("$.data.batches.length()").value(3))
                .andExpect(jsonPath("$.data.batches[0].batchNo").value(batch));
        sample(f, batch, "sample", 20, 19, 1)
                .andExpect(jsonPath("$.data.batch.status").value("PENDING_RECHECK"));
        disposition(f, batch, 97, 3, QUALITY)
                .andExpect(jsonPath("$.code").value("INSPECTION_RECHECK_REQUIRED"));
        String full = """
                {"method":"FULL","checkedQuantity":%d,"passedQuantity":%d,"failedQuantity":3}
                """;
        call(f, post(INSPECTIONS + "/{batch}/recheck", batch).content(full.formatted(99, 96)), QUALITY)
                .andExpect(jsonPath("$.code").value("INSPECTION_FULL_QUANTITY_INVALID"));
        call(f, post(INSPECTIONS + "/{batch}/recheck", batch).content(full.formatted(100, 97)), QUALITY)
                .andExpect(jsonPath("$.data.recheck.failedQuantity").value(3))
                .andExpect(jsonPath("$.data.batch.status").value("PENDING_DISPOSITION"));
        inventory(f).andExpect(jsonPath("$.data[0].pendingInspection").value(240))
                .andExpect(jsonPath("$.data[0].quarantined").value(0));
        disposition(f, batch, 98, 2, QUALITY).andExpect(jsonPath("$.code").value("INSPECTION_FULL_RESULT_MISMATCH"));
        disposition(f, batch, 97, 3, QUALITY).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batch.batchNo").value(batch))
                .andExpect(jsonPath("$.data.pendingPutaway").value(97))
                .andExpect(jsonPath("$.data.quarantined").value(3));
        disposition(f, batch, 96, 4, QUALITY).andExpect(jsonPath("$.code").value("INSPECTION_ALREADY_DISPOSED"));
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(240))
                .andExpect(jsonPath("$.data[0].available").value(0))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(140))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(97))
                .andExpect(jsonPath("$.data[0].quarantined").value(3));
        call(f, get(INSPECTIONS + "/work"), QUALITY)
                .andExpect(jsonPath("$.data.batches.length()").value(2))
                .andExpect(jsonPath("$.data.batches[0].batchNo").value(second.at("/lines/0/batchNo").asText()))
                .andExpect(jsonPath("$.data.batches[1].batchNo").value(second.at("/lines/1/batchNo").asText()));
        String b2 = second.at("/lines/0/batchNo").asText();
        sample(f, b2, "sample", 5, 5, 0).andExpect(status().isOk());
        disposition(f, b2, 60, 0, QUALITY).andExpect(status().isOk());
        inventory(f).andExpect(jsonPath("$.data[0].pendingInspection").value(80))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(157))
                .andExpect(jsonPath("$.data[0].quarantined").value(3));
        String b3 = second.at("/lines/1/batchNo").asText();
        sample(f, b3, "sample", 5, 4, 1).andExpect(status().isOk());
        call(f, post(INSPECTIONS + "/{batch}/recheck", b3).content("""
                {"method":"RECHECK","checkedQuantity":20,"passedQuantity":18,"failedQuantity":2}
                """), QUALITY).andExpect(status().isOk());
        disposition(f, b3, 80, 0, QUALITY).andExpect(jsonPath("$.code").value("INSPECTION_RECHECK_RESULT_MISMATCH"));
        disposition(f, b3, 78, 2, QUALITY).andExpect(status().isOk());
        inventory(f).andExpect(jsonPath("$.data[0].pendingInspection").value(0))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(235))
                .andExpect(jsonPath("$.data[0].quarantined").value(5));
    }

    private Fixture fixture(int orderedQuantity) throws Exception {
        Fixture tenant = new Fixture("inspection-" + UUID.randomUUID(), null, null);
        String supplier = data(call(tenant, post("/scm/api/in/v1/suppliers").content("""
                {"name":"Quality supplier","countryCode":"CN"}
                """))).at("/supplier/supplierId").asText();
        String po = data(call(tenant, post(ORDERS).content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"MUG-01","quantity":%d,"unitCost":10}]}
                """.formatted(supplier, orderedQuantity)))).path("poId").asText();
        call(tenant, patch(ORDERS + "/{po}/submit", po)).andExpect(status().isOk());
        call(tenant, patch(ORDERS + "/{po}/approve", po)).andExpect(status().isOk());
        String warehouse = data(call(tenant, post("/wms/api/in/v1/warehouses").content("""
                {"code":"QC","name":"Quality Warehouse","countryCode":"US"}
                """))).path("warehouseId").asText();
        return new Fixture(tenant.tenant(), warehouse, po);
    }

    @Test
    void splitPutawayTracksAvailableStockAndLedgerByLocation() throws Exception {
        Fixture f = fixture(100);
        String batch = receive(f, """
                [{"lineId":"L1","quantity":100,"supplierBatchNo":"S01"}]
                """).at("/lines/0/batchNo").asText();
        String a1 = location(f, f.warehouse(), "A-01", "GOOD", true);
        String a2 = location(f, f.warehouse(), "A-02", "GOOD", true);
        location(f, f.warehouse(), "OFF", "GOOD", false);
        location(f, f.warehouse(), "QC", "QUARANTINE", true);
        String otherWarehouse = data(call(f, post("/wms/api/in/v1/warehouses").content("""
                {"code":"OTHER","name":"Other Warehouse","countryCode":"US"}
                """))).path("warehouseId").asText();
        location(f, otherWarehouse, "OTHER-01", "GOOD", true);

        sample(f, batch, "sample", 20, 19, 1).andExpect(status().isOk());
        call(f, post(INSPECTIONS + "/{batch}/recheck", batch).content("""
                {"method":"FULL","checkedQuantity":100,"passedQuantity":97,"failedQuantity":3}
                """), QUALITY).andExpect(status().isOk());
        disposition(f, batch, 97, 3, QUALITY).andExpect(status().isOk());
        call(f, get(PUTAWAYS + "/work").param("purchaseOrderId", f.purchaseOrder()), PUTAWAY)
                .andExpect(jsonPath("$.data.batches[0].batchNo").value(batch))
                .andExpect(jsonPath("$.data.batches[0].pendingPutaway").value(97));

        String requestId = UUID.randomUUID().toString();
        putaway(f, batch, requestId, "A-01", 60, RECEIVE).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (String code : new String[]{"OTHER-01", "OFF", "QC"}) {
            putaway(f, batch, requestId, code, 1, PUTAWAY).andExpect(status().isBadRequest());
        }
        inventory(f).andExpect(jsonPath("$.data[0].available").value(0));
        putaway(f, batch, requestId, "A-01", 60, PUTAWAY).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterPendingPutaway").value(37))
                .andExpect(jsonPath("$.data.targetLocationId").value(a1));
        putaway(f, batch, requestId, "A-01", 60, PUTAWAY).andExpect(status().isOk());
        putaway(f, batch, requestId, "A-02", 37, PUTAWAY).andExpect(status().isBadRequest());
        call(f, get(PUTAWAYS + "/{batch}/recommendation", batch), PUTAWAY)
                .andExpect(jsonPath("$.data.locationCode").value("A-01"));
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(100))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(37))
                .andExpect(jsonPath("$.data[0].available").value(60));
        call(f, post("/wms/api/in/v1/inventory/reserve").content("""
                {"warehouseId":"%s","sellerSku":"MUG-01","quantity":1}
                """.formatted(f.warehouse())))
                .andExpect(jsonPath("$.code").value("BATCH_STOCK_OPERATION_REQUIRED"));
        call(f, post("/wms/api/in/v1/movements").content("""
                {"warehouseId":"%s","sellerSku":"MUG-01","fromLocationId":"%s","toLocationId":"%s","quantity":1}
                """.formatted(f.warehouse(), a1, a2)))
                .andExpect(jsonPath("$.code").value("BATCH_STOCK_OPERATION_REQUIRED"));
        putaway(f, batch, UUID.randomUUID().toString(), "A-02", 38, PUTAWAY).andExpect(status().isBadRequest());
        String second = UUID.randomUUID().toString();
        putaway(f, batch, second, "A-02", 37, PUTAWAY).andExpect(status().isOk());
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(100))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(0))
                .andExpect(jsonPath("$.data[0].available").value(97))
                .andExpect(jsonPath("$.data[0].quarantined").value(3));

        call(f, get(LEDGER + "/balances").param("warehouseId", f.warehouse()).param("purchaseOrderId", f.purchaseOrder())
                        .param("sellerSku", "MUG-01").param("batchNo", batch))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.locationCode == 'A-01')].quantity").value(org.hamcrest.Matchers.contains(60)))
                .andExpect(jsonPath("$.data[?(@.locationCode == 'A-02')].quantity").value(org.hamcrest.Matchers.contains(37)))
                .andExpect(jsonPath("$.data[?(@.status == 'QUARANTINED')].quantity").value(org.hamcrest.Matchers.contains(3)));
        call(f, get(LEDGER + "/transactions").param("warehouseId", f.warehouse()).param("locationId", a2)
                        .param("batchNo", batch).param("documentId", second))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].referenceId").value(second))
                .andExpect(jsonPath("$.data[0].beforeOnHand").value(100))
                .andExpect(jsonPath("$.data[0].afterOnHand").value(100))
                .andExpect(jsonPath("$.data[0].pendingPutawayDelta").value(-37))
                .andExpect(jsonPath("$.data[0].afterLocationQuantity").value(37));
        call(f, patch("/wms/api/in/v1/warehouses/{wh}/locations/{id}/enabled", f.warehouse(), a2)
                .content("{\"enabled\":false}"), "wms:location:write").andExpect(status().isOk());
        call(f, get(PUTAWAYS + "/{batch}/recommendation", batch), PUTAWAY)
                .andExpect(jsonPath("$.data.locationCode").value("A-01"));
    }

    @Test
    void returnsOnlyActualDefectivesFromTheirQuarantineLocationBeforeGoodPutaway() throws Exception {
        Fixture f = fixture(110);
        JsonNode receipt = receive(f, """
                [{"lineId":"L1","quantity":100}]
                """);
        String receiptId = receipt.path("receiptId").asText();
        String batch = receipt.at("/lines/0/batchNo").asText();
        String quarantine = location(f, f.warehouse(), "Q-01", "QUARANTINE", true);
        location(f, f.warehouse(), "A-01", "GOOD", true);
        sample(f, batch, "sample", 20, 19, 1).andExpect(status().isOk());
        call(f, post(INSPECTIONS + "/{batch}/recheck", batch).content("""
                {"method":"FULL","checkedQuantity":100,"passedQuantity":97,"failedQuantity":3}
                """), QUALITY).andExpect(status().isOk());
        disposition(f, batch, 97, 3, QUALITY).andExpect(status().isOk());
        call(f, get(RETURNS + "/work").param("purchaseOrderId", f.purchaseOrder()), ISOLATE)
                .andExpect(jsonPath("$.data.pendingIsolation[0].quantity").value(3));

        String isolation = UUID.randomUUID().toString();
        call(f, post(RETURNS + "/batches/{batch}/isolate", batch).content("""
                {"requestId":"%s","locationCode":"A-01","quantity":3}
                """.formatted(isolation)), ISOLATE).andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            call(f, post(RETURNS + "/batches/{batch}/isolate", batch).content("""
                    {"requestId":"%s","locationCode":"Q-01","quantity":3}
                    """.formatted(isolation)), ISOLATE).andExpect(status().isOk());
        }
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(100))
                .andExpect(jsonPath("$.data[0].available").value(0));
        call(f, get(LEDGER + "/balances").param("locationId", quarantine).param("batchNo", batch))
                .andExpect(jsonPath("$.data[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$.data[0].quantity").value(3));
        call(f, post("/wms/api/in/v1/movements").content("""
                {"warehouseId":"%s","sellerSku":"MUG-01","fromLocationId":"%s","toLocationId":"%s","quantity":1}
                """.formatted(f.warehouse(), quarantine, quarantine)))
                .andExpect(jsonPath("$.code").value("BATCH_STOCK_OPERATION_REQUIRED"));

        String returnId = UUID.randomUUID().toString();
        String body = """
                {"returnId":"%s","batchNo":"%s","locationId":"%s","quantity":3,"reason":"Damaged"}
                """.formatted(returnId, batch, quarantine);
        call(f, post(RETURNS + "/receipts/{receipt}", receiptId).content(body), ISOLATE)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (int attempt = 0; attempt < 2; attempt++) {
            call(f, post(RETURNS + "/receipts/{receipt}", receiptId).content(body), REQUEST_RETURN)
                    .andExpect(jsonPath("$.data.purchaseOrderLineId").value("L1"))
                    .andExpect(jsonPath("$.data.locationCode").value("Q-01"));
        }
        call(f, post(RETURNS + "/receipts/{receipt}", receiptId)
                    .content(body.replace(returnId, UUID.randomUUID().toString())), REQUEST_RETURN)
                .andExpect(status().isBadRequest());
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(100));
        call(f, post(RETURNS + "/{id}/ship", returnId).content("{\"quantity\":3}"), REQUEST_RETURN)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(RETURNS + "/{id}/ship", returnId).content("{\"quantity\":4}"), SHIP_RETURN)
                .andExpect(status().isBadRequest());
        call(new Fixture("other-return-tenant", null, null), post(RETURNS + "/{id}/ship", returnId)
                .content("{\"quantity\":3}"), SHIP_RETURN).andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            call(f, post(RETURNS + "/{id}/ship", returnId).content("{\"quantity\":3}"), SHIP_RETURN)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.shippedQuantity").value(3));
        }
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(97))
                .andExpect(jsonPath("$.data[0].quarantined").value(0))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(97))
                .andExpect(jsonPath("$.data[0].available").value(0));
        call(f, get(LEDGER + "/balances").param("locationId", quarantine).param("batchNo", batch))
                .andExpect(jsonPath("$.data").isEmpty());
        call(f, get(ORDERS + "/{po}", f.purchaseOrder()))
                .andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(10));
        call(f, get(ORDERS + "/{po}/supplier-returns", f.purchaseOrder()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].shippedQuantity").value(3));
        call(f, get(LEDGER + "/transactions").param("documentId", returnId).param("batchNo", batch))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sourceLocationId").value(quarantine))
                .andExpect(jsonPath("$.data[0].afterOnHand").value(97));
    }

    private String location(Fixture f, String warehouseId, String code, String purpose, boolean enabled) throws Exception {
        return data(call(f, post("/wms/api/in/v1/warehouses/{id}/locations", warehouseId)
                .content("""
                        {"locationCode":"%s","purpose":"%s","enabled":%s}
                        """.formatted(code, purpose, enabled)), "wms:location:write")).path("locationId").asText();
    }

    private ResultActions putaway(Fixture f, String batch, String requestId, String locationCode, int quantity,
                                  String permission) throws Exception {
        return call(f, post(PUTAWAYS + "/{batch}", batch).content("""
                {"requestId":"%s","locationCode":"%s","quantity":%d}
                """.formatted(requestId, locationCode, quantity)), permission);
    }

    private JsonNode receive(Fixture fixture, String lines) throws Exception {
        return data(call(fixture, post(RECEIPTS + "/{po}", fixture.purchaseOrder()).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":%s}
                """.formatted(UUID.randomUUID(), fixture.warehouse(), lines)), RECEIVE));
    }

    private ResultActions sample(Fixture f, String batch, String stage, int checked, int passed, int failed)
            throws Exception {
        return call(f, post(INSPECTIONS + "/{batch}/{stage}", batch, stage).content("""
                {"checkedQuantity":%d,"passedQuantity":%d,"failedQuantity":%d}
                """.formatted(checked, passed, failed)), QUALITY);
    }

    private ResultActions inventory(Fixture f) throws Exception {
        return call(f, get("/wms/api/in/v1/warehouses/{id}/inventory", f.warehouse())).andExpect(status().isOk());
    }

    private ResultActions disposition(Fixture f, String batch, int good, int defective, String permission)
            throws Exception {
        return call(f, post(INSPECTIONS + "/{batch}/disposition", batch).content("""
                {"goodQuantity":%d,"defectiveQuantity":%d}
                """.formatted(good, defective)), permission);
    }

    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                        jwt.createToken(f.tenant(), "quality-worker", "inspector", Set.of(permissions)))
                .contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode data(ResultActions response) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private record Fixture(String tenant, String warehouse, String purchaseOrder) {}
}
