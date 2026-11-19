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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ErpAppTest
@AutoConfigureMockMvc
class SalesDispatchApiTests {
    private static final String WMS = "/wms/api/in/v1";
    private static final String SALES = "/oms/api/in/v1/orders";
    private static final String DISPATCH = WMS + "/sales-dispatch";
    private static final String SHIP = "wms:ship:write";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private JdbcTemplate database;

    @Test
    void warehouseShipsActualPicksOnceAndBothDirectionsTraceThePurchaseSource() throws Exception {
        Fixture f = fixture(30);
        String first = actualPick(f, 10);
        String second = actualPick(f, 20);
        call(f, patch(SALES + "/{id}/ship", f.salesOrder()), "oms:warehouse:write").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
        String packageId = UUID.randomUUID().toString();
        pack(f, packageId, """
                [{"pickId":"%s","quantity":10},{"pickId":"%s","quantity":20}]
                """.formatted(first, second)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PACKED"));
        weigh(f, packageId).andExpect(status().isOk());
        ship(f, packageId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.omsSyncStatus").value("SYNCED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(210))
                .andExpect(jsonPath("$.data[0].reserved").value(0)).andExpect(jsonPath("$.data[0].available").value(67));
        JsonNode balances = data(call(f, get(WMS + "/inventory-ledger/balances").param("batchNo", f.batch())));
        assertThat(balances.findValuesAsText("locationCode")).contains("A-01", "A-02");
        assertThat(balances.findValuesAsText("status")).doesNotContain("PICKED", "RESERVED");
        assertThat(balances.findValues("quantity").stream().map(JsonNode::asInt).toList()).contains(30, 37);
        call(f, get(SALES + "/{id}", f.salesOrder())).andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.fulfillmentStatus").value("SHIPPED"));
        call(f, patch(SALES + "/{id}/ship", f.salesOrder()), "oms:warehouse:write").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
        ship(f, packageId).andExpect(status().isOk());
        call(f, post(DISPATCH + "/packages/{id}/sync", packageId), SHIP).andExpect(status().isOk());
        call(f, post(SALES + "/{id}/warehouse-shipments/refresh", f.salesOrder()), "oms:warehouse:write")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shippedQuantity").value(30));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(210));
        JsonNode transactions = data(call(f, get(WMS + "/inventory-ledger/transactions").param("documentId", packageId)));
        assertThat(transactions.size()).isEqualTo(2);
        assertThat(transactions.findValuesAsText("transactionType")).containsOnly("SALES_SHIP");
        JsonNode trace = data(call(f, get(DISPATCH + "/trace").param("purchaseOrderId", f.po())));
        assertThat(trace.size()).isEqualTo(2);
        assertThat(trace.findValuesAsText("salesOrderId")).containsOnly(f.salesOrder());
        assertThat(trace.findValuesAsText("receiptId")).containsOnly(f.receipt());
        assertThat(trace.findValuesAsText("batchNo")).containsOnly(f.batch());
        assertThat(trace.findValues("quantity").stream().mapToInt(JsonNode::asInt).sum()).isEqualTo(30);
        call(f, get(SALES + "/{id}/warehouse-shipments", f.salesOrder()))
                .andExpect(jsonPath("$.data.packages.length()").value(1))
                .andExpect(jsonPath("$.data.packages[0].packageId").value(packageId));
    }

    @Test
    void splitPackagesAdvancePartialAndCompleteFulfillmentWithoutRegressingDeliveredOrders() throws Exception {
        Fixture f = fixture(30);
        String pick = actualPick(f, 30);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        pack(f, first, "[{\"pickId\":\"" + pick + "\",\"quantity\":10}]").andExpect(status().isOk());
        pack(f, second, "[{\"pickId\":\"" + pick + "\",\"quantity\":20}]").andExpect(status().isOk());
        weigh(f, first).andExpect(status().isOk());
        weigh(f, second).andExpect(status().isOk());
        ship(f, first).andExpect(status().isOk());
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(230)).andExpect(jsonPath("$.data[0].reserved").value(20));
        call(f, get(SALES + "/{id}", f.salesOrder())).andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.fulfillmentStatus").value("PARTIALLY_SHIPPED"));
        call(f, get(WMS + "/sales-picking/{id}", f.outbound()), SHIP)
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_SHIPPED"));
        ship(f, second).andExpect(status().isOk());
        call(f, patch(SALES + "/{id}/deliver", f.salesOrder())).andExpect(status().isOk());
        for (String packageId : new String[]{first, second}) {
            ship(f, packageId).andExpect(status().isOk());
            call(f, post(DISPATCH + "/packages/{id}/sync", packageId), SHIP).andExpect(status().isOk());
        }
        call(f, post(SALES + "/{id}/warehouse-shipments/refresh", f.salesOrder()), "oms:warehouse:write")
                .andExpect(jsonPath("$.data.shippedQuantity").value(30)).andExpect(jsonPath("$.data.orderStatus").value("DELIVERED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(210)).andExpect(jsonPath("$.data[0].reserved").value(0));
        call(f, get(WMS + "/sales-picking/{id}", f.outbound()), SHIP).andExpect(jsonPath("$.data.status").value("SHIPPED"));
    }

    @Test
    void packageValidationPermissionsAndLegacyEntriesCannotShipUnverifiedGoods() throws Exception {
        Fixture f = fixture(30);
        String pick = actualPick(f, 10);
        String packageId = UUID.randomUUID().toString();
        String lines = "[{\"pickId\":\"" + pick + "\",\"quantity\":10}]";
        pack(f, packageId, "[{\"pickId\":\"" + pick + "\",\"quantity\":11}]")
                .andExpect(jsonPath("$.code").value("SALES_PACKAGE_EXCEEDS_PICKED"));
        pack(f, packageId, "[{\"pickId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]")
                .andExpect(jsonPath("$.code").value("SALES_PACKAGE_PICK_INVALID"));
        JsonNode packaged = data(pack(f, packageId, lines));
        pack(f, packageId, lines).andExpect(status().isOk());
        pack(f, packageId, "[{\"pickId\":\"" + pick + "\",\"quantity\":9}]")
                .andExpect(jsonPath("$.code").value("SALES_PACKAGE_REQUEST_CONFLICT"));
        pack(f, UUID.randomUUID().toString(), lines).andExpect(jsonPath("$.code").value("SALES_PACKAGE_EXCEEDS_PICKED"));
        ship(f, packageId).andExpect(jsonPath("$.code").value("SALES_PACKAGE_WEIGHT_REQUIRED"));
        call(f, post(DISPATCH + "/packages/{id}/sync", packageId), SHIP).andExpect(jsonPath("$.code").value("SALES_PACKAGE_NOT_SHIPPED"));
        call(f, post(DISPATCH + "/packages/{id}/weight", packageId).content("{\"weightKg\":0}"), SHIP)
                .andExpect(status().isBadRequest());
        call(f, post(DISPATCH + "/packages/{id}/ship", packageId).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"TRACK-08\"}"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        Fixture other = new Fixture("other-" + f.tenant(), f.warehouse(), f.po(), f.receipt(), f.batch(), f.salesOrder(), f.outbound(), f.allocation());
        ship(other, packageId).andExpect(jsonPath("$.code").value("SALES_PACKAGE_NOT_FOUND"));
        call(other, get(DISPATCH + "/trace").param("batchNo", f.batch())).andExpect(jsonPath("$.data").isEmpty());
        call(f, post(WMS + "/outbound-orders/{id}/packages", f.outbound()).content("""
                {"lines":[{"lineId":"%s","quantity":1}]}
                """.formatted(packaged.at("/lines/0/outboundLineId").asText())))
                .andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        call(f, post(WMS + "/packages/{id}/weigh", packageId).content("{\"weightKg\":1.5}"))
                .andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        call(f, post(WMS + "/packages/{id}/ship", packageId).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"TRACK-08\"}"))
                .andExpect(jsonPath("$.code").value("SALES_BATCH_PICK_REQUIRED"));
        call(f, get(WMS + "/sales-picking/work"), SHIP)
                .andExpect(jsonPath("$.data.canPick").value(false)).andExpect(jsonPath("$.data.canShip").value(true))
                .andExpect(jsonPath("$.data.orders.length()").value(1));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(240)).andExpect(jsonPath("$.data[0].reserved").value(30));
        call(f, get(DISPATCH + "/outbounds/{id}/packages", f.outbound())).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void concurrentConfirmationOfTheSamePackagePostsOneShipmentAndOneSetOfStockMovements() throws Exception {
        Fixture f = fixture(30);
        String pick = actualPick(f, 30);
        String packageId = UUID.randomUUID().toString();
        pack(f, packageId, "[{\"pickId\":\"" + pick + "\",\"quantity\":30}]").andExpect(status().isOk());
        weigh(f, packageId).andExpect(status().isOk());
        race(() -> data(ship(f, packageId)), () -> data(ship(f, packageId)));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(210)).andExpect(jsonPath("$.data[0].reserved").value(0));
        JsonNode movements = data(call(f, get(WMS + "/inventory-ledger/transactions").param("documentId", packageId)));
        assertThat(movements.size()).isEqualTo(1);
        call(f, get(SALES + "/{id}/warehouse-shipments", f.salesOrder())).andExpect(jsonPath("$.data.shippedQuantity").value(30));
    }

    @Test
    void failedOmsPersistenceLeavesShipmentCommittedAndCanBeRetriedWithoutAnotherDeduction() throws Exception {
        Fixture f = fixture(30);
        String pick = actualPick(f, 30);
        String packageId = UUID.randomUUID().toString();
        pack(f, packageId, "[{\"pickId\":\"" + pick + "\",\"quantity\":30}]").andExpect(status().isOk());
        weigh(f, packageId).andExpect(status().isOk());
        String fault = "wms08_fault_" + UUID.randomUUID().toString().replace("-", "");
        // 在真实数据库边界注入仅针对本用例租户的落库故障；业务结果仍全部通过 HTTP 验证。
        database.execute("CREATE FUNCTION public." + fault + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.tenant_id = '" + f.tenant() + "' THEN RAISE EXCEPTION 'simulated OMS persistence outage'; END IF; RETURN NEW; END $$");
        try {
            database.execute("CREATE TRIGGER " + fault + " BEFORE INSERT ON oms_warehouse_shipment "
                    + "FOR EACH ROW EXECUTE FUNCTION public." + fault + "()");
            try {
                ship(f, packageId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SHIPPED"))
                        .andExpect(jsonPath("$.data.omsSyncStatus").value("PENDING"))
                        .andExpect(jsonPath("$.data.syncError").value("订单回写失败，可重试"));
                stock(f).andExpect(jsonPath("$.data[0].onHand").value(210)).andExpect(jsonPath("$.data[0].reserved").value(0));
                call(f, get(SALES + "/{id}/warehouse-shipments", f.salesOrder()))
                        .andExpect(jsonPath("$.data.orderStatus").value("PAID")).andExpect(jsonPath("$.data.shippedQuantity").value(0));
            } finally { database.execute("DROP TRIGGER " + fault + " ON oms_warehouse_shipment"); }
        } finally { database.execute("DROP FUNCTION public." + fault + "()"); }
        call(f, post(DISPATCH + "/packages/{id}/sync", packageId), SHIP).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.omsSyncStatus").value("SYNCED"));
        ship(f, packageId).andExpect(status().isOk());
        call(f, post(SALES + "/{id}/warehouse-shipments/refresh", f.salesOrder()), "oms:warehouse:write")
                .andExpect(jsonPath("$.data.shippedQuantity").value(30)).andExpect(jsonPath("$.data.orderStatus").value("SHIPPED"));
        stock(f).andExpect(jsonPath("$.data[0].onHand").value(210));
        call(f, get(WMS + "/inventory-ledger/transactions").param("documentId", packageId))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void originalOmsShipmentEntryChecksTheLoginTenantAndWarehousePermission() throws Exception {
        Fixture f = fixture(30);
        call(f, patch(SALES + "/{id}/ship", f.salesOrder())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(patch(SALES + "/{id}/ship", f.salesOrder()).header("X-Tenant-Id", f.tenant())
                .header("Authorization", "Bearer " + jwt.createToken("other-tenant", "foreign-user", "other", Set.of("oms:warehouse:write"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        call(f, patch(SALES + "/{id}/ship", f.salesOrder()), "oms:warehouse:write")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    void packageInputRejectsNullLinesAndWeightsThatCannotBeStoredPrecisely() throws Exception {
        Fixture f = fixture(30);
        String pick = actualPick(f, 30);
        String packageId = UUID.randomUUID().toString();
        pack(f, packageId, "[null]").andExpect(status().isBadRequest());
        pack(f, packageId, "[{\"pickId\":\"" + pick + "\",\"quantity\":30}]").andExpect(status().isOk());
        for (String weight : new String[]{"0.0001", "1.2345", "10000000"}) {
            call(f, post(DISPATCH + "/packages/{id}/weight", packageId).content("{\"weightKg\":" + weight + "}"), SHIP)
                    .andExpect(status().isBadRequest());
        }
        call(f, post(DISPATCH + "/packages/{id}/weight", packageId).content("{\"weightKg\":1.234}"), SHIP)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.weightKg").value(1.234));
        ship(f, packageId).andExpect(status().isOk());
        call(f, post(DISPATCH + "/packages/{id}/weight", packageId).content("{\"weightKg\":1.234}"), SHIP).andExpect(status().isOk());
    }

    private void race(Callable<JsonNode> first, Callable<JsonNode> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .allSatisfy(s -> assertThat(s.path("status").asText()).isEqualTo("SHIPPED"));
        } finally { executor.shutdownNow(); }
    }

    private Fixture fixture(int salesQuantity) throws Exception {
        Fixture f = new Fixture("sales-ship-" + UUID.randomUUID(), null, null, null, null, null, null, null);
        String supplier = data(call(f, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Shipping supplier\",\"countryCode\":\"CN\"}")))
                .at("/supplier/supplierId").asText();
        String po = data(call(f, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":[
                {"lineId":"L1","sellerSku":"MUG-08","quantity":240,"unitCost":10}]}
                """.formatted(supplier)))).path("poId").asText();
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/submit", po)).andExpect(status().isOk());
        call(f, patch("/scm/api/in/v1/purchase-orders/{po}/approve", po)).andExpect(status().isOk());
        String wh = data(call(f, post(WMS + "/warehouses").content("{\"code\":\"SHIP08\",\"name\":\"Dispatch Warehouse\",\"countryCode\":\"US\"}")))
                .path("warehouseId").asText();
        f = new Fixture(f.tenant(), wh, po, null, null, null, null, null);
        JsonNode receipt = data(call(f, post(WMS + "/purchase-receipts/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":100,"supplierBatchNo":"FIRST"}]}
                """.formatted(UUID.randomUUID(), wh)), "wms:receipt:write"));
        String batch = receipt.at("/lines/0/batchNo").asText();
        call(f, post(WMS + "/purchase-receipts/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":140,"supplierBatchNo":"SECOND"}]}
                """.formatted(UUID.randomUUID(), wh)), "wms:receipt:write").andExpect(status().isOk());
        call(f, post(WMS + "/purchase-inspections/{b}/sample", batch).content("""
                {"checkedQuantity":20,"passedQuantity":19,"failedQuantity":1}
                """), "wms:inspection:write").andExpect(status().isOk());
        call(f, post(WMS + "/purchase-inspections/{b}/recheck", batch).content("""
                {"method":"FULL","checkedQuantity":100,"passedQuantity":97,"failedQuantity":3}
                """), "wms:inspection:write").andExpect(status().isOk());
        call(f, post(WMS + "/purchase-inspections/{b}/disposition", batch).content("{\"goodQuantity\":97,\"defectiveQuantity\":3}"), "wms:inspection:write")
                .andExpect(status().isOk());
        for (String code : new String[]{"A-01", "A-02"}) {
            call(f, post(WMS + "/warehouses/{wh}/locations", wh).content("""
                    {"locationCode":"%s","purpose":"GOOD","enabled":true}
                    """.formatted(code)), "wms:location:write").andExpect(status().isOk());
            call(f, post(WMS + "/purchase-putaways/{b}", batch).content("""
                    {"requestId":"%s","locationCode":"%s","quantity":%d}
                    """.formatted(UUID.randomUUID(), code, code.equals("A-01") ? 60 : 37)), "wms:putaway:write")
                    .andExpect(status().isOk());
        }
        String sales = data(call(f, post(SALES + "/import").content("""
                {"platform":"Amazon","platformOrderNo":"%s","buyerName":"Buyer-%s","countryCode":"US",
                "shippingAddress":"1 Main Street","currency":"USD","lines":[{"lineId":"L1","sellerSku":"MUG-08",
                "title":"Mug","quantity":%d,"unitPrice":25,"estimatedUnitCost":10}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), salesQuantity)))).path("orderId").asText();
        call(f, patch(SALES + "/{id}/paid", sales)).andExpect(status().isOk());
        JsonNode work = data(call(f, post(SALES + "/{id}/warehouse-work", sales).content("{\"warehouseId\":\"" + wh + "\"}"), "oms:warehouse:write"));
        return new Fixture(f.tenant(), wh, po, receipt.path("receiptId").asText(), batch, sales,
                work.path("outboundId").asText(), work.at("/allocations/0/allocationId").asText());
    }
    private String actualPick(Fixture f, int quantity) throws Exception {
        return data(call(f, post(WMS + "/sales-picking/{id}/picks", f.outbound()).content("""
                {"requestId":"%s","allocationId":"%s","locationCode":"A-01","batchNo":"%s","quantity":%d}
                """.formatted(UUID.randomUUID(), f.allocation(), f.batch(), quantity)), "wms:pick:write")).path("requestId").asText();
    }
    private ResultActions pack(Fixture f, String packageId, String lines) throws Exception {
        return call(f, post(DISPATCH + "/outbounds/{id}/packages", f.outbound()).content("""
                {"packageId":"%s","lines":%s}
                """.formatted(packageId, lines)), SHIP);
    }
    private ResultActions weigh(Fixture f, String packageId) throws Exception {
        return call(f, post(DISPATCH + "/packages/{id}/weight", packageId).content("{\"weightKg\":1.5}"), SHIP);
    }
    private ResultActions ship(Fixture f, String packageId) throws Exception {
        return call(f, post(DISPATCH + "/packages/{id}/ship", packageId).content("{\"carrierCode\":\"UPS\",\"trackingNo\":\"TRACK-08\"}"), SHIP);
    }
    private ResultActions stock(Fixture f) throws Exception { return call(f, get(WMS + "/warehouses/{id}/inventory", f.warehouse())); }
    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer " +
                jwt.createToken(f.tenant(), "dispatch-worker", "warehouse", Set.of(permissions))).contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        var result = response.andReturn().getResponse();
        var body = json.readTree(result.getContentAsString());
        assertThat(result.getStatus()).as("响应业务码 %s", body.path("code").asText()).isEqualTo(200);
        return body.path("data");
    }
    private record Fixture(String tenant, String warehouse, String po, String receipt, String batch,
                           String salesOrder, String outbound, String allocation) {}
}
