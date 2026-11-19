package com.aidotnet.erp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ErpAppTest
@AutoConfigureMockMvc
class SalesPickingApiTests {
    private static final String SALES = "/oms/api/in/v1/orders";
    private static final String PICKS = "/wms/api/in/v1/sales-picking";
    private static final String DISPATCH = "oms:warehouse:write";
    private static final String PICK = "wms:pick:write";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;

    @Test
    void orderReservesOnlyPutawayGoodsAndMultipleActualPicksRemainOnHandInStaging() throws Exception {
        Fixture f = fixture();
        String order = salesOrder(f, 8);
        JsonNode work = data(dispatch(f, order));
        String outbound = work.path("outboundId").asText();
        assertThat(work.path("allocations").size()).isEqualTo(2);
        assertThat(work.at("/allocations/0/locationCode").asText()).isEqualTo("A-01");
        assertThat(work.at("/allocations/0/plannedQuantity").asInt()).isEqualTo(5);
        assertThat(work.at("/allocations/1/plannedQuantity").asInt()).isEqualTo(3);
        assertThat(data(dispatch(f, order)).path("outboundId").asText()).isEqualTo(outbound);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(20))
                .andExpect(jsonPath("$.data[0].reserved").value(8))
                .andExpect(jsonPath("$.data[0].available").value(2));
        pick(f, outbound, work.at("/allocations/0/allocationId").asText(), UUID.randomUUID().toString(),
                "A-01", f.batch(), 2).andExpect(status().isOk());
        pick(f, outbound, work.at("/allocations/0/allocationId").asText(), UUID.randomUUID().toString(),
                "A-01", f.batch(), 3).andExpect(status().isOk());
        call(f, get(PICKS + "/{id}", outbound), PICK)
                .andExpect(jsonPath("$.data.allocations[0].pickedQuantity").value(5))
                .andExpect(jsonPath("$.data.picks.length()").value(2))
                .andExpect(jsonPath("$.data.picks[0].receiptId").value(f.receipt()))
                .andExpect(jsonPath("$.data.picks[0].purchaseOrderId").value(f.po()));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(20))
                .andExpect(jsonPath("$.data[0].reserved").value(8));
        JsonNode ledger = data(call(f, get("/wms/api/in/v1/inventory-ledger/balances")));
        assertThat(ledger.findValuesAsText("status")).contains("RESERVED", "PICKED", "AVAILABLE", "PENDING_PUTAWAY");
        JsonNode movements = data(call(f, get("/wms/api/in/v1/inventory-ledger/transactions").param("documentId", outbound)));
        assertThat(movements.size()).isEqualTo(4);
        assertThat(movements.findValuesAsText("operatorId")).containsOnly("warehouse-worker");
    }

    @Test
    void wrongScansRetriesAndLegacyActionsCannotChangeTheOrdersBatchReservation() throws Exception {
        Fixture f = fixture();
        String order = salesOrder(f, 8);
        call(f, post(SALES + "/{id}/warehouse-work", order).content("{\"warehouseId\":\"" + f.warehouse() + "\"}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        JsonNode work = data(dispatch(f, order));
        String outbound = work.path("outboundId").asText();
        String allocation = work.at("/allocations/0/allocationId").asText();
        String request = UUID.randomUUID().toString();
        pick(f, outbound, allocation, request, "A-02", f.batch(), 2)
                .andExpect(jsonPath("$.code").value("SALES_PICK_SCAN_INVALID"));
        pick(f, outbound, allocation, request, "A-01", UUID.randomUUID().toString(), 2)
                .andExpect(jsonPath("$.code").value("SALES_PICK_SCAN_INVALID"));
        pick(f, outbound, allocation, request, "A-01", f.batch(), 6)
                .andExpect(jsonPath("$.code").value("SALES_PICK_QUANTITY_INVALID"));
        call(f, post(PICKS + "/{id}/picks", outbound).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":2}
                """.formatted(request, allocation, f.batch())))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        Fixture other = new Fixture("other-" + f.tenant(), f.warehouse(), f.po(), f.receipt(), f.batch());
        pick(other, outbound, allocation, request, "A-01", f.batch(), 2)
                .andExpect(jsonPath("$.code").value("SALES_PICK_NOT_FOUND"));
        pick(f, outbound, allocation, request, "A-01", f.batch(), 2).andExpect(status().isOk());
        pick(f, outbound, allocation, request, "A-01", f.batch(), 2).andExpect(status().isOk());
        pick(f, outbound, allocation, request, "A-01", f.batch(), 3)
                .andExpect(jsonPath("$.code").value("SALES_PICK_REQUEST_CONFLICT"));
        call(f, post("/wms/api/in/v1/outbound-orders/{id}/pick", outbound).content("""
                {"picks":[{"lineId":"%s","pickedQuantity":1}]}
                """.formatted(work.at("/allocations/0/lineId").asText())))
                .andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        call(f, patch(SALES + "/{id}/cancel", order))
                .andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_WORK_EXISTS"));
        call(f, post(SALES + "/{id}/fulfillment-plans/generate", order))
                .andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_WORK_EXISTS"));
        call(f, patch(SALES + "/{id}/ship", order))
                .andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_WORK_EXISTS"));
        call(f, post(SALES + "/{id}/refunds", order).content("{\"reason\":\"Cancel paid order\",\"refundType\":\"FULL\"}"))
                .andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_WORK_EXISTS"));
        call(f, post(SALES + "/risk-alerts").content("""
                {"orderId":"%s","riskType":"QUALITY","riskScore":95,"riskLevel":"HIGH"}
                """.formatted(order))).andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_WORK_EXISTS"));
        call(f, get(SALES + "/{id}", order)).andExpect(jsonPath("$.data.status").value("PAID"));
        call(f, post("/wms/api/in/v1/inventory/release").content("""
                {"warehouseId":"%s","sellerSku":"MUG-01","quantity":1,"referenceType":"MANUAL","referenceId":"test"}
                """.formatted(f.warehouse()))).andExpect(jsonPath("$.code").value("BATCH_STOCK_OPERATION_REQUIRED"));
        call(f, get(PICKS + "/{id}", outbound), PICK).andExpect(jsonPath("$.data.picks.length()").value(1));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(20))
                .andExpect(jsonPath("$.data[0].reserved").value(8));
    }

    @Test
    void concurrentOrdersAndActualPicksCannotReserveOrPickTheSameGoodsTwice() throws Exception {
        Fixture f = fixture();
        String firstOrder = salesOrder(f, 8);
        String secondOrder = salesOrder(f, 8);
        List<JsonNode> attempts = race(() -> body(dispatch(f, firstOrder)), () -> body(dispatch(f, secondOrder)));
        assertThat(attempts.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(attempts.stream().filter(r -> !r.path("success").asBoolean()).findFirst().orElseThrow().path("code").asText())
                .isEqualTo("SALES_PICK_INSUFFICIENT_STOCK");
        JsonNode work = attempts.stream().filter(r -> r.path("success").asBoolean()).findFirst().orElseThrow().path("data");
        String outbound = work.path("outboundId").asText();
        String allocation = work.at("/allocations/0/allocationId").asText();
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(8)).andExpect(jsonPath("$.data[0].available").value(2));
        List<JsonNode> actual = race(
                () -> body(pick(f, outbound, allocation, UUID.randomUUID().toString(), "A-01", f.batch(), 4)),
                () -> body(pick(f, outbound, allocation, UUID.randomUUID().toString(), "A-01", f.batch(), 4)));
        assertThat(actual.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(actual.stream().filter(r -> !r.path("success").asBoolean()).findFirst().orElseThrow().path("code").asText())
                .isEqualTo("SALES_PICK_QUANTITY_INVALID");
        String retryId = UUID.randomUUID().toString();
        assertThat(race(() -> body(pick(f, outbound, allocation, retryId, "A-01", f.batch(), 1)),
                () -> body(pick(f, outbound, allocation, retryId, "A-01", f.batch(), 1))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        call(f, get(PICKS + "/{id}", outbound), PICK).andExpect(jsonPath("$.data.picks.length()").value(2))
                .andExpect(jsonPath("$.data.allocations[0].pickedQuantity").value(5));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(20)).andExpect(jsonPath("$.data[0].reserved").value(8));
    }

    @Test
    void unavailableLocationsAndUnfinishedReceiptsCannotBeAllocatedAndShortageRollsBackTheWholeOrder() throws Exception {
        Fixture f = fixture();
        String order = salesOrder(f, 11);
        dispatch(f, order).andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        String locationId = data(call(f, get("/wms/api/in/v1/warehouses/{id}/locations", f.warehouse()))).get(1).path("locationId").asText();
        call(f, patch("/wms/api/in/v1/warehouses/{id}/locations/{location}/enabled", f.warehouse(), locationId)
                .content("{\"enabled\":false}"), "wms:location:write").andExpect(status().isOk());
        String smaller = salesOrder(f, 8);
        dispatch(f, smaller).andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].available").value(10));
        call(f, get(PICKS + "/work"), PICK).andExpect(jsonPath("$.data.orders").isEmpty());
        JsonNode transactions = data(call(f, get("/wms/api/in/v1/inventory-ledger/transactions")));
        assertThat(transactions.findValuesAsText("transactionType")).doesNotContain("RESERVE", "SALES_PICK");
    }

    private List<JsonNode> race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void pendingInspectionAndQuarantinedBatchesCannotFillASalesOrderShortage() throws Exception {
        Fixture f = fixture();
        String supplier = data(call(f, get("/scm/api/in/v1/purchase-orders/{po}", f.po()))).path("supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"MUG-01","quantity":10,"unitCost":10}]}
                """.formatted(supplier)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        JsonNode receipt = data(call(f, post("/wms/api/in/v1/purchase-receipts/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":5,"supplierBatchNo":"PENDING"},
                {"lineId":"L1","quantity":5,"supplierBatchNo":"BAD"}]}
                """.formatted(UUID.randomUUID(), f.warehouse())), "wms:receipt:write"));
        String badBatch = receipt.at("/lines/1/batchNo").asText();
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/sample", badBatch).content("""
                {"checkedQuantity":1,"passedQuantity":0,"failedQuantity":1}
                """), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/recheck", badBatch).content("""
                {"method":"FULL","checkedQuantity":5,"passedQuantity":0,"failedQuantity":5}
                """), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/disposition", badBatch)
                .content("{\"goodQuantity\":0,\"defectiveQuantity\":5}"), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/warehouses/{id}/locations", f.warehouse())
                .content("{\"locationCode\":\"Q-01\",\"purpose\":\"QUARANTINE\"}"), "wms:location:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-returns/batches/{b}/isolate", badBatch).content("""
                {"requestId":"%s","locationCode":"Q-01","quantity":5}
                """.formatted(UUID.randomUUID())), "wms:quarantine:write").andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(30))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(5))
                .andExpect(jsonPath("$.data[0].quarantined").value(5));
        dispatch(f, salesOrder(f, 11)).andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].available").value(10));
    }

    @Test
    void putawayAfterPickingUsesThePhysicalSourceLocationBalance() throws Exception {
        Fixture f = fixture();
        JsonNode work = data(dispatch(f, salesOrder(f, 8)));
        String outbound = work.path("outboundId").asText();
        pick(f, outbound, work.at("/allocations/0/allocationId").asText(), UUID.randomUUID().toString(), "A-01", f.batch(), 2)
                .andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-putaways/{b}", f.batch()).content("""
                {"requestId":"%s","locationCode":"A-01","quantity":2}
                """.formatted(UUID.randomUUID())), "wms:putaway:write")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.beforeLocationQuantity").value(3))
                .andExpect(jsonPath("$.data.afterLocationQuantity").value(5));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(20)).andExpect(jsonPath("$.data[0].reserved").value(8))
                .andExpect(jsonPath("$.data[0].available").value(4));
    }
    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }

    private Fixture fixture() throws Exception {
        Fixture f = new Fixture("sales-pick-" + UUID.randomUUID(), null, null, null, null);
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Pick supplier\",\"countryCode\":\"CN\"}")))
                .at("/supplier/supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"MUG-01","quantity":20,"unitCost":10}]}
                """.formatted(supplier)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        String wh = data(call(f, post("/wms/api/in/v1/warehouses").content("{\"code\":\"PICK\",\"name\":\"Pick Warehouse\",\"countryCode\":\"US\"}")))
                .path("warehouseId").asText();
        f = new Fixture(f.tenant(), wh, po, null, null);
        JsonNode receipt = data(call(f, post("/wms/api/in/v1/purchase-receipts/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":20}]}
                """.formatted(UUID.randomUUID(), wh)), "wms:receipt:write"));
        String batch = receipt.at("/lines/0/batchNo").asText();
        f = new Fixture(f.tenant(), wh, po, receipt.path("receiptId").asText(), batch);
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/sample", batch)
                .content("{\"checkedQuantity\":2,\"passedQuantity\":2,\"failedQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/disposition", batch)
                .content("{\"goodQuantity\":20,\"defectiveQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
        for (String code : new String[]{"A-01", "A-02"}) {
            call(f, post("/wms/api/in/v1/warehouses/{wh}/locations", wh).content("""
                    {"locationCode":"%s","purpose":"GOOD","enabled":true}
                    """.formatted(code)), "wms:location:write").andExpect(status().isOk());
            call(f, post("/wms/api/in/v1/purchase-putaways/{b}", batch).content("""
                    {"requestId":"%s","locationCode":"%s","quantity":5}
                    """.formatted(UUID.randomUUID(), code)), "wms:putaway:write").andExpect(status().isOk());
        }
        return f;
    }

    private String salesOrder(Fixture f, int quantity) throws Exception {
        String id = data(call(f, post(SALES + "/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Alice-%s","countryCode":"US",
                 "shippingAddress":"1 Main Street","currency":"USD","lines":[{"lineId":"L1",
                 "sellerSku":"MUG-01","title":"Mug","quantity":%d,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), quantity)))).path("orderId").asText();
        call(f, patch(SALES + "/{id}/paid", id)).andExpect(status().isOk());
        return id;
    }
    private ResultActions dispatch(Fixture f, String order) throws Exception {
        return call(f, post(SALES + "/{id}/warehouse-work", order).content("{\"warehouseId\":\"" + f.warehouse() + "\"}"), DISPATCH);
    }
    private ResultActions pick(Fixture f, String outbound, String allocation, String requestId, String code, String batch, int quantity) throws Exception {
        return call(f, post(PICKS + "/{id}/picks", outbound).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"%s","batchNo":"%s","quantity":%d}
                """.formatted(requestId, allocation, code, batch, quantity)), PICK);
    }
    private ResultActions stock(Fixture f) throws Exception {
        return call(f, get("/wms/api/in/v1/warehouses/{id}/inventory", f.warehouse()));
    }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken(f.tenant(), "warehouse-worker", "warehouse", Set.of(permissions))).contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private record Fixture(String tenant, String warehouse, String po, String receipt, String batch) {}
}
