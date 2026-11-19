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
class FbaDispatchApiTests {
    private static final String FBA = "/fba/api/in/v1/shipments";
    private static final String PICKS = "/wms/api/in/v1/sales-picking";
    private static final String CARTONS = "/wms/api/in/v1/fba-cartons";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate database;

    private static final String SHIP = "/wms/api/in/v1/fba-dispatch";

    @Test
    void sellerCentralLabelsBindToExactlyOneCartonAndBothBoxNumbersTraceTheSameGoods() throws Exception {
        Prepared p = prepared();
        Fixture f = p.fixture();
        String amazon = data(call(f, get(FBA))).get(0).path("amazonShipmentId").asText();
        String labels = "{\"amazonShipmentId\":\"" + amazon + "\",\"boxNumbers\":[\"BOX-14-01\",\"BOX-14-02\"]}";
        call(f, post(FBA + "/{id}/box-labels", p.shipment()).content(labels), "fba:label:write")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.boxNumbers.length()").value(2));
        call(f, post(FBA + "/{id}/box-labels", p.shipment()).content(labels), "fba:label:write").andExpect(status().isOk());
        bind(f, p.outbound(), p.first().path("cartonCode").asText(), "BOX-14-01").andExpect(status().isOk());
        bind(f, p.outbound(), p.first().path("cartonCode").asText(), "BOX-14-01").andExpect(status().isOk());
        bind(f, p.outbound(), p.second().path("cartonCode").asText(), "BOX-14-02").andExpect(status().isOk());
        JsonNode internal = data(call(f, get(CARTONS).param("cartonCode", p.first().path("cartonCode").asText()), "wms:pick:write")).path("cartons").get(0);
        JsonNode external = data(call(f, get(CARTONS).param("cartonCode", "BOX-14-01"), "wms:pick:write")).path("cartons").get(0);
        assertThat(external).isEqualTo(internal);
        assertThat(external.path("fbaBoxNumber").asText()).isEqualTo("BOX-14-01");
        assertThat(external.path("lines").get(0).path("purchaseOrderId").asText()).isNotBlank();
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(40));
    }

    @Test
    void missingTenthLabelBlocksTheWholeOrderThenShipmentAndFbaWritebackHappenOnce() throws Exception {
        Prepared p = prepared(10);
        Fixture f = p.fixture();
        register(p, 10).andExpect(status().isOk());
        for (int i = 0; i < 9; i++) bind(f, p.outbound(), p.cartons().get(i).path("cartonCode").asText(), "BOX-14-%02d".formatted(i + 1)).andExpect(status().isOk());
        JsonNode before = ledger(p);
        ship(p).andExpect(jsonPath("$.code").value("FBA_LABELS_REQUIRED"));
        call(f, get(SHIP + "/{id}", p.outbound()), "wms:fba-ship:write")
                .andExpect(jsonPath("$.data.preparation.missingLabelCartonCodes[0]").value("INTERNAL-14-10"))
                .andExpect(jsonPath("$.data.preparation.readyToShip").value(false));
        assertThat(ledger(p)).isEqualTo(before);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(40));
        call(f, get(FBA)).andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
        bind(f, p.outbound(), "INTERNAL-14-10", "BOX-14-10").andExpect(status().isOk());
        ship(p).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"));
        JsonNode after = ledger(p);
        assertThat(after.findValuesAsText("transactionType")).contains("FBA_SHIP");
        int shipped = 0;
        for (JsonNode movement : after) if (movement.path("transactionType").asText().equals("FBA_SHIP")) shipped += movement.path("quantity").asInt();
        assertThat(shipped).isEqualTo(40);
        ship(p).andExpect(status().isOk());
        call(f, post(SHIP + "/{id}/sync", p.outbound()), "wms:fba-ship:write").andExpect(status().isOk());
        assertThat(ledger(p)).isEqualTo(after);
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(10)).andExpect(jsonPath("$.data[0].reserved").value(0))
                .andExpect(jsonPath("$.data[0].available").value(10));
        call(f, get(PICKS + "/{id}", p.outbound()), "wms:pick:write").andExpect(jsonPath("$.data.status").value("SHIPPED"));
        call(f, get(FBA)).andExpect(jsonPath("$.data[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.data[0].cartonCount").value(10)).andExpect(jsonPath("$.data[0].trackingNo").value("FBA14-TRACK"));
    }

    @Test
    void internalAndFbaIdentifiersCannotReferToDifferentCartonsEvenAcrossTasks() throws Exception {
        Prepared a = prepared();
        String amazon = data(call(a.fixture(), get(FBA))).get(0).path("amazonShipmentId").asText();
        call(a.fixture(), post(FBA + "/{id}/box-labels", a.shipment()).content(json.writeValueAsString(java.util.Map.of(
                "amazonShipmentId", amazon, "boxNumbers", List.of("INTERNAL-14-01", "ALIAS-SECOND")))), "fba:label:write").andExpect(status().isOk());
        bind(a.fixture(), a.outbound(), "INTERNAL-14-02", "INTERNAL-14-01")
                .andExpect(jsonPath("$.code").value("FBA_BOX_IDENTIFIER_CONFLICT"));
        Prepared b = prepared();
        register(b, 2).andExpect(status().isOk());
        bind(b.fixture(), b.outbound(), "INTERNAL-14-01", "BOX-14-01").andExpect(status().isOk());
        String other = data(dispatch(b.fixture(), shipment(b.fixture(), 5))).path("outboundId").asText();
        JsonNode allocation = data(call(b.fixture(), get(PICKS + "/{id}", other), "wms:pick:write")).path("allocations").get(0);
        JsonNode actual = data(pick(b.fixture(), other, allocation, 5));
        pack(b.fixture(), other, cartonBody(UUID.randomUUID().toString(), "BOX-14-01", line(actual, 5)))
                .andExpect(jsonPath("$.code").value("FBA_CARTON_CONFLICT"));
        call(b.fixture(), get(CARTONS).param("cartonCode", "BOX-14-01"), "wms:pick:write")
                .andExpect(jsonPath("$.data.cartons.length()").value(1));
    }

    @Test
    void incorrectLabelsPermissionsAndOtherTenantsCannotChangeBindingsOrDispatch() throws Exception {
        Prepared p = prepared();
        Fixture f = p.fixture();
        register(p, 2).andExpect(status().isOk());
        call(f, post(FBA + "/{id}/box-labels", p.shipment()).content("{\"amazonShipmentId\":\"x\",\"boxNumbers\":[\"x\"]}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(SHIP + "/{id}/bindings", p.outbound()).content("{\"cartonCode\":\"INTERNAL-14-01\",\"fbaBoxNumber\":\"BOX-14-01\",\"labelApplied\":true}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(SHIP + "/{id}/bindings", p.outbound()).content("{\"cartonCode\":\"INTERNAL-14-01\",\"fbaBoxNumber\":\"BOX-14-01\",\"labelApplied\":false}"), "wms:fba-bind:write")
                .andExpect(status().isBadRequest());
        register(p, 3).andExpect(jsonPath("$.code").value("FBA_LABEL_PLAN_CONFLICT"));
        bind(f, p.outbound(), "NOT-THIS-CARTON", "BOX-14-01").andExpect(jsonPath("$.code").value("FBA_CARTON_SCAN_INVALID"));
        bind(f, p.outbound(), "INTERNAL-14-01", "NOT-REGISTERED").andExpect(jsonPath("$.code").value("FBA_BOX_SHIPMENT_MISMATCH"));
        String otherShipment = shipment(f, 5), otherAmazon = "";
        for (JsonNode s : data(call(f, get(FBA)))) if (s.path("fbaShipmentId").asText().equals(otherShipment)) otherAmazon = s.path("amazonShipmentId").asText();
        call(f, post(FBA + "/{id}/box-labels", otherShipment).content(json.writeValueAsString(java.util.Map.of(
                "amazonShipmentId", otherAmazon, "boxNumbers", List.of("BOX-14-01")))), "fba:label:write")
                .andExpect(jsonPath("$.code").value("FBA_LABEL_NUMBER_CONFLICT"));
        call(f, get(FBA + "/{id}/box-labels", otherShipment), "fba:label:write").andExpect(jsonPath("$.data").doesNotExist());
        call(f, post(FBA + "/{id}/box-labels", otherShipment).content(json.writeValueAsString(java.util.Map.of(
                "amazonShipmentId", otherAmazon, "boxNumbers", List.of("OTHER-FBA-BOX")))), "fba:label:write").andExpect(status().isOk());
        bind(f, p.outbound(), "INTERNAL-14-01", "OTHER-FBA-BOX").andExpect(jsonPath("$.code").value("FBA_BOX_SHIPMENT_MISMATCH"));
        bind(f, p.outbound(), "INTERNAL-14-01", "BOX-14-01").andExpect(status().isOk());
        bind(f, p.outbound(), "INTERNAL-14-01", "BOX-14-02").andExpect(jsonPath("$.code").value("FBA_BOX_ALREADY_BOUND"));
        bind(f, p.outbound(), "INTERNAL-14-02", "BOX-14-01").andExpect(jsonPath("$.code").value("FBA_BOX_ALREADY_BOUND"));
        Fixture other = new Fixture("other-" + f.tenant(), f.warehouse(), f.batch1(), f.batch2());
        bind(other, p.outbound(), "INTERNAL-14-02", "BOX-14-02").andExpect(jsonPath("$.code").value("FBA_PICK_NOT_FOUND"));
        call(other, get(CARTONS).param("cartonCode", "BOX-14-01"), "wms:fba-bind:write").andExpect(jsonPath("$.data.cartons.length()").value(0));
        call(f, post(SHIP + "/{id}/sync", p.outbound()), "wms:fba-ship:write").andExpect(jsonPath("$.code").value("FBA_SHIPMENT_NOT_DISPATCHED"));
        call(f, post(SHIP + "/{id}/ship", p.outbound()).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"x\",\"confirmDispatch\":true}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(40));
    }

    @Test
    void fbaPersistenceFailureLeavesThePhysicalShipmentCommittedAndWritebackCanBeRetried() throws Exception {
        Prepared p = prepared();
        register(p, 2).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) bind(p.fixture(), p.outbound(), p.cartons().get(i).path("cartonCode").asText(), "BOX-14-%02d".formatted(i + 1)).andExpect(status().isOk());
        String fault = "wms14_fault_" + UUID.randomUUID().toString().replace("-", "");
        // 仅在真实数据库边界注入本测试租户的落库故障，业务结果通过 HTTP 查询核对。
        database.execute("CREATE FUNCTION public." + fault + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id = '" + p.fixture().tenant()
                + "' AND NEW.status = 'SHIPPED' THEN RAISE EXCEPTION 'simulated FBA persistence outage'; END IF; RETURN NEW; END $$");
        JsonNode posted;
        try {
            database.execute("CREATE TRIGGER " + fault + " BEFORE UPDATE ON fba_shipment FOR EACH ROW EXECUTE FUNCTION public." + fault + "()");
            ship(p).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"))
                    .andExpect(jsonPath("$.data.syncStatus").value("PENDING")).andExpect(jsonPath("$.data.syncError").value("FBA 回写失败，可重试"));
            posted = ledger(p);
            ship(p).andExpect(status().isOk()).andExpect(jsonPath("$.data.syncStatus").value("PENDING"));
            assertThat(ledger(p)).isEqualTo(posted);
            stock(p.fixture()).andExpect(jsonPath("$.data[0].onHand").value(10)).andExpect(jsonPath("$.data[0].reserved").value(0));
            call(p.fixture(), get(FBA)).andExpect(jsonPath("$.data[0].status").value("SUBMITTED"));
            call(p.fixture(), get("/fba/api/in/v1/warehouse-work"), "fba:warehouse:write")
                    .andExpect(jsonPath("$.data.orders[0].warehouseShipment").doesNotExist());
        } finally {
            database.execute("DROP TRIGGER IF EXISTS " + fault + " ON fba_shipment");
            database.execute("DROP FUNCTION public." + fault + "()");
        }
        call(p.fixture(), post(SHIP + "/{id}/sync", p.outbound()), "wms:fba-ship:write").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("SYNCED"));
        call(p.fixture(), post(SHIP + "/{id}/sync", p.outbound()), "wms:fba-ship:write").andExpect(status().isOk());
        assertThat(ledger(p)).isEqualTo(posted);
        call(p.fixture(), get("/fba/api/in/v1/warehouse-work"), "fba:warehouse:write")
                .andExpect(jsonPath("$.data.orders[0].shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.orders[0].warehouseShipment.quantity").value(40))
                .andExpect(jsonPath("$.data.orders[0].warehouseShipment.cartonCount").value(2));
    }

    @Test
    void concurrentBindingAndWholeOrderDispatchCannotUseABoxOrDeductStockTwice() throws Exception {
        Prepared p = prepared();
        register(p, 2).andExpect(status().isOk());
        List<JsonNode> competing = race(() -> body(bind(p.fixture(), p.outbound(), "INTERNAL-14-01", "BOX-14-01")),
                () -> body(bind(p.fixture(), p.outbound(), "INTERNAL-14-02", "BOX-14-01")));
        assertThat(competing.stream().filter(r -> r.path("success").asBoolean()).count()).isEqualTo(1);
        assertThat(competing.stream().filter(r -> !r.path("success").asBoolean()).findFirst().orElseThrow().path("code").asText()).isEqualTo("FBA_BOX_ALREADY_BOUND");
        JsonNode first = data(call(p.fixture(), get(CARTONS).param("cartonCode", "INTERNAL-14-01"), "wms:pick:write")).path("cartons").get(0);
        String unbound = first.path("bindingActive").asBoolean() ? "INTERNAL-14-02" : "INTERNAL-14-01";
        bind(p.fixture(), p.outbound(), unbound, "BOX-14-02").andExpect(status().isOk());
        List<JsonNode> results = race(() -> body(ship(p)), () -> body(ship(p)));
        assertThat(results).allSatisfy(r -> {
            assertThat(r.path("success").asBoolean()).isTrue();
            assertThat(r.at("/data/syncStatus").asText()).isEqualTo("SYNCED");
        });
        int shipped = 0;
        for (JsonNode movement : ledger(p)) if (movement.path("transactionType").asText().equals("FBA_SHIP")) shipped += movement.path("quantity").asInt();
        assertThat(shipped).isEqualTo(40);
        stock(p.fixture()).andExpect(jsonPath("$.data[0].onHand").value(10)).andExpect(jsonPath("$.data[0].reserved").value(0));
    }

    @Test
    void qualityHoldBlocksWholeOrderDispatchAndCancelledCartonsCannotBeBound() throws Exception {
        Prepared p = prepared();
        register(p, 2).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) bind(p.fixture(), p.outbound(), p.cartons().get(i).path("cartonCode").asText(), "BOX-14-%02d".formatted(i + 1)).andExpect(status().isOk());
        String correction = UUID.randomUUID().toString();
        call(p.fixture(), post("/wms/api/in/v1/quality-corrections").content("{\"correctionId\":\"" + correction + "\",\"batchNo\":\"" + p.fixture().batch1() + "\",\"reason\":\"发现 FBA 待发批次质量问题\"}"), "wms:quality-correction:write").andExpect(status().isOk());
        ship(p).andExpect(jsonPath("$.code").value("FBA_DISPATCH_NOT_READY"));
        JsonNode picked = data(call(p.fixture(), get(PICKS + "/{id}", p.outbound()), "wms:pick:write")).path("picks").get(0);
        call(p.fixture(), post("/wms/api/in/v1/quality-corrections/{id}/unpick", correction).content(json.writeValueAsString(java.util.Map.of(
                "requestId", UUID.randomUUID(), "pickId", picked.path("requestId").asText(), "locationCode", "A-01", "batchNo", p.fixture().batch1()))), "wms:quality-correction:write").andExpect(status().isOk());
        bind(p.fixture(), p.outbound(), "INTERNAL-14-01", "BOX-14-01").andExpect(jsonPath("$.code").value("FBA_CARTON_CANCELLED"));
        ship(p).andExpect(jsonPath("$.code").value("FBA_DISPATCH_NOT_READY"));
        stock(p.fixture()).andExpect(jsonPath("$.data[0].onHand").value(50)).andExpect(jsonPath("$.data[0].reserved").value(15));
    }

    @Test
    void submittingADraftWhileRegisteringSellerCentralLabelsKeepsTheNewShipmentIdAndBoxCount() throws Exception {
        Fixture f = new Fixture("fba-label-race-" + UUID.randomUUID(), null, null, null);
        for (int i = 0; i < 8; i++) {
            String shipment = data(call(f, post(FBA).content("{\"destinationFc\":\"ONT8\",\"plannedQuantity\":4,\"items\":[{\"sellerSku\":\"MUG-01\",\"quantity\":4}]}"))).path("fbaShipmentId").asText();
            String amazon = "FBA14-RACE-" + UUID.randomUUID();
            String labels = json.writeValueAsString(java.util.Map.of("amazonShipmentId", amazon,
                    "boxNumbers", List.of("RACE-" + i + "-1", "RACE-" + i + "-2")));
            assertThat(race(() -> body(call(f, patch(FBA + "/{id}/submit", shipment))),
                    () -> body(call(f, post(FBA + "/{id}/box-labels", shipment).content(labels), "fba:label:write"))))
                    .allSatisfy(r -> assertThat(r.path("success").asBoolean()).isTrue());
            JsonNode source = null;
            for (JsonNode row : data(call(f, get(FBA)))) if (row.path("fbaShipmentId").asText().equals(shipment)) source = row;
            assertThat(source).isNotNull();
            assertThat(source.path("amazonShipmentId").asText()).isEqualTo(amazon);
            assertThat(source.path("cartonCount").asInt()).isEqualTo(2);
            assertThat(source.path("status").asText()).isEqualTo("SUBMITTED");
        }
    }

    private JsonNode body(ResultActions response) throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsString()); }
    private List<JsonNode> race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { gate.await(); return first.call(); });
            var b = executor.submit(() -> { gate.await(); return second.call(); });
            gate.countDown(); return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    private ResultActions register(Prepared p, int count) throws Exception {
        String amazon = "";
        for (JsonNode source : data(call(p.fixture(), get(FBA)))) if (source.path("fbaShipmentId").asText().equals(p.shipment())) amazon = source.path("amazonShipmentId").asText();
        var numbers = new java.util.ArrayList<String>();
        for (int i = 1; i <= count; i++) numbers.add("BOX-14-%02d".formatted(i));
        return call(p.fixture(), post(FBA + "/{id}/box-labels", p.shipment()).content(json.writeValueAsString(
                java.util.Map.of("amazonShipmentId", amazon, "boxNumbers", numbers))), "fba:label:write");
    }
    private ResultActions ship(Prepared p) throws Exception {
        return call(p.fixture(), post(SHIP + "/{id}/ship", p.outbound()).content(
                "{\"carrierCode\":\"UPS\",\"trackingNo\":\"FBA14-TRACK\",\"confirmDispatch\":true}"), "wms:fba-ship:write");
    }
    private JsonNode ledger(Prepared p) throws Exception {
        return data(call(p.fixture(), get("/wms/api/in/v1/inventory-ledger/transactions").param("documentId", p.outbound())));
    }
    private Prepared prepared() throws Exception { return prepared(2); }
    private Prepared prepared(int count) throws Exception {
        Fixture f = fixture();
        String shipment = shipment(f, 40);
        JsonNode work = data(dispatch(f, shipment));
        String outbound = work.path("outboundId").asText();
        for (JsonNode a : work.path("allocations")) pick(f, outbound, a, a.path("plannedQuantity").asInt()).andExpect(status().isOk());
        JsonNode picked = data(call(f, get(PICKS + "/{id}", outbound), "wms:pick:write")).path("picks");
        var cartons = new java.util.ArrayList<JsonNode>();
        int remaining = 25;
        for (int i = 1; i <= count; i++) {
            int fromFirst = Math.min(remaining, 40 / count), fromSecond = 40 / count - fromFirst;
            String lines = fromFirst > 0 ? line(picked.get(0), fromFirst) : "";
            if (fromSecond > 0) lines += (lines.isEmpty() ? "" : ",") + line(picked.get(1), fromSecond);
            cartons.add(data(pack(f, outbound, cartonBody(UUID.randomUUID().toString(), "INTERNAL-14-%02d".formatted(i), lines))));
            remaining -= fromFirst;
        }
        return new Prepared(f, shipment, outbound, cartons.get(0), cartons.get(1), cartons);
    }
    private ResultActions bind(Fixture f, String outbound, String carton, String box) throws Exception {
        return call(f, post(SHIP + "/{id}/bindings", outbound).content("{\"cartonCode\":\"" + carton + "\",\"fbaBoxNumber\":\"" + box + "\",\"labelApplied\":true}"), "wms:fba-bind:write");
    }
    private record Prepared(Fixture fixture, String shipment, String outbound, JsonNode first, JsonNode second, List<JsonNode> cartons) {}

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
