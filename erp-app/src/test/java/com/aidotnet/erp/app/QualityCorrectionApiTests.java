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
class QualityCorrectionApiTests {
    private static final String W = "/wms/api/in/v1";
    private static final String Q = W + "/quality-corrections";
    private static final String INSPECT = "wms:inspection:write";
    private static final String CORRECT = "wms:quality-correction:write";
    private static final String APPROVE = "wms:quality-correction:approve";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;

    @Test
    void unreservedGoodsReversePutawayThenWithdrawAndReinspectWithoutChangingReceiptOrOnHand() throws Exception {
        Fixture f = fixture();
        String id = UUID.randomUUID().toString();
        pause(f, id).andExpect(status().isOk()).andExpect(jsonPath("$.data.correction.quantity").value(20));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].available").value(8))
                .andExpect(jsonPath("$.data[0].qualityHold").value(20));
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_PHYSICAL_REQUIRED"));
        String request = UUID.randomUUID().toString();
        reverse(f, id, request, 20).andExpect(status().isOk());
        reverse(f, id, request, 20).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].pendingPutaway").value(20)).andExpect(jsonPath("$.data[0].qualityHold").value(0));
        call(f, post(Q + "/{id}/withdraw", id), CORRECT).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].pendingInspection").value(20)).andExpect(jsonPath("$.data[0].pendingPutaway").value(0));
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":18,\"defectiveQuantity\":2}"), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].pendingInspection").value(0))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(18)).andExpect(jsonPath("$.data[0].quarantined").value(2));
        JsonNode detail = data(call(f, get(Q + "/{id}", id), CORRECT));
        assertThat(detail.at("/correction/originalInspection").asText()).contains("sample_checked", "disposed_at");
        assertThat(detail.at("/correction/status").asText()).isEqualTo("COMPLETED");
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", id)));
        assertThat(ledger.findValuesAsText("transactionType")).contains("QUALITY_HOLD", "PUTAWAY_REVERSE", "INSPECTION_WITHDRAW", "QUALITY_REINSPECT");
        assertThat(ledger.findValuesAsText("batchNo")).containsOnly(f.batch());
        JsonNode receipts = data(call(f, get(W + "/purchase-receipts").param("purchaseOrderId", f.po())));
        assertThat(receipts.findValues("quantity").stream().mapToInt(JsonNode::asInt).sum()).isEqualTo(28);
    }

    @Test
    void reservedUnpickedGoodsRequireSupervisorReleaseAndOrdersCanUseAnotherGoodBatch() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 8);
        String outbound = work.path("outboundId").asText();
        String allocation = work.at("/allocations/0/allocationId").asText();
        String id = UUID.randomUUID().toString();
        pause(f, id).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(8)).andExpect(jsonPath("$.data[0].qualityHold").value(12));
        pick(f, outbound, allocation, f.batch(), 1).andExpect(jsonPath("$.code").value("BATCH_QUALITY_HELD"));
        call(f, post(Q + "/{id}/release", id), CORRECT).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(Q + "/{id}/release", id), APPROVE).andExpect(status().isOk());
        call(f, post(Q + "/{id}/release", id), APPROVE).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].qualityHold").value(20));
        call(f, get(W + "/sales-picking/{id}", outbound), "wms:pick:write")
                .andExpect(jsonPath("$.data.status").value("NEEDS_ALLOCATION"))
                .andExpect(jsonPath("$.data.allocations[0].cancelledQuantity").value(8));
        JsonNode replacement = data(call(f, post(W + "/sales-picking/{id}/reallocate", outbound), "wms:pick:write"));
        assertThat(replacement.path("allocations").findValuesAsText("batchNo")).contains(f.batch(), f.alternate());
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(8)).andExpect(jsonPath("$.data[0].available").value(0));
        call(f, post(W + "/sales-picking/{id}/reallocate", outbound), "wms:pick:write").andExpect(status().isOk());
        reverse(f, id, UUID.randomUUID().toString(), 20).andExpect(status().isOk());
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].pendingInspection").value(20));
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", id)));
        assertThat(ledger.findValuesAsText("operatorId")).contains("warehouse-supervisor");
        assertThat(ledger.findValuesAsText("transactionType").stream().filter("QUALITY_RELEASE"::equals).count()).isEqualTo(1);
    }

    @Test
    void pickedGoodsReturnDirectlyFromStagingWhileShippedHistoryAndReceiptRemainTrue() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 20);
        String outbound = work.path("outboundId").asText(), allocation = work.at("/allocations/0/allocationId").asText();
        String pick = data(pick(f, outbound, allocation, f.batch(), 20)).path("requestId").asText();
        String shipped = pack(f, outbound, pick, 5), pending = pack(f, outbound, pick, 15);
        ship(f, shipped).andExpect(status().isOk());
        String id = UUID.randomUUID().toString();
        pause(f, id).andExpect(status().isOk()).andExpect(jsonPath("$.data.correction.quantity").value(15));
        ship(f, pending).andExpect(jsonPath("$.code").value("BATCH_QUALITY_HELD"));
        String unpickRequest = UUID.randomUUID().toString();
        String unpickBody = """
                {"requestId":"%s","pickId":"%s","locationCode":"A-01","batchNo":"%s"}
                """.formatted(unpickRequest, pick, f.batch());
        call(f, post(Q + "/{id}/unpick", id).content(unpickBody), CORRECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/unpick", id).content(unpickBody), CORRECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(23)).andExpect(jsonPath("$.data[0].reserved").value(0))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(15)).andExpect(jsonPath("$.data[0].pendingPutaway").value(0));
        call(f, get(W + "/sales-dispatch/packages/{id}", pending)).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        ship(f, pending).andExpect(jsonPath("$.code").value("SALES_PACKAGE_CANCELLED"));
        ship(f, shipped).andExpect(status().isOk());
        call(f, get(W + "/sales-picking/{id}", outbound), "wms:pick:write")
                .andExpect(jsonPath("$.data.picks[0].quantity").value(20)).andExpect(jsonPath("$.data.picks[0].returnedQuantity").value(15))
                .andExpect(jsonPath("$.data.allocations[0].pickedQuantity").value(5)).andExpect(jsonPath("$.data.allocations[0].cancelledQuantity").value(15));
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":14,\"defectiveQuantity\":1}"), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].pendingPutaway").value(14)).andExpect(jsonPath("$.data[0].quarantined").value(1))
                .andExpect(jsonPath("$.data[0].onHand").value(23));
        call(f, get(W + "/sales-dispatch/trace").param("batchNo", f.batch())).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].quantity").value(5));
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", id)));
        assertThat(ledger.findValuesAsText("transactionType")).contains("SALES_UNPICK");
        assertThat(ledger.findValuesAsText("remark")).anySatisfy(s -> assertThat(s).contains("发货暂存", "待检", pick));
    }

    @Test
    void mixedPickedAndUnpickedGoodsKeepEachPhysicalReturnSeparateAndRejectConflictingOrForeignRequests() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 20);
        String outbound = work.path("outboundId").asText(), allocation = work.at("/allocations/0/allocationId").asText();
        String pick = data(pick(f, outbound, allocation, f.batch(), 12)).path("requestId").asText();
        String id = UUID.randomUUID().toString();
        pause(f, id).andExpect(status().isOk());
        pause(f, id).andExpect(status().isOk());
        pause(f, UUID.randomUUID().toString()).andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_ACTIVE"));
        call(f, post(Q + "/{id}/release", id), APPROVE).andExpect(status().isOk());
        String reverse = UUID.randomUUID().toString();
        reverse(f, id, reverse, 8).andExpect(status().isOk());
        reverse(f, id, reverse, 7).andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_REQUEST_CONFLICT"));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(12)).andExpect(jsonPath("$.data[0].pendingPutaway").value(8))
                .andExpect(jsonPath("$.data[0].qualityHold").value(0)).andExpect(jsonPath("$.data[0].onHand").value(28));
        call(f, post(W + "/purchase-putaways/{id}", f.batch()).content("""
                {"requestId":"%s","locationCode":"A-01","quantity":8}
                """.formatted(UUID.randomUUID())), "wms:putaway:write").andExpect(jsonPath("$.code").value("BATCH_QUALITY_HELD"));
        call(f, post(Q + "/{id}/unpick", id).content("""
                {"requestId":"%s","pickId":"%s","locationCode":"A-02","batchNo":"%s"}
                """.formatted(UUID.randomUUID(), pick, f.batch())), CORRECT).andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_SCAN_INVALID"));
        call(f, post(Q + "/{id}/unpick", id).content("""
                {"requestId":"%s","pickId":"%s","locationCode":"A-01","batchNo":"%s"}
                """.formatted(UUID.randomUUID(), pick, f.batch())), CORRECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].pendingInspection").value(12)).andExpect(jsonPath("$.data[0].pendingPutaway").value(8));
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].pendingInspection").value(20)).andExpect(jsonPath("$.data[0].pendingPutaway").value(0))
                .andExpect(jsonPath("$.data[0].reserved").value(0));
        call(f, get(Q + "/work")).andExpect(jsonPath("$.data.cases").isEmpty());
        call(new Fixture("foreign-" + UUID.randomUUID(), null, null, null, null, null, null), get(Q + "/{id}", id), CORRECT)
                .andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_NOT_FOUND"));
        mvc.perform(get(Q + "/work").header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken("foreign", "x", "x", Set.of(APPROVE)))).andExpect(status().isForbidden());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":19,\"defectiveQuantity\":0}"), INSPECT)
                .andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_QUANTITY_INVALID"));
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":20,\"defectiveQuantity\":0}"), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":20,\"defectiveQuantity\":0}"), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":19,\"defectiveQuantity\":1}"), INSPECT)
                .andExpect(jsonPath("$.code").value("QUALITY_CORRECTION_REQUEST_CONFLICT"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(28)).andExpect(jsonPath("$.data[0].pendingPutaway").value(20));
    }

    @Test
    void concurrentPauseAndShipmentSerializeAndConcurrentReleaseOrUnpickPostOnlyOnce() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 8);
        String outbound = work.path("outboundId").asText(), allocation = work.at("/allocations/0/allocationId").asText();
        String pick = data(pick(f, outbound, allocation, f.batch(), 8)).path("requestId").asText();
        String packageId = pack(f, outbound, pick, 8), id = UUID.randomUUID().toString();
        List<JsonNode> result = race(() -> body(pause(f, id)), () -> body(ship(f, packageId)));
        assertThat(result.get(0).path("success").asBoolean()).isTrue();
        boolean shipped = result.get(1).path("success").asBoolean();
        if (!shipped) assertThat(result.get(1).path("code").asText()).isEqualTo("BATCH_QUALITY_HELD");
        int remaining = shipped ? 12 : 20;
        call(f, get(Q + "/{id}", id), CORRECT).andExpect(jsonPath("$.data.correction.quantity").value(remaining));
        if (!shipped) {
            String unpickBody = """
                    {"requestId":"%s","pickId":"%s","locationCode":"A-01","batchNo":"%s"}
                    """.formatted(UUID.randomUUID(), pick, f.batch());
            assertThat(race(() -> body(call(f, post(Q + "/{id}/unpick", id).content(unpickBody), CORRECT)),
                    () -> body(call(f, post(Q + "/{id}/unpick", id).content(unpickBody), CORRECT))))
                    .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        }
        reverse(f, id, UUID.randomUUID().toString(), 12).andExpect(status().isOk());
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(shipped ? 20 : 28))
                .andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].pendingInspection").value(remaining));
        Fixture reserved = fixture();
        dispatch(reserved, 8);
        String reservedId = UUID.randomUUID().toString();
        pause(reserved, reservedId).andExpect(status().isOk());
        assertThat(race(() -> body(call(reserved, post(Q + "/{id}/release", reservedId), APPROVE)),
                () -> body(call(reserved, post(Q + "/{id}/release", reservedId), APPROVE))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        JsonNode ledger = data(call(reserved, get(W + "/inventory-ledger/transactions").param("documentId", reservedId)));
        assertThat(ledger.findValuesAsText("transactionType").stream().filter("QUALITY_RELEASE"::equals).count()).isEqualTo(1);
        stock(reserved).andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].qualityHold").value(20));
    }

    @Test
    void pendingShortageReviewNeverPreventsQualityHoldAndFoundStockStaysHeldUntilReinspection() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 20);
        String outbound = work.path("outboundId").asText(), allocation = work.at("/allocations/0/allocationId").asText();
        String picked = data(pick(f, outbound, allocation, f.batch(), 12)).path("requestId").asText();
        String packageId = pack(f, outbound, picked, 12), review = UUID.randomUUID().toString();
        call(f, post(W + "/short-picks/outbounds/{id}", outbound).content("""
                {"shortPickId":"%s","allocationId":"%s","quantity":8,"reason":"原位缺货"}
                """.formatted(review, allocation)), "wms:pick:write").andExpect(status().isOk());
        String id = UUID.randomUUID().toString();
        pause(f, id).andExpect(status().isOk());
        ship(f, packageId).andExpect(jsonPath("$.code").value("BATCH_QUALITY_HELD"));
        call(f, post(W + "/short-picks/{id}/count", review).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), "wms:stock-review:write").andExpect(status().isOk());
        call(f, post(W + "/short-picks/{id}/confirm", review).content("{\"foundQuantity\":2,\"missingQuantity\":6}"), "wms:stock-review:approve").andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(22)).andExpect(jsonPath("$.data[0].reserved").value(12))
                .andExpect(jsonPath("$.data[0].frozen").value(2)).andExpect(jsonPath("$.data[0].qualityHold").value(2))
                .andExpect(jsonPath("$.data[0].available").value(8));
        call(f, get(Q + "/{id}", id), CORRECT).andExpect(jsonPath("$.data.correction.quantity").value(14));
        call(f, post(Q + "/{id}/unpick", id).content("""
                {"requestId":"%s","pickId":"%s","locationCode":"A-01","batchNo":"%s"}
                """.formatted(UUID.randomUUID(), picked, f.batch())), CORRECT).andExpect(status().isOk());
        reverse(f, id, UUID.randomUUID().toString(), 2).andExpect(status().isOk());
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":14,\"defectiveQuantity\":0}"), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(22)).andExpect(jsonPath("$.data[0].pendingPutaway").value(14));
    }

    @Test
    void entirelyMissingReviewCanCloseQualityCorrectionWithNoInventedInspectionStock() throws Exception {
        Fixture f = fixture();
        JsonNode work = dispatch(f, 20);
        String review = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        call(f, post(W + "/short-picks/outbounds/{id}", work.path("outboundId").asText()).content("""
                {"shortPickId":"%s","allocationId":"%s","quantity":20,"reason":"整批未找到"}
                """.formatted(review, work.at("/allocations/0/allocationId").asText())), "wms:pick:write").andExpect(status().isOk());
        pause(f, id).andExpect(status().isOk());
        call(f, post(W + "/short-picks/{id}/count", review).content("{\"foundQuantity\":0,\"missingQuantity\":20}"), "wms:stock-review:write").andExpect(status().isOk());
        call(f, post(W + "/short-picks/{id}/confirm", review).content("{\"foundQuantity\":0,\"missingQuantity\":20}"), "wms:stock-review:approve").andExpect(status().isOk());
        call(f, get(Q + "/{id}", id), CORRECT).andExpect(jsonPath("$.data.correction.quantity").value(0))
                .andExpect(jsonPath("$.data.correction.excludedMissingQuantity").value(20));
        call(f, post(Q + "/{id}/withdraw", id), INSPECT).andExpect(status().isOk());
        call(f, post(Q + "/{id}/inspection", id).content("{\"goodQuantity\":0,\"defectiveQuantity\":0}"), INSPECT).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(8)).andExpect(jsonPath("$.data[0].pendingInspection").value(0))
                .andExpect(jsonPath("$.data[0].pendingPutaway").value(0)).andExpect(jsonPath("$.data[0].available").value(8));
    }

    private List<JsonNode> race(Callable<JsonNode> a, Callable<JsonNode> b) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return a.call(); });
            var second = executor.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String pack(Fixture f, String outbound, String pick, int quantity) throws Exception {
        String id = UUID.randomUUID().toString();
        call(f, post(W + "/sales-dispatch/outbounds/{id}/packages", outbound).content("""
                {"packageId":"%s","lines":[{"pickId":"%s","quantity":%d}]}
                """.formatted(id, pick, quantity)), "wms:ship:write").andExpect(status().isOk());
        call(f, post(W + "/sales-dispatch/packages/{id}/weight", id).content("{\"weightKg\":0.5}"), "wms:ship:write").andExpect(status().isOk());
        return id;
    }
    private ResultActions ship(Fixture f, String id) throws Exception {
        return call(f, post(W + "/sales-dispatch/packages/{id}/ship", id).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"QC-10\"}"), "wms:ship:write");
    }

    private JsonNode dispatch(Fixture f, int quantity) throws Exception {
        String order = data(call(f, post("/oms/api/in/v1/orders/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Buyer-%s","countryCode":"US",
                "shippingAddress":"1 Main Street","currency":"USD","lines":[{"lineId":"L1","sellerSku":"MUG-10",
                "title":"Mug","quantity":%d,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), quantity)))).path("orderId").asText();
        call(f, patch("/oms/api/in/v1/orders/{id}/paid", order)).andExpect(status().isOk());
        return data(call(f, post("/oms/api/in/v1/orders/{id}/warehouse-work", order)
                .content("{\"warehouseId\":\"" + f.warehouse() + "\"}"), "oms:warehouse:write"));
    }
    private ResultActions pick(Fixture f, String outbound, String allocation, String batch, int quantity) throws Exception {
        return call(f, post(W + "/sales-picking/{id}/picks", outbound).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":%d}
                """.formatted(UUID.randomUUID(), allocation, batch, quantity)), "wms:pick:write");
    }

    private ResultActions pause(Fixture f, String id) throws Exception {
        return call(f, post(Q).content("{\"correctionId\":\"" + id + "\",\"batchNo\":\"" + f.batch() + "\",\"reason\":\"原判定错误，复查整批\"}"), CORRECT);
    }
    private ResultActions reverse(Fixture f, String id, String request, int quantity) throws Exception {
        return call(f, post(Q + "/{id}/reverse-putaway", id).content("""
                {"requestId":"%s","putawayTransactionId":"%s","locationCode":"A-01","quantity":%d}
                """.formatted(request, f.putaway(), quantity)), CORRECT);
    }
    private ResultActions stock(Fixture f) throws Exception { return call(f, get(W + "/warehouses/{id}/inventory", f.warehouse())); }
    private Fixture fixture() throws Exception {
        Fixture f = new Fixture("correction-" + UUID.randomUUID(), null, null, null, null, null, null);
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Correction supplier\",\"countryCode\":\"CN\"}"))).at("/supplier/supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[{"lineId":"L1","sellerSku":"MUG-10","quantity":28,"unitCost":10}]}
                """.formatted(supplier)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        String wh = data(call(f, post(W + "/warehouses").content("{\"code\":\"QC10\",\"name\":\"Correction Warehouse\",\"countryCode\":\"US\"}"))).path("warehouseId").asText();
        f = new Fixture(f.tenant(), wh, po, null, null, null, null);
        call(f, post(W + "/warehouses/{id}/locations", wh).content("{\"locationCode\":\"A-01\",\"purpose\":\"GOOD\"}"), "wms:location:write").andExpect(status().isOk());
        String batch = null, receipt = null, putaway = null, alternate = null;
        for (int i = 0; i < 2; i++) {
            int quantity = i == 0 ? 20 : 8;
            JsonNode received = data(call(f, post(W + "/purchase-receipts/{po}", po).content("""
                    {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":%d}]}
                    """.formatted(UUID.randomUUID(), wh, quantity)), "wms:receipt:write"));
            String current = received.at("/lines/0/batchNo").asText();
            call(f, post(W + "/purchase-inspections/{b}/sample", current).content("{\"checkedQuantity\":2,\"passedQuantity\":2,\"failedQuantity\":0}"), INSPECT).andExpect(status().isOk());
            call(f, post(W + "/purchase-inspections/{b}/disposition", current).content("{\"goodQuantity\":" + quantity + ",\"defectiveQuantity\":0}"), INSPECT).andExpect(status().isOk());
            JsonNode placed = data(call(f, post(W + "/purchase-putaways/{b}", current).content("""
                    {"requestId":"%s","locationCode":"A-01","quantity":%d}
                    """.formatted(UUID.randomUUID(), quantity)), "wms:putaway:write"));
            if (i == 0) { batch = current; receipt = received.path("receiptId").asText(); putaway = placed.path("transactionId").asText(); }
            else alternate = current;
        }
        return new Fixture(f.tenant(), wh, po, receipt, batch, alternate, putaway);
    }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        String user = Set.of(permissions).contains(APPROVE) ? "warehouse-supervisor" : Set.of(permissions).contains(INSPECT) ? "quality-inspector" : "warehouse-clerk";
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " + jwt.createToken(f.tenant(), user, user, Set.of(permissions)))
                .contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        var result = response.andReturn().getResponse();
        JsonNode body = json.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(result.getStatus()).as("业务码 %s", body.path("code").asText()).isEqualTo(200);
        return body.path("data");
    }
    private record Fixture(String tenant, String warehouse, String po, String receipt, String batch, String alternate, String putaway) {}
}
