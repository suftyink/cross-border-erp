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
class FbaWarehouseApiTests {
    private static final String FBA = "/fba/api/in/v1/shipments";
    private static final String PICKS = "/wms/api/in/v1/sales-picking";
    private static final String CARTONS = "/wms/api/in/v1/fba-cartons";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;

    @Test
    void fbaDispatchUsesBatchPickingAndRetriesPreserveOneReservationWithoutDeductingStock() throws Exception {
        Fixture f = fixture();
        String shipment = shipment(f, 40);
        JsonNode work = data(dispatch(f, shipment));
        String outbound = work.path("outboundId").asText();
        assertThat(work.path("sourceType").asText()).isEqualTo("FBA_SHIPMENT");
        assertThat(work.path("sourceId").asText()).isEqualTo(shipment);
        assertThat(work.path("salesOrderId").isNull()).isTrue();
        assertThat(work.path("allocations").size()).isEqualTo(2);
        assertThat(work.at("/allocations/0/plannedQuantity").asInt()).isEqualTo(25);
        assertThat(work.at("/allocations/1/plannedQuantity").asInt()).isEqualTo(15);
        assertThat(data(dispatch(f, shipment)).path("outboundId").asText()).isEqualTo(outbound);
        call(f, get(PICKS + "/work"), "wms:pick:write")
                .andExpect(jsonPath("$.data.orders.length()").value(1));
        call(f, get(FBA + "/{id}/warehouse-work", shipment), "fba:warehouse:write")
                .andExpect(jsonPath("$.data.outboundId").value(outbound));
        for (JsonNode a : work.path("allocations")) {
            pick(f, outbound, a, a.path("plannedQuantity").asInt()).andExpect(status().isOk());
        }
        call(f, get(PICKS + "/{id}", outbound), "wms:pick:write")
                .andExpect(jsonPath("$.data.status").value("PICKED"))
                .andExpect(jsonPath("$.data.picks.length()").value(2));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50))
                .andExpect(jsonPath("$.data[0].reserved").value(40))
                .andExpect(jsonPath("$.data[0].available").value(10));
        JsonNode movements = data(call(f, get("/wms/api/in/v1/inventory-ledger/transactions").param("documentId", outbound)));
        assertThat(movements.size()).isEqualTo(4);
        assertThat(movements.findValuesAsText("referenceType")).containsOnly("FBA_BATCH_PICK");
    }

    @Test
    void twoCartonsRecordActualMixedBatchesAndSupportBothDirectionsOfPurchaseTrace() throws Exception {
        Fixture f = fixture();
        String shipment = shipment(f, 40);
        JsonNode work = data(dispatch(f, shipment));
        String outbound = work.path("outboundId").asText();
        for (JsonNode a : work.path("allocations")) pick(f, outbound, a, a.path("plannedQuantity").asInt()).andExpect(status().isOk());
        JsonNode picked = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write"));
        JsonNode first = picked.path("picks").get(0);
        JsonNode second = picked.path("picks").get(1);
        String requestId = UUID.randomUUID().toString();
        String firstBody = cartonBody(requestId, "CTN-EXISTING-001", line(first, 20));
        JsonNode carton1 = data(pack(f, outbound, firstBody));
        JsonNode carton2 = data(pack(f, outbound, cartonBody(UUID.randomUUID().toString(), null, line(first, 5) + "," + line(second, 15))));
        assertThat(carton1.path("cartonCode").asText()).isEqualTo("CTN-EXISTING-001");
        assertThat(carton2.path("cartonCode").asText()).startsWith("CTN-");
        assertThat(carton2.path("lines").size()).isEqualTo(2);
        assertThat(carton2.path("lines").findValuesAsText("batchNo")).containsExactlyInAnyOrder(f.batch1(), f.batch2());
        assertThat(carton2.path("lines").findValuesAsText("purchaseOrderId")).containsExactlyInAnyOrder(
                first.path("purchaseOrderId").asText(), second.path("purchaseOrderId").asText());
        assertThat(data(pack(f, outbound, firstBody)).path("cartonId").asText()).isEqualTo(carton1.path("cartonId").asText());
        call(f, get(CARTONS).param("cartonCode", carton2.path("cartonCode").asText()), "wms:pick:write")
                .andExpect(jsonPath("$.data.cartons.length()").value(1))
                .andExpect(jsonPath("$.data.cartons[0].lines.length()").value(2));
        call(f, get(CARTONS).param("batchNo", f.batch1()), "wms:pick:write")
                .andExpect(jsonPath("$.data.cartons.length()").value(2));
        call(f, get(CARTONS).param("purchaseOrderId", second.path("purchaseOrderId").asText()), "wms:pick:write")
                .andExpect(jsonPath("$.data.cartons.length()").value(1));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50))
                .andExpect(jsonPath("$.data[0].reserved").value(40));
        assertThat(data(call(f, get("/wms/api/in/v1/inventory-ledger/transactions").param("documentId", outbound))).size()).isEqualTo(4);
        call(f, get(FBA), "fba:warehouse:write").andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
    }

    @Test
    void invalidScansOverpackingAndLegacyShippingCannotBypassFbaWarehouseWork() throws Exception {
        Fixture f = fixture();
        String shipment = shipment(f, 40);
        call(f, post(FBA + "/{id}/warehouse-work", shipment).content("{\"warehouseId\":\"" + f.warehouse() + "\"}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        JsonNode work = data(dispatch(f, shipment));
        String outbound = work.path("outboundId").asText();
        pick(f, outbound, work.path("allocations").get(0), 25).andExpect(status().isOk());
        JsonNode pick = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write")).path("picks").get(0);
        String request = UUID.randomUUID().toString();
        pack(f, outbound, cartonBody(request, "SCAN-001", line(pick, 26))).andExpect(jsonPath("$.code").value("FBA_CARTON_EXCEEDS_PICKED"));
        pack(f, outbound, cartonBody(request, "SCAN-001", line(pick, 5).replace("MUG-01", "WRONG-SKU")))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_SCAN_INVALID"));
        pack(f, outbound, cartonBody(request, "SCAN-001", line(pick, 5).replace(f.batch1(), f.batch2())))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_SCAN_INVALID"));
        pack(f, outbound, cartonBody(request, "SCAN-001", line(pick, 5) + "," + line(pick, 5)))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_LINES_INVALID"));
        String valid = cartonBody(request, "SCAN-001", line(pick, 20));
        call(f, post(CARTONS + "/{id}", outbound).content(valid)).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        Fixture other = new Fixture("other-" + f.tenant(), f.warehouse(), f.batch1(), f.batch2());
        pack(other, outbound, valid).andExpect(jsonPath("$.code").value("FBA_PICK_NOT_FOUND"));
        call(other, get(CARTONS).param("cartonCode", "SCAN-001"), "wms:carton:write").andExpect(jsonPath("$.data.cartons.length()").value(0));
        JsonNode carton = data(pack(f, outbound, valid));
        pack(f, outbound, cartonBody(request, "SCAN-001", line(pick, 21))).andExpect(jsonPath("$.code").value("FBA_CARTON_REQUEST_CONFLICT"));
        pack(f, outbound, cartonBody(UUID.randomUUID().toString(), "SCAN-002", line(pick, 6))).andExpect(jsonPath("$.code").value("FBA_CARTON_EXCEEDS_PICKED"));
        String otherShipment = shipment(f, 5);
        JsonNode otherWork = data(dispatch(f, otherShipment));
        String otherOutbound = otherWork.path("outboundId").asText();
        for (JsonNode a : otherWork.path("allocations")) pick(f, otherOutbound, a, a.path("plannedQuantity").asInt()).andExpect(status().isOk());
        JsonNode otherPick = data(call(f, get(PICKS + "/{id}", otherOutbound), "wms:pick:write")).path("picks").get(0);
        pack(f, outbound, cartonBody(UUID.randomUUID().toString(), "SCAN-003", line(otherPick, 1))).andExpect(jsonPath("$.code").value("FBA_CARTON_PICK_INVALID"));
        pack(f, otherOutbound, cartonBody(UUID.randomUUID().toString(), "SCAN-001", line(otherPick, 1))).andExpect(jsonPath("$.code").value("FBA_CARTON_CONFLICT"));
        call(f, put(FBA + "/{id}/ship", shipment).content("{\"carrier\":\"UPS\",\"trackingNo\":\"BYPASS\"}"))
                .andExpect(jsonPath("$.code").value("FBA_WAREHOUSE_WORK_EXISTS"));
        call(f, put(FBA + "/{id}/pack", shipment).content("{\"cartonCount\":2,\"totalWeight\":1}"))
                .andExpect(jsonPath("$.code").value("FBA_WAREHOUSE_WORK_EXISTS"));
        call(f, patch(FBA + "/{id}/receive", shipment).content("{\"receivedQuantity\":1}"))
                .andExpect(jsonPath("$.code").value("FBA_WAREHOUSE_WORK_EXISTS"));
        call(f, post("/wms/api/in/v1/outbound-orders/{id}/pick", outbound).content("""
                {"picks":[{"lineId":"%s","pickedQuantity":1}]}
                """.formatted(work.at("/allocations/0/lineId").asText()))).andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        call(f, post("/wms/api/in/v1/packages/{id}/ship", carton.path("cartonId").asText()).content(
                "{\"carrierCode\":\"UPS\",\"trackingNo\":\"BYPASS\"}")).andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(45));
    }

    @Test
    void concurrentDispatchAndPackingSerializeEachTaskAndProtectUniqueCartonCodes() throws Exception {
        Fixture f = fixture();
        String shipment = shipment(f, 40);
        List<JsonNode> dispatched = race(() -> body(dispatch(f, shipment)), () -> body(dispatch(f, shipment)));
        assertThat(dispatched).allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        String outbound = dispatched.get(0).at("/data/outboundId").asText();
        assertThat(dispatched.get(1).at("/data/outboundId").asText()).isEqualTo(outbound);
        JsonNode work = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write"));
        pick(f, outbound, work.path("allocations").get(0), 25).andExpect(status().isOk());
        JsonNode actual = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write")).path("picks").get(0);
        List<JsonNode> cartons = race(
                () -> body(pack(f, outbound, cartonBody(UUID.randomUUID().toString(), null, line(actual, 20)))),
                () -> body(pack(f, outbound, cartonBody(UUID.randomUUID().toString(), null, line(actual, 20)))));
        assertThat(cartons.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(cartons.stream().filter(r -> !r.path("success").asBoolean()).findFirst().orElseThrow().path("code").asText())
                .isEqualTo("FBA_CARTON_EXCEEDS_PICKED");
        String retry = cartonBody(UUID.randomUUID().toString(), null, line(actual, 5));
        List<JsonNode> retries = race(() -> body(pack(f, outbound, retry)), () -> body(pack(f, outbound, retry)));
        assertThat(retries).allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
        assertThat(retries.get(0).at("/data/cartonCode").asText()).isEqualTo(retries.get(1).at("/data/cartonCode").asText());
        String second = data(dispatch(f, shipment(f, 5))).path("outboundId").asText();
        String third = data(dispatch(f, shipment(f, 5))).path("outboundId").asText();
        JsonNode a2 = data(call(f, get(PICKS + "/{id}", second), "wms:pick:write")).path("allocations").get(0);
        JsonNode a3 = data(call(f, get(PICKS + "/{id}", third), "wms:pick:write")).path("allocations").get(0);
        JsonNode p2 = data(pick(f, second, a2, 5)), p3 = data(pick(f, third, a3, 5));
        List<JsonNode> sameCode = race(
                () -> body(pack(f, second, cartonBody(UUID.randomUUID().toString(), "SHARED-BOX", line(p2, 5)))),
                () -> body(pack(f, third, cartonBody(UUID.randomUUID().toString(), "SHARED-BOX", line(p3, 5)))));
        assertThat(sameCode.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(sameCode.stream().filter(r -> !r.path("success").asBoolean()).findFirst().orElseThrow().path("code").asText())
                .isEqualTo("FBA_CARTON_CONFLICT");
        call(f, get(CARTONS), "wms:carton:write").andExpect(jsonPath("$.data.cartons.length()").value(3));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(50));
    }

    @Test
    void qualityWithdrawalCancelsTheCartonAndPreservesItsOriginalPurchaseTraceWithoutReusingReturnedPicks() throws Exception {
        Fixture f = fixture();
        JsonNode work = data(dispatch(f, shipment(f, 40)));
        String outbound = work.path("outboundId").asText();
        JsonNode actual = data(pick(f, outbound, work.path("allocations").get(0), 25));
        String packed = cartonBody(UUID.randomUUID().toString(), "QUALITY-BOX", line(actual, 20));
        data(pack(f, outbound, packed));
        String correction = UUID.randomUUID().toString();
        call(f, post("/wms/api/in/v1/quality-corrections").content("""
                {"correctionId":"%s","batchNo":"%s","reason":"FBA 装箱后发现原质检判定错误"}
                """.formatted(correction, f.batch1())), "wms:quality-correction:write").andExpect(status().isOk());
        pack(f, outbound, cartonBody(UUID.randomUUID().toString(), null, line(actual, 1)))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_STATUS_INVALID"));
        call(f, post("/wms/api/in/v1/quality-corrections/{id}/unpick", correction).content("""
                {"requestId":"%s","pickId":"%s","locationCode":"A-01","batchNo":"%s"}
                """.formatted(UUID.randomUUID(), actual.path("requestId").asText(), f.batch1())), "wms:quality-correction:write").andExpect(status().isOk());
        call(f, get(CARTONS).param("cartonCode", "QUALITY-BOX"), "wms:carton:write")
                .andExpect(jsonPath("$.data.cartons[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cartons[0].lines[0].quantity").value(20))
                .andExpect(jsonPath("$.data.cartons[0].lines[0].purchaseOrderId").value(actual.path("purchaseOrderId").asText()));
        assertThat(data(pack(f, outbound, packed)).path("status").asText()).isEqualTo("CANCELLED");
        pack(f, outbound, cartonBody(UUID.randomUUID().toString(), null, line(actual, 1)))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_STATUS_INVALID"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(15))
                .andExpect(jsonPath("$.data[0].pendingInspection").value(25));
    }

    @Test
    void invalidSourceAndStockShortageLeaveNoWorkAndOneCartonCanContainDifferentSkus() throws Exception {
        Fixture f = fixture();
        String empty = data(call(f, post(FBA).content("{\"plannedQuantity\":4,\"destinationFc\":\"ONT8\"}"))).path("fbaShipmentId").asText();
        dispatch(f, empty).andExpect(jsonPath("$.code").value("FBA_WAREHOUSE_STATUS_INVALID"));
        call(f, patch(FBA + "/{id}/submit", empty)).andExpect(status().isOk());
        dispatch(f, empty).andExpect(jsonPath("$.code").value("FBA_WAREHOUSE_LINES_INVALID"));
        String excessive = shipment(f, 51);
        dispatch(f, excessive).andExpect(jsonPath("$.code").value("SALES_PICK_INSUFFICIENT_STOCK"));
        call(f, get(PICKS + "/work"), "wms:pick:write").andExpect(jsonPath("$.data.orders.length()").value(0));
        stock(f).andExpect(jsonPath("$.data[0].reserved").value(0));
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Mixed SKU supplier\",\"countryCode\":\"CN\"}"))).at("/supplier/supplierId").asText();
        receive(f, supplier, 10, "BOTTLE-02");
        String mixed = data(call(f, post(FBA).content("""
                {"destinationFc":"ONT8","plannedQuantity":8,"items":[{"sellerSku":"MUG-01","quantity":3},
                {"sellerSku":"BOTTLE-02","quantity":5}]}
                """))).path("fbaShipmentId").asText();
        call(f, patch(FBA + "/{id}/submit", mixed)).andExpect(status().isOk());
        JsonNode work = data(dispatch(f, mixed));
        String outbound = work.path("outboundId").asText();
        for (JsonNode a : work.path("allocations")) pick(f, outbound, a, a.path("plannedQuantity").asInt()).andExpect(status().isOk());
        JsonNode picked = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write"));
        JsonNode carton = data(pack(f, outbound, cartonBody(UUID.randomUUID().toString(), "MIXED-SKUS",
                line(picked.path("picks").get(0), picked.path("picks").get(0).path("quantity").asInt()) + "," +
                line(picked.path("picks").get(1), picked.path("picks").get(1).path("quantity").asInt()))));
        assertThat(carton.path("lines").findValuesAsText("sellerSku")).containsExactlyInAnyOrder("MUG-01", "BOTTLE-02");
    }

    private JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsString());
    }
    private List<JsonNode> race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { gate.await(); return first.call(); });
            var b = executor.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    private String line(JsonNode pick, int quantity) {
        return """
                {"pickId":"%s","sellerSku":"%s","batchNo":"%s","quantity":%d}
                """.formatted(pick.path("requestId").asText(), pick.path("sellerSku").asText(), pick.path("batchNo").asText(), quantity);
    }
    private String cartonBody(String requestId, String code, String lines) {
        return "{\"requestId\":\"" + requestId + "\",\"cartonCode\":" + (code == null ? "null" : "\"" + code + "\"") + ",\"lines\":[" + lines + "]}";
    }
    private ResultActions pack(Fixture f, String outbound, String body) throws Exception {
        return call(f, post(CARTONS + "/{id}", outbound).content(body), "wms:carton:write");
    }

    private Fixture fixture() throws Exception {
        Fixture f = new Fixture("fba-work-" + UUID.randomUUID(), null, null, null);
        String warehouse = data(call(f, post("/wms/api/in/v1/warehouses").content(
                "{\"code\":\"FBA\",\"name\":\"FBA prep warehouse\",\"countryCode\":\"CN\"}"))).path("warehouseId").asText();
        f = new Fixture(f.tenant(), warehouse, null, null);
        call(f, post("/wms/api/in/v1/warehouses/{id}/locations", warehouse).content(
                "{\"locationCode\":\"A-01\",\"purpose\":\"GOOD\"}"), "wms:location:write").andExpect(status().isOk());
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content(
                "{\"name\":\"FBA goods supplier\",\"countryCode\":\"CN\"}"))).at("/supplier/supplierId").asText();
        String batch1 = receive(f, supplier, 25);
        String batch2 = receive(f, supplier, 25);
        return new Fixture(f.tenant(), warehouse, batch1, batch2);
    }

    private String receive(Fixture f, String supplier, int quantity) throws Exception {
        return receive(f, supplier, quantity, "MUG-01");
    }
    private String receive(Fixture f, String supplier, int quantity, String sku) throws Exception {
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"%s","quantity":%d,"unitCost":10}]}
                """.formatted(supplier, sku, quantity)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        String batch = data(call(f, post("/wms/api/in/v1/purchase-receipts/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":%d}]}
                """.formatted(UUID.randomUUID(), f.warehouse(), quantity)), "wms:receipt:write")).at("/lines/0/batchNo").asText();
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/sample", batch).content(
                "{\"checkedQuantity\":2,\"passedQuantity\":2,\"failedQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-inspections/{b}/disposition", batch).content(
                "{\"goodQuantity\":" + quantity + ",\"defectiveQuantity\":0}"), "wms:inspection:write").andExpect(status().isOk());
        call(f, post("/wms/api/in/v1/purchase-putaways/{b}", batch).content("""
                {"requestId":"%s","locationCode":"A-01","quantity":%d}
                """.formatted(UUID.randomUUID(), quantity)), "wms:putaway:write").andExpect(status().isOk());
        return batch;
    }

    private String shipment(Fixture f, int quantity) throws Exception {
        String id = data(call(f, post(FBA).content("""
                {"amazonShipmentId":"%s","destinationFc":"ONT8","plannedQuantity":%d,
                "items":[{"sellerSku":"MUG-01","quantity":%d,"boxQuantity":2}]}
                """.formatted(UUID.randomUUID(), quantity, quantity)))).path("fbaShipmentId").asText();
        call(f, patch(FBA + "/{id}/submit", id)).andExpect(status().isOk());
        return id;
    }
    private ResultActions dispatch(Fixture f, String shipment) throws Exception {
        return call(f, post(FBA + "/{id}/warehouse-work", shipment).content(
                "{\"warehouseId\":\"" + f.warehouse() + "\"}"), "fba:warehouse:write");
    }
    private ResultActions pick(Fixture f, String outbound, JsonNode allocation, int quantity) throws Exception {
        return call(f, post(PICKS + "/{id}/picks", outbound).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"%s","batchNo":"%s","quantity":%d}
                """.formatted(UUID.randomUUID(), allocation.path("allocationId").asText(), allocation.path("locationCode").asText(),
                allocation.path("batchNo").asText(), quantity)), "wms:pick:write");
    }
    private ResultActions stock(Fixture f) throws Exception {
        return call(f, get("/wms/api/in/v1/warehouses/{id}/inventory", f.warehouse()));
    }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken(f.tenant(), "warehouse-worker", "warehouse", Set.of(permissions))).contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        JsonNode body = json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(body.path("success").asBoolean()).isTrue();
        return body.path("data");
    }
    private record Fixture(String tenant, String warehouse, String batch1, String batch2) {}
}
