package com.aidotnet.erp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.List;
import java.util.UUID;
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
class ShortPickApiTests {
    private static final String WMS = "/wms/api/in/v1";
    private static final String SALES = "/oms/api/in/v1/orders";
    private static final String SHORT = WMS + "/short-picks";
    private static final String PICK = "wms:pick:write";
    private static final String COUNT = "wms:stock-review:write";
    private static final String APPROVE = "wms:stock-review:approve";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;

    @Test
    void shortageFreezesMissingStockAndReplacementAndSupervisorReviewPreserveBothPickSources() throws Exception {
        Fixture f = fixture(true);
        actualPick(f, f.allocation(), f.original(), 12);
        String id = UUID.randomUUID().toString();
        report(f, id, 8).andExpect(status().isOk()).andExpect(jsonPath("$.data.shortQuantity").value(8));
        call(f, get(WMS + "/sales-picking/{id}", f.outbound()), PICK).andExpect(jsonPath("$.data.status").value("SHORT_PICK"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].reserved").value(12))
                .andExpect(jsonPath("$.data[0].frozen").value(8)).andExpect(jsonPath("$.data[0].pendingReview").value(8))
                .andExpect(jsonPath("$.data[0].available").value(8));
        JsonNode pending = data(call(f, get(WMS + "/inventory-ledger/balances").param("batchNo", f.original())));
        assertThat(pending.findValuesAsText("status")).contains("PENDING_REVIEW");
        String replaceRequest = UUID.randomUUID().toString();
        JsonNode replaced = data(replace(f, id, replaceRequest, "A-01", f.alternate()));
        String replacement = replaced.path("replacementAllocationId").asText();
        actualPick(f, replacement, f.alternate(), 8);
        call(f, get(WMS + "/sales-picking/{id}", f.outbound()), PICK)
                .andExpect(jsonPath("$.data.status").value("PICKED"))
                .andExpect(jsonPath("$.data.allowBatchSubstitution").value(true))
                .andExpect(jsonPath("$.data.picks.length()").value(2));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(20)).andExpect(jsonPath("$.data[0].available").value(0));
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":2,\"missingQuantity\":6,\"remark\":\"复盘完成\"}"), COUNT)
                .andExpect(status().isOk());
        confirm(f, id, PICK).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        confirm(f, id, APPROVE).andExpect(status().isOk()).andExpect(jsonPath("$.data.confirmedBy").value("warehouse-supervisor"));
        confirm(f, id, APPROVE).andExpect(status().isOk());
        report(f, id, 8).andExpect(status().isOk());
        replace(f, id, replaceRequest, "A-01", f.alternate()).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(22)).andExpect(jsonPath("$.data[0].reserved").value(20))
                .andExpect(jsonPath("$.data[0].frozen").value(0)).andExpect(jsonPath("$.data[0].pendingReview").value(0))
                .andExpect(jsonPath("$.data[0].available").value(2));
        JsonNode transactions = data(call(f, get(WMS + "/inventory-ledger/transactions").param("documentId", id)));
        assertThat(transactions.size()).isEqualTo(3);
        assertThat(transactions.findValuesAsText("transactionType")).containsExactlyInAnyOrder("SHORT_PICK_REPORT", "SHORT_PICK_REPLACE", "STOCK_REVIEW");
        JsonNode adjustment = transactions.get(0);
        assertThat(adjustment.path("transactionType").asText()).isEqualTo("STOCK_REVIEW");
        assertThat(adjustment.path("operatorId").asText()).isEqualTo("warehouse-supervisor");
        assertThat(adjustment.path("pendingReviewDelta").asInt()).isEqualTo(-8);
        JsonNode picked = data(call(f, get(WMS + "/sales-picking/{id}", f.outbound()), PICK));
        var packedLines = json.createArrayNode();
        for (JsonNode pick : picked.path("picks")) packedLines.addObject().put("pickId", pick.path("requestId").asText())
                .put("quantity", pick.path("quantity").asInt());
        String packageId = UUID.randomUUID().toString();
        String dispatch = WMS + "/sales-dispatch";
        call(f, post(dispatch + "/outbounds/{id}/packages", f.outbound()).content("""
                {"packageId":"%s","lines":%s}
                """.formatted(packageId, packedLines)), "wms:ship:write").andExpect(status().isOk());
        call(f, post(dispatch + "/packages/{id}/weight", packageId).content("{\"weightKg\":0.5}"), "wms:ship:write").andExpect(status().isOk());
        call(f, post(dispatch + "/packages/{id}/ship", packageId).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"SHORT-09\"}"), "wms:ship:write")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.omsSyncStatus").value("SYNCED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(2)).andExpect(jsonPath("$.data[0].reserved").value(0));
        call(f, get(SALES + "/{id}/warehouse-shipments", f.salesOrder())).andExpect(jsonPath("$.data.shippedQuantity").value(20));
        JsonNode trace = data(call(f, get(dispatch + "/trace").param("packageId", packageId)));
        assertThat(trace.findValuesAsText("batchNo")).containsExactlyInAnyOrder(f.original(), f.alternate());
        assertThat(trace.findValues("quantity").stream().map(JsonNode::asInt).toList()).containsExactlyInAnyOrder(12, 8);
    }

    @Test
    void forbiddenInvalidOrRepeatedOperationsDoNotReleaseTheReviewStock() throws Exception {
        Fixture f = fixture(false);
        actualPick(f, f.allocation(), f.original(), 12);
        String id = UUID.randomUUID().toString();
        report(f, id, 9).andExpect(jsonPath("$.code").value("SHORT_PICK_QUANTITY_INVALID"));
        report(f, id, 8).andExpect(status().isOk());
        report(f, id, 7).andExpect(jsonPath("$.code").value("SHORT_PICK_REQUEST_CONFLICT"));
        replace(f, id, UUID.randomUUID().toString(), "A-01", f.alternate())
                .andExpect(jsonPath("$.code").value("SHORT_PICK_SUBSTITUTION_FORBIDDEN"));
        call(f, post(SALES + "/{id}/warehouse-work", f.salesOrder()).content("""
                {"warehouseId":"%s","allowBatchSubstitution":true}
                """.formatted(f.warehouse())), "oms:warehouse:write")
                .andExpect(jsonPath("$.code").value("SALES_DISPATCH_CONFLICT"));
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), PICK)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        confirm(f, id, APPROVE).andExpect(jsonPath("$.code").value("STOCK_REVIEW_COUNT_REQUIRED"));
        call(f, post(WMS + "/sales-picking/{id}/picks", f.outbound()).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":1}
                """.formatted(UUID.randomUUID(), f.allocation(), f.original())), PICK)
                .andExpect(jsonPath("$.code").value("SALES_PICK_QUANTITY_INVALID"));
        mvc.perform(get(SHORT + "/work").header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken("foreign-tenant", "foreign-user", "other", Set.of(APPROVE))))
                .andExpect(status().isForbidden());
        call(f, get(SHORT + "/work"), COUNT).andExpect(jsonPath("$.data.canApprove").value(false))
                .andExpect(jsonPath("$.data.cases.length()").value(1));
        call(f, get(SHORT + "/work")).andExpect(jsonPath("$.data.cases").isEmpty());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].reserved").value(12))
                .andExpect(jsonPath("$.data[0].frozen").value(8)).andExpect(jsonPath("$.data[0].pendingReview").value(8));

        Fixture allowed = fixture(true);
        actualPick(allowed, allowed.allocation(), allowed.original(), 12);
        String another = UUID.randomUUID().toString();
        report(allowed, another, 8).andExpect(status().isOk());
        replace(allowed, another, UUID.randomUUID().toString(), "A-02", allowed.alternate())
                .andExpect(jsonPath("$.code").value("SHORT_PICK_REPLACEMENT_INVALID"));
        replace(allowed, another, UUID.randomUUID().toString(), "A-01", UUID.randomUUID().toString())
                .andExpect(jsonPath("$.code").value("SHORT_PICK_REPLACEMENT_UNAVAILABLE"));
        stock(allowed).andExpect(jsonPath("$.data[0].reserved").value(12)).andExpect(jsonPath("$.data[0].frozen").value(8));
    }

    @Test
    void concurrentReplacementAndOtherOrdersCannotAllocatePendingReviewAndApprovalPostsOnlyOnce() throws Exception {
        Fixture f = fixture(true);
        actualPick(f, f.allocation(), f.original(), 12);
        String id = UUID.randomUUID().toString();
        report(f, id, 8).andExpect(status().isOk());
        String otherOrder = salesOrder(f, 8);
        List<JsonNode> result = race(() -> body(replace(f, id, UUID.randomUUID().toString(), "A-01", f.alternate())),
                () -> body(call(f, post(SALES + "/{id}/warehouse-work", otherOrder)
                        .content("{\"warehouseId\":\"" + f.warehouse() + "\"}"), "oms:warehouse:write")));
        assertThat(result.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(result.stream().filter(r -> !r.path("success").asBoolean()).map(r -> r.path("code").asText()).toList())
                .allSatisfy(code -> assertThat(code).isIn("SHORT_PICK_REPLACEMENT_UNAVAILABLE", "SALES_PICK_INSUFFICIENT_STOCK"));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(20)).andExpect(jsonPath("$.data[0].pendingReview").value(8))
                .andExpect(jsonPath("$.data[0].available").value(0));
        JsonNode original = data(call(f, get(WMS + "/inventory-ledger/balances").param("batchNo", f.original())));
        assertThat(original.findValuesAsText("status")).containsExactlyInAnyOrder("PICKED", "PENDING_REVIEW");
        String extra = salesOrder(f, 1);
        call(f, post(SALES + "/{id}/warehouse-work", extra).content("{\"warehouseId\":\"" + f.warehouse() + "\"}"), "oms:warehouse:write")
                .andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), COUNT).andExpect(status().isOk());
        assertThat(race(() -> body(confirm(f, id, APPROVE)), () -> body(confirm(f, id, APPROVE))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(22)).andExpect(jsonPath("$.data[0].frozen").value(0))
                .andExpect(jsonPath("$.data[0].available").value(2));
        JsonNode ledger = data(call(f, get(WMS + "/inventory-ledger/transactions").param("documentId", id)));
        assertThat(ledger.findValuesAsText("transactionType").stream().filter("STOCK_REVIEW"::equals).count()).isEqualTo(1);
    }

    @Test
    void supervisorMustConfirmTheCurrentCountAndConfirmedCountsCannotBeEdited() throws Exception {
        Fixture f = fixture(true);
        actualPick(f, f.allocation(), f.original(), 12);
        String id = UUID.randomUUID().toString();
        report(f, id, 8).andExpect(status().isOk());
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":2,\"missingQuantity\":5}"), COUNT)
                .andExpect(jsonPath("$.code").value("STOCK_REVIEW_QUANTITY_INVALID"));
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), COUNT).andExpect(status().isOk());
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":3,\"missingQuantity\":5}"), COUNT).andExpect(status().isOk());
        confirm(f, id, APPROVE).andExpect(jsonPath("$.code").value("STOCK_REVIEW_COUNT_CHANGED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].frozen").value(8));
        call(f, post(SHORT + "/{id}/confirm", id).content("{\"foundQuantity\":3,\"missingQuantity\":5}"), APPROVE).andExpect(status().isOk());
        call(f, post(SHORT + "/{id}/count", id).content("{\"foundQuantity\":4,\"missingQuantity\":4}"), COUNT)
                .andExpect(jsonPath("$.code").value("STOCK_REVIEW_CONFIRMED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(23)).andExpect(jsonPath("$.data[0].reserved").value(12))
                .andExpect(jsonPath("$.data[0].available").value(11));
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
    private JsonNode body(ResultActions response) throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsString()); }

    private String salesOrder(Fixture f, int quantity) throws Exception {
        String order = data(call(f, post(SALES + "/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Buyer-%s","countryCode":"US",
                "shippingAddress":"1 Main Street","currency":"USD","lines":[{"lineId":"L1","sellerSku":"MUG-09",
                "title":"Mug","quantity":%d,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), quantity)))).path("orderId").asText();
        call(f, patch(SALES + "/{id}/paid", order)).andExpect(status().isOk());
        return order;
    }

    private Fixture fixture(boolean allow) throws Exception {
        Fixture f = new Fixture("short-pick-" + UUID.randomUUID(), null, null, null, null, null, null, null);
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Short pick supplier\",\"countryCode\":\"CN\"}")))
                .at("/supplier/supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"MUG-09","quantity":28,"unitCost":10}]}
                """.formatted(supplier)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        String wh = data(call(f, post(WMS + "/warehouses").content("{\"code\":\"SHORT09\",\"name\":\"Review Warehouse\",\"countryCode\":\"US\"}")))
                .path("warehouseId").asText();
        f = new Fixture(f.tenant(), wh, po, null, null, null, null, null);
        call(f, post(WMS + "/warehouses/{wh}/locations", wh).content("{\"locationCode\":\"A-01\",\"purpose\":\"GOOD\"}"), "wms:location:write")
                .andExpect(status().isOk());
        String[] batches = new String[2];
        for (int i = 0; i < 2; i++) {
            int quantity = i == 0 ? 20 : 8;
            batches[i] = data(call(f, post(WMS + "/purchase-receipts/{po}", po).content("""
                    {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":%d,"supplierBatchNo":"B%d"}]}
                    """.formatted(UUID.randomUUID(), wh, quantity, i)), "wms:receipt:write")).at("/lines/0/batchNo").asText();
            call(f, post(WMS + "/purchase-inspections/{b}/sample", batches[i])
                    .content("{\"checkedQuantity\":2,\"passedQuantity\":2,\"failedQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
            call(f, post(WMS + "/purchase-inspections/{b}/disposition", batches[i])
                    .content("{\"goodQuantity\":" + quantity + ",\"defectiveQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
            call(f, post(WMS + "/purchase-putaways/{b}", batches[i]).content("""
                    {"requestId":"%s","locationCode":"A-01","quantity":%d}
                    """.formatted(UUID.randomUUID(), quantity)), "wms:putaway:write").andExpect(status().isOk());
        }
        String order = salesOrder(f, 20);
        JsonNode work = data(call(f, post(SALES + "/{id}/warehouse-work", order).content("""
                {"warehouseId":"%s","allowBatchSubstitution":%s}
                """.formatted(wh, allow)), "oms:warehouse:write"));
        return new Fixture(f.tenant(), wh, po, batches[0], batches[1], order, work.path("outboundId").asText(),
                work.at("/allocations/0/allocationId").asText());
    }
    private void actualPick(Fixture f, String allocation, String batch, int quantity) throws Exception {
        call(f, post(WMS + "/sales-picking/{id}/picks", f.outbound()).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":%d}
                """.formatted(UUID.randomUUID(), allocation, batch, quantity)), PICK).andExpect(status().isOk());
    }
    private ResultActions report(Fixture f, String id, int quantity) throws Exception {
        return call(f, post(SHORT + "/outbounds/{id}", f.outbound()).content("""
                {"shortPickId":"%s","allocationId":"%s","quantity":%d,"reason":"库位实物不足"}
                """.formatted(id, f.allocation(), quantity)), PICK);
    }
    private ResultActions replace(Fixture f, String id, String request, String location, String batch) throws Exception {
        return call(f, post(SHORT + "/{id}/replace", id).content("""
                {"requestId":"%s","locationCode":"%s","batchNo":"%s"}
                """.formatted(request, location, batch)), PICK);
    }
    private ResultActions confirm(Fixture f, String id, String permission) throws Exception {
        return call(f, post(SHORT + "/{id}/confirm", id).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), permission);
    }
    private ResultActions stock(Fixture f) throws Exception { return call(f, get(WMS + "/warehouses/{id}/inventory", f.warehouse())); }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        String user = Set.of(permissions).contains(APPROVE) ? "warehouse-supervisor" : "warehouse-clerk";
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken(f.tenant(), user, user, Set.of(permissions))).contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        var result = response.andReturn().getResponse();
        var body = json.readTree(result.getContentAsString());
        assertThat(result.getStatus()).as("响应业务码 %s", body.path("code").asText()).isEqualTo(200);
        return body.path("data");
    }
    private record Fixture(String tenant, String warehouse, String po, String original, String alternate,
                           String salesOrder, String outbound, String allocation) {}
}
