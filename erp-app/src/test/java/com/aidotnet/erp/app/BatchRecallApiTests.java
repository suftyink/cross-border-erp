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
class BatchRecallApiTests {
    private static final String W = "/wms/api/in/v1", C = "/crm/api/in/v1/batch-recalls", R = "/crm/api/in/v1/sales-returns";
    private static final String RECALL = "crm:recall:write", RETURNS = "crm:return:write", RECEIVE = "wms:return:write";
    private static final String CLOSE = "crm:recall:close";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtProvider jwt;

    @Test
    void recallUsesActualShippedBatchAndPartialReturnsRemainPendingInspectionUntilBatchConfirmation() throws Exception {
        Fixture f = fixture();
        String recallId = UUID.randomUUID().toString();
        JsonNode recall = data(createRecall(f, recallId));
        String line = recall.at("/lines/0/lineId").asText();
        assertThat(recall.at("/lines/0/expectedQuantity").asInt()).isEqualTo(10);
        assertThat(recall.at("/lines/0/salesOrderId").asText()).isEqualTo(f.salesOrder());
        assertThat(recall.at("/lines/0/outboundId").asText()).isEqualTo(f.outbound());
        assertThat(recall.path("sources").findValuesAsText("batchNo")).containsOnly(f.batch());
        call(f, post(C + "/{id}/lines/{line}/notifications", recallId, line).content("""
                {"notificationId":"%s","channel":"PLATFORM","content":"已通知客户退回目标批次"}
                """.formatted(UUID.randomUUID())), RECALL).andExpect(status().isOk());
        String returnId = UUID.randomUUID().toString();
        data(createReturn(f, recallId, line, returnId, 10));
        String receiptId = UUID.randomUUID().toString();
        receive(f, returnId, receiptId, f.batch(), 4).andExpect(status().isOk());
        receive(f, returnId, receiptId, f.batch(), 4).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].pendingInspection").value(4))
                .andExpect(jsonPath("$.data[0].available").value(0));
        call(f, get(C + "/{id}", recallId), RECALL).andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(4))
                .andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(0)).andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(10));
        verify(f, returnId, receiptId, f.batch()).andExpect(status().isOk());
        verify(f, returnId, receiptId, f.batch()).andExpect(status().isOk());
        call(f, get(C + "/{id}", recallId), RECALL).andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(4))
                .andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(6));
        String second = UUID.randomUUID().toString();
        receive(f, returnId, second, f.batch(), 6).andExpect(status().isOk());
        verify(f, returnId, second, f.batch()).andExpect(status().isOk());
        call(f, get(C + "/{id}", recallId), RECALL).andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(10))
                .andExpect(jsonPath("$.data.lines[0].status").value("COMPLETED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(10)).andExpect(jsonPath("$.data[0].pendingInspection").value(10))
                .andExpect(jsonPath("$.data[0].frozen").value(10)).andExpect(jsonPath("$.data[0].available").value(0));
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", returnId)));
        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.findValuesAsText("transactionType")).containsOnly("SALES_RETURN_RECEIVE");
        call(f, get(W + "/sales-dispatch/trace").param("batchNo", f.batch())).andExpect(jsonPath("$.data[0].quantity").value(10));
        call(f, get(R + "/{id}", returnId), RETURNS).andExpect(jsonPath("$.data.receipts.length()").value(2));
    }

    @Test
    void limitsAndRolesProtectTargetBatchAndRepeatedOrForeignCommandsLeaveStockUnchanged() throws Exception {
        Fixture f = fixture(); String id = UUID.randomUUID().toString();
        JsonNode recall = data(createRecall(f, id)); String line = recall.at("/lines/0/lineId").asText();
        createRecall(f, id).andExpect(jsonPath("$.data.lines[0].lineId").value(line));
        createRecall(f, UUID.randomUUID().toString()).andExpect(jsonPath("$.code").value("RECALL_NO_SHIPMENTS"));
        String returnId = UUID.randomUUID().toString();
        createReturn(f, id, line, returnId, 11).andExpect(jsonPath("$.code").value("SALES_RETURN_QUANTITY_INVALID"));
        createReturn(f, id, line, returnId, 10).andExpect(status().isOk());
        createReturn(f, id, line, returnId, 9).andExpect(jsonPath("$.code").value("SALES_RETURN_REQUEST_CONFLICT"));
        createReturn(f, id, line, UUID.randomUUID().toString(), 1).andExpect(jsonPath("$.code").value("SALES_RETURN_QUANTITY_INVALID"));
        String receipt = UUID.randomUUID().toString();
        receive(f, returnId, receipt, f.otherBatch(), 4).andExpect(jsonPath("$.code").value("SALES_RETURN_BATCH_MISMATCH"));
        receive(f, returnId, receipt, f.batch(), 11).andExpect(jsonPath("$.code").value("SALES_RETURN_RECEIPT_EXCEEDED"));
        call(f, post(W + "/sales-returns/{id}/receipts", returnId).content("""
                {"receiptId":"%s","batchNo":"%s","quantity":4}
                """.formatted(receipt, f.batch())), RETURNS).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(0));
        receive(f, returnId, receipt, f.batch(), 4).andExpect(status().isOk());
        receive(f, returnId, receipt, f.batch(), 3).andExpect(jsonPath("$.code").value("SALES_RETURN_REQUEST_CONFLICT"));
        verify(f, returnId, receipt, f.otherBatch()).andExpect(jsonPath("$.code").value("SALES_RETURN_BATCH_MISMATCH"));
        call(f, post(R + "/{id}/receipts/{receipt}/verify", returnId, receipt).content("{\"batchNo\":\"" + f.batch() + "\"}"), RECEIVE)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, get(C + "/work")).andExpect(jsonPath("$.data.recalls").isEmpty());
        Fixture other = new Fixture("foreign-" + UUID.randomUUID(), null, null, null, null, null, null);
        call(other, get(R + "/{id}", returnId), RETURNS).andExpect(jsonPath("$.code").value("SALES_RETURN_NOT_FOUND"));
        call(other, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.code").value("RECALL_NOT_FOUND"));
        mvc.perform(get(C + "/work").header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " + jwt.createToken(other.tenant(), "x", "x", Set.of(RECALL))))
                .andExpect(status().isForbidden());
        JsonNode balances = data(call(f, get(W + "/inventory-ledger/balances").param("batchNo", f.batch())));
        assertThat(balances.size()).isEqualTo(1);
        assertThat(balances.get(0).path("sourceType").asText()).isEqualTo("SALES_RETURN");
        assertThat(balances.get(0).path("sourceDocumentId").asText()).isEqualTo(returnId);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].available").value(0));
    }

    @Test
    void concurrentAuthorizationsReceiptsAndVerificationsStayWithinOneShippedBatch() throws Exception {
        Fixture f = fixture(); String id = UUID.randomUUID().toString();
        JsonNode recall = data(createRecall(f, id)); String line = recall.at("/lines/0/lineId").asText();
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var created = race(() -> body(createReturn(f, id, line, a, 8)), () -> body(createReturn(f, id, line, b, 8)));
        assertThat(created.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        String returnId = created.stream().filter(r -> r.path("success").asBoolean()).findFirst().orElseThrow().at("/data/order/returnId").asText();
        String receipt = UUID.randomUUID().toString();
        assertThat(race(() -> body(receive(f, returnId, receipt, f.batch(), 4)), () -> body(receive(f, returnId, receipt, f.batch(), 4))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4));
        assertThat(race(() -> body(verify(f, returnId, receipt, f.batch())), () -> body(verify(f, returnId, receipt, f.batch()))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        var extra = race(() -> body(receive(f, returnId, UUID.randomUUID().toString(), f.batch(), 4)),
                () -> body(receive(f, returnId, UUID.randomUUID().toString(), f.batch(), 4)));
        assertThat(extra.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(8)).andExpect(jsonPath("$.data[0].pendingInspection").value(8));
        call(f, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(8))
                .andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(4)).andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(6));
    }

    @Test
    void concurrentRecallCreationClaimsEachShipmentOnceAndNotificationRetriesKeepTheSameAuditRecord() throws Exception {
        Fixture f = fixture();
        var created = race(() -> body(createRecall(f, UUID.randomUUID().toString())), () -> body(createRecall(f, UUID.randomUUID().toString())));
        assertThat(created.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        JsonNode recall = created.stream().filter(r -> r.path("success").asBoolean()).findFirst().orElseThrow().path("data");
        String id = recall.at("/recall/recallId").asText(), line = recall.at("/lines/0/lineId").asText();
        String notification = UUID.randomUUID().toString();
        String content = """
                {"notificationId":"%s","channel":"PHONE","content":"已联系客户确认退货"}
                """.formatted(notification);
        call(f, post(C + "/{id}/lines/{line}/notifications", id, line).content(content), RECALL).andExpect(status().isOk());
        call(f, post(C + "/{id}/lines/{line}/notifications", id, line).content(content), RECALL).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications.length()").value(1)).andExpect(jsonPath("$.data.lines[0].status").value("NOTIFIED"));
        call(f, post(C + "/{id}/lines/{line}/notifications", id, line).content(content.replace("PHONE", "EMAIL")), RECALL)
                .andExpect(jsonPath("$.code").value("RECALL_REQUEST_CONFLICT"));
        call(f, post(C + "/{id}/lines/{line}/notifications", id, UUID.randomUUID()).content(content), RECALL)
                .andExpect(jsonPath("$.code").value("RECALL_LINE_NOT_FOUND"));
        call(f, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.data.sources.length()").value(1))
                .andExpect(jsonPath("$.data.sources[0].quantity").value(10));
    }

    @Test
    void unidentifiedReceiptIsQuarantinedWithoutClaimingTheExpectedBatchAndIdentificationPostsOnlyAStateTransfer() throws Exception {
        Fixture f = fixture(6, 4);
        call(f, post(W + "/warehouses/{id}/locations", f.warehouse()).content("{\"locationCode\":\"Q-01\",\"purpose\":\"QUARANTINE\"}"), "wms:location:write").andExpect(status().isOk());
        String id = UUID.randomUUID().toString(); JsonNode recall = data(createRecall(f, id));
        String line = recall.at("/lines/0/lineId").asText(), ret = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString();
        createReturn(f, id, line, ret, 6).andExpect(status().isOk());
        unidentified(f, ret, receipt, 4, "Q-01").andExpect(status().isOk()).andExpect(jsonPath("$.data.batchNo").isEmpty());
        unidentified(f, ret, receipt, 4, "Q-01").andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].quarantined").value(4))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(0)).andExpect(jsonPath("$.data[0].available").value(0));
        call(f, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.data.lines[0].expectedQuantity").value(6))
                .andExpect(jsonPath("$.data.lines[0].unidentifiedQuantity").value(4)).andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(0));
        call(f, get(W + "/inventory-ledger/balances").param("batchNo", f.batch())).andExpect(jsonPath("$.data").isEmpty());
        verify(f, ret, receipt, f.batch()).andExpect(status().isOk());
        verify(f, ret, receipt, f.batch()).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].frozen").value(4))
                .andExpect(jsonPath("$.data[0].quarantined").value(0)).andExpect(jsonPath("$.data[0].pendingInspection").value(4));
        call(f, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(4))
                .andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(2)).andExpect(jsonPath("$.data.lines[0].unidentifiedQuantity").value(0));
        call(f, get(R + "/{id}", ret), RETURNS).andExpect(jsonPath("$.data.receipts[0].batchNo").isEmpty())
                .andExpect(jsonPath("$.data.receipts[0].identifiedBatchNo").value(f.batch()))
                .andExpect(jsonPath("$.data.receipts[0].quarantineLocationCode").value("Q-01"));
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", ret)));
        assertThat(ledger.size()).isEqualTo(2);
        assertThat(ledger.findValuesAsText("transactionType")).containsExactlyInAnyOrder("SALES_RETURN_RECEIVE", "SALES_RETURN_IDENTIFY");
        call(f, get(W + "/sales-dispatch/trace").param("batchNo", f.batch())).andExpect(jsonPath("$.data[0].quantity").value(6));
    }

    @Test
    void exceptionClosureRequiresAuthorityReasonAndCurrentCountsAndRetainsAnImmutableSnapshotDuringLaterReturnHandling() throws Exception {
        Fixture f = fixture(6, 4);
        call(f, post(W + "/warehouses/{id}/locations", f.warehouse()).content("{\"locationCode\":\"Q-01\",\"purpose\":\"QUARANTINE\"}"), "wms:location:write").andExpect(status().isOk());
        String id = UUID.randomUUID().toString(), ret = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString();
        String line = data(createRecall(f, id)).at("/lines/0/lineId").asText();
        createReturn(f, id, line, ret, 6).andExpect(status().isOk());
        unidentified(f, ret, receipt, 4, "Q-01").andExpect(status().isOk());
        close(f, id, line, "多次核查仍无法识别批次", 4, 0, 4, RETURNS).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        close(f, id, line, " ", 4, 0, 4, CLOSE).andExpect(status().isBadRequest());
        close(f, id, line, "多次核查仍无法识别批次", 4, 0, 3, CLOSE).andExpect(jsonPath("$.code").value("RECALL_CLOSE_SNAPSHOT_CHANGED"));
        close(f, id, line, "多次核查仍无法识别批次", 4, 0, 4, CLOSE).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].status").value("EXCEPTION_CLOSED"))
                .andExpect(jsonPath("$.data.lines[0].closedExpectedQuantity").value(6))
                .andExpect(jsonPath("$.data.lines[0].closedConfirmedQuantity").value(0))
                .andExpect(jsonPath("$.data.lines[0].closedUnidentifiedQuantity").value(4))
                .andExpect(jsonPath("$.data.lines[0].closedBy").value("recall-manager"));
        close(f, id, line, "多次核查仍无法识别批次", 4, 0, 4, CLOSE).andExpect(status().isOk());
        close(f, id, line, "换个原因", 4, 0, 4, CLOSE).andExpect(jsonPath("$.code").value("RECALL_ALREADY_CLOSED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].quarantined").value(4))
                .andExpect(jsonPath("$.data[0].available").value(0));
        createReturn(f, id, line, UUID.randomUUID().toString(), 1).andExpect(jsonPath("$.code").value("RECALL_LINE_CLOSED"));
        createReturn(f, id, line, ret, 6).andExpect(status().isOk());
        verify(f, ret, receipt, f.batch()).andExpect(status().isOk());
        unidentified(f, ret, UUID.randomUUID().toString(), 1, "Q-01").andExpect(status().isOk());
        call(f, get(C + "/{id}", id), CLOSE).andExpect(jsonPath("$.data.lines[0].status").value("EXCEPTION_CLOSED"))
                .andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(4)).andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(5))
                .andExpect(jsonPath("$.data.lines[0].closedReceivedQuantity").value(4)).andExpect(jsonPath("$.data.lines[0].closedConfirmedQuantity").value(0))
                .andExpect(jsonPath("$.data.lines[0].closedUnidentifiedQuantity").value(4));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(5)).andExpect(jsonPath("$.data[0].pendingInspection").value(4))
                .andExpect(jsonPath("$.data[0].quarantined").value(1)).andExpect(jsonPath("$.data[0].frozen").value(5));
    }

    @Test
    void unidentifiedReceiptsRequireExplicitModeARealQuarantineBinAndReasonAndCannotBeAllocated() throws Exception {
        Fixture f = fixture(6, 4); String id = UUID.randomUUID().toString(), ret = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString();
        String line = data(createRecall(f, id)).at("/lines/0/lineId").asText(); createReturn(f, id, line, ret, 6).andExpect(status().isOk());
        unidentified(f, ret, receipt, 4, "A-01").andExpect(jsonPath("$.code").value("SALES_RETURN_QUARANTINE_INVALID"));
        unidentified(f, ret, receipt, 4, "Q-99").andExpect(jsonPath("$.code").value("SALES_RETURN_QUARANTINE_INVALID"));
        call(f, post(W + "/sales-returns/{id}/receipts", ret).content("""
                {"receiptId":"%s","quantity":4,"unidentified":true,"quarantineLocationCode":"Q-01","unidentifiedReason":" "}
                """.formatted(receipt)), RECEIVE).andExpect(jsonPath("$.code").value("SALES_RETURN_RECEIPT_INVALID"));
        call(f, post(W + "/sales-returns/{id}/receipts", ret).content("{\"receiptId\":\"" + receipt + "\",\"quantity\":4}"), RECEIVE)
                .andExpect(jsonPath("$.code").value("SALES_RETURN_RECEIPT_INVALID"));
        call(f, post(W + "/warehouses/{id}/locations", f.warehouse()).content("{\"locationCode\":\"Q-01\",\"purpose\":\"QUARANTINE\"}"), "wms:location:write").andExpect(status().isOk());
        unidentified(f, ret, receipt, 7, "Q-01").andExpect(jsonPath("$.code").value("SALES_RETURN_RECEIPT_EXCEEDED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(0));
        unidentified(f, ret, receipt, 4, "Q-01").andExpect(status().isOk());
        unidentified(f, ret, receipt, 4, "Q-99").andExpect(jsonPath("$.code").value("SALES_RETURN_REQUEST_CONFLICT"));
        verify(f, ret, receipt, f.otherBatch()).andExpect(jsonPath("$.code").value("SALES_RETURN_BATCH_MISMATCH"));
        call(f, get(W + "/inventory-ledger/balances").param("warehouseId", f.warehouse())).andExpect(jsonPath("$.data[0].status").value("UNIDENTIFIED_RETURN"))
                .andExpect(jsonPath("$.data[0].batchNo").isEmpty()).andExpect(jsonPath("$.data[0].locationCode").value("Q-01"));
        String order = data(call(f, post("/oms/api/in/v1/orders/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Buyer-%s","countryCode":"US","shippingAddress":"1 Main Street","currency":"USD",
                 "lines":[{"lineId":"L1","sellerSku":"MUG-11","title":"Mug","quantity":1,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID())))).path("orderId").asText();
        call(f, patch("/oms/api/in/v1/orders/{id}/paid", order)).andExpect(status().isOk());
        call(f, post("/oms/api/in/v1/orders/{id}/warehouse-work", order).content("{\"warehouseId\":\"" + f.warehouse() + "\"}"), "oms:warehouse:write")
                .andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        Fixture other = new Fixture("foreign-" + UUID.randomUUID(), null, null, null, null, null, null);
        close(other, id, line, "无法核实", 4, 0, 4, CLOSE).andExpect(jsonPath("$.code").value("RECALL_LINE_NOT_FOUND"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].quarantined").value(4));
    }

    @Test
    void concurrentUnidentifiedReceiptIdentificationAndClosureKeepOnePostingAndAConsistentClosureSnapshot() throws Exception {
        Fixture f = fixture(6, 4); String id = UUID.randomUUID().toString(), ret = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString();
        call(f, post(W + "/warehouses/{id}/locations", f.warehouse()).content("{\"locationCode\":\"Q-01\",\"purpose\":\"QUARANTINE\"}"), "wms:location:write").andExpect(status().isOk());
        String line = data(createRecall(f, id)).at("/lines/0/lineId").asText(); createReturn(f, id, line, ret, 6).andExpect(status().isOk());
        assertThat(race(() -> body(unidentified(f, ret, receipt, 4, "Q-01")), () -> body(unidentified(f, ret, receipt, 4, "Q-01"))))
                .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        var results = race(() -> body(verify(f, ret, receipt, f.batch())), () -> body(close(f, id, line, "无法继续核实，异常结案", 4, 0, 4, CLOSE)));
        assertThat(results.get(0).path("success").asBoolean()).isTrue();
        if (results.get(1).path("success").asBoolean()) {
            close(f, id, line, "无法继续核实，异常结案", 4, 0, 4, CLOSE).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lines[0].closedConfirmedQuantity").value(0)).andExpect(jsonPath("$.data.lines[0].closedUnidentifiedQuantity").value(4));
        } else assertThat(results.get(1).path("code").asText()).isEqualTo("RECALL_CLOSE_SNAPSHOT_CHANGED");
        verify(f, ret, receipt, f.batch()).andExpect(status().isOk());
        unidentified(f, ret, receipt, 4, "Q-01").andExpect(status().isOk());
        JsonNode ledger = data(call(f, get(W + "/inventory-ledger/transactions").param("documentId", ret)));
        assertThat(ledger.size()).isEqualTo(2);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(4)).andExpect(jsonPath("$.data[0].pendingInspection").value(4))
                .andExpect(jsonPath("$.data[0].quarantined").value(0)).andExpect(jsonPath("$.data[0].frozen").value(4));
        call(f, get(C + "/{id}", id), RECALL).andExpect(jsonPath("$.data.lines[0].confirmedQuantity").value(4));
    }

    private ResultActions close(Fixture f, String id, String line, String reason, int received, int confirmed, int unidentified, String permission) throws Exception {
        var body = json.createObjectNode().put("reason", reason).put("expectedReceivedQuantity", received)
                .put("expectedConfirmedQuantity", confirmed).put("expectedUnidentifiedQuantity", unidentified);
        return call(f, post(C + "/{id}/lines/{line}/exception-close", id, line).content(body.toString()), permission);
    }

    private ResultActions unidentified(Fixture f, String ret, String receipt, int quantity, String location) throws Exception {
        return call(f, post(W + "/sales-returns/{id}/receipts", ret).content("""
                {"receiptId":"%s","quantity":%d,"unidentified":true,"quarantineLocationCode":"%s","unidentifiedReason":"实物标签丢失"}
                """.formatted(receipt, quantity, location)), RECEIVE);
    }

    private List<JsonNode> race(Callable<JsonNode> a, Callable<JsonNode> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);var start = new CountDownLatch(1);
        try {var first = pool.submit(() -> {start.await();return a.call();});var second = pool.submit(() -> {start.await();return b.call();});
            start.countDown();return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    private JsonNode body(ResultActions r) throws Exception {return json.readTree(r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));}

    private ResultActions createRecall(Fixture f, String id) throws Exception {
        return call(f, post(C).content("{\"recallId\":\"" + id + "\",\"batchNo\":\"" + f.batch() + "\",\"reason\":\"批次质量问题\"}"), RECALL);
    }
    private ResultActions createReturn(Fixture f, String recall, String line, String id, int quantity) throws Exception {
        return call(f, post(C + "/{id}/lines/{line}/returns", recall, line).content("{\"returnId\":\"" + id + "\",\"quantity\":" + quantity + "}"), RETURNS);
    }
    private ResultActions receive(Fixture f, String ret, String receipt, String batch, int quantity) throws Exception {
        return call(f, post(W + "/sales-returns/{id}/receipts", ret).content("{\"receiptId\":\"" + receipt + "\",\"batchNo\":\"" + batch + "\",\"quantity\":" + quantity + "}"), RECEIVE);
    }
    private ResultActions verify(Fixture f, String ret, String receipt, String batch) throws Exception {
        return call(f, post(R + "/{id}/receipts/{receipt}/verify", ret, receipt).content("{\"batchNo\":\"" + batch + "\"}"), RETURNS);
    }
    private ResultActions stock(Fixture f) throws Exception { return call(f, get(W + "/warehouses/{id}/inventory", f.warehouse())); }
    private Fixture fixture() throws Exception { return fixture(10, 4); }
    private Fixture fixture(int firstQuantity, int otherQuantity) throws Exception {
        Fixture f = new Fixture("recall-" + UUID.randomUUID(), null, null, null, null, null, null);
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Recall supplier\",\"countryCode\":\"CN\"}"))).at("/supplier/supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[{"lineId":"L1","sellerSku":"MUG-11","quantity":%d,"unitCost":10}]}
                """.formatted(supplier, firstQuantity + otherQuantity)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{id}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{id}/approve", po)).andExpect(status().isOk());
        String wh = data(call(f, post(W + "/warehouses").content("{\"code\":\"RECALL11\",\"name\":\"Recall warehouse\",\"countryCode\":\"US\"}"))).path("warehouseId").asText();
        f = new Fixture(f.tenant(), wh, po, null, null, null, null);
        call(f, post(W + "/warehouses/{id}/locations", wh).content("{\"locationCode\":\"A-01\",\"purpose\":\"GOOD\"}"), "wms:location:write").andExpect(status().isOk());
        String[] batches = new String[2];
        for (int i = 0; i < 2; i++) {
            int quantity = i == 0 ? firstQuantity : otherQuantity;
            batches[i] = data(call(f, post(W + "/purchase-receipts/{id}", po).content("""
                    {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":%d}]}
                    """.formatted(UUID.randomUUID(), wh, quantity)), "wms:receipt:write")).at("/lines/0/batchNo").asText();
            call(f, post(W + "/purchase-inspections/{id}/sample", batches[i]).content("{\"checkedQuantity\":2,\"passedQuantity\":2,\"failedQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
            call(f, post(W + "/purchase-inspections/{id}/disposition", batches[i]).content("{\"goodQuantity\":" + quantity + ",\"defectiveQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
            call(f, post(W + "/purchase-putaways/{id}", batches[i]).content("""
                    {"requestId":"%s","locationCode":"A-01","quantity":%d}
                    """.formatted(UUID.randomUUID(), quantity)), "wms:putaway:write").andExpect(status().isOk());
        }
        String order = data(call(f, post("/oms/api/in/v1/orders/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Buyer-%s","countryCode":"US","shippingAddress":"1 Main Street",
                 "currency":"USD","lines":[{"lineId":"L1","sellerSku":"MUG-11","title":"Mug","quantity":%d,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), firstQuantity + otherQuantity)))).path("orderId").asText();
        call(f, patch("/oms/api/in/v1/orders/{id}/paid", order)).andExpect(status().isOk());
        JsonNode work = data(call(f, post("/oms/api/in/v1/orders/{id}/warehouse-work", order).content("{\"warehouseId\":\"" + wh + "\"}"), "oms:warehouse:write"));
        String outbound = work.path("outboundId").asText();
        var lines = json.createArrayNode();
        for (JsonNode a : work.path("allocations")) {
            JsonNode picked = data(call(f, post(W + "/sales-picking/{id}/picks", outbound).content("""
                    {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":%d}
                    """.formatted(UUID.randomUUID(), a.path("allocationId").asText(), a.path("batchNo").asText(), a.path("plannedQuantity").asInt())), "wms:pick:write"));
            lines.addObject().put("pickId", picked.path("requestId").asText()).put("quantity", picked.path("quantity").asInt());
        }
        String pack = UUID.randomUUID().toString();
        call(f, post(W + "/sales-dispatch/outbounds/{id}/packages", outbound).content("{\"packageId\":\"" + pack + "\",\"lines\":" + lines + "}"), "wms:ship:write").andExpect(status().isOk());
        call(f, post(W + "/sales-dispatch/packages/{id}/weight", pack).content("{\"weightKg\":1}"), "wms:ship:write").andExpect(status().isOk());
        call(f, post(W + "/sales-dispatch/packages/{id}/ship", pack).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"RECALL11\"}"), "wms:ship:write").andExpect(status().isOk());
        return new Fixture(f.tenant(), wh, po, batches[0], batches[1], order, outbound);
    }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        String user = Set.of(permissions).contains(CLOSE) ? "recall-manager" : Set.of(permissions).contains(RECEIVE) ? "warehouse-clerk" : "after-sales";
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " + jwt.createToken(f.tenant(), user, user, Set.of(permissions)))
                .contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        var r = response.andReturn().getResponse();JsonNode body = json.readTree(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.getStatus()).as("业务码 %s", body.path("code").asText()).isEqualTo(200);return body.path("data");
    }
    private record Fixture(String tenant, String warehouse, String po, String batch, String otherBatch, String salesOrder, String outbound) {}
}
