package com.aidotnet.erp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
class PurchaseOverreceiptApiTests {

    private static final String ORDERS = "/scm/api/in/v1/purchase-orders";
    private static final String SUPPLIERS = "/scm/api/in/v1/suppliers";
    private static final String RECEIPTS = "/wms/api/in/v1/purchase-receipts";
    private static final String AMEND = "scm:purchase:amend";
    private static final String CONFIGURE = "scm:supplier:tolerance:write";
    private static final String RECEIVE = "wms:receipt:write";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void freezesToleranceAtApprovalAndRequiresAnAmendmentAfterClosing() throws Exception {
        Fixture f = fixture();
        call(f, get(SUPPLIERS + "/{id}", f.supplier()))
                .andExpect(jsonPath("$.data.supplier.receiptTolerancePercent").value(0));
        String zeroOrder = approve(f);
        call(f, patch(SUPPLIERS + "/{id}/receipt-tolerance", f.supplier()).content("{\"percent\":5}"), RECEIVE)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        tolerance(f, 5);
        String po = approve(f);
        tolerance(f, 10);
        String newer = approve(f);
        order(f, newer).andExpect(jsonPath("$.data.receiptTolerancePercent").value(10));
        order(f, zeroOrder).andExpect(jsonPath("$.data.receiptTolerancePercent").value(0));
        receive(f, zeroOrder, 241).andExpect(status().isBadRequest());
        order(f, zeroOrder).andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(0));

        receive(f, po, 100).andExpect(status().isOk());
        order(f, po).andExpect(jsonPath("$.data.receiptTolerancePercent").value(5))
                .andExpect(jsonPath("$.data.lines[0].receivingLimit").value(252));
        receive(f, po, 153).andExpect(status().isBadRequest());
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(100));
        order(f, po).andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(100));
        String secondReceipt = id(receive(f, po, 145), "/data/receiptId");
        order(f, po).andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(245))
                .andExpect(jsonPath("$.data.lines[0].overReceivedQuantity").value(5));
        call(f, get(RECEIPTS + "/{id}/purchase-order", secondReceipt), RECEIVE)
                .andExpect(jsonPath("$.data.receiptTolerancePercent").value(5))
                .andExpect(jsonPath("$.data.overReceivedQuantity").value(5));
        call(new Fixture("other-tenant", null, null), get(RECEIPTS + "/{id}/purchase-order", secondReceipt), RECEIVE)
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_FOUND"));
        receive(f, po, 7).andExpect(status().isBadRequest());
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(245));

        String command = """
                {"reason":"Supplier follow-up delivery","lines":[{"lineId":"L1","quantity":270}]}
                """;
        call(f, post(ORDERS + "/{po}/amendments", po).content(command), RECEIVE)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f, post(ORDERS + "/{po}/amendments", po).content(command.replace("270", "245")), AMEND)
                .andExpect(status().isBadRequest());
        call(f, post(ORDERS + "/{po}/amendments", po).content(command), AMEND)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.data.totalAmount").value(2700))
                .andExpect(jsonPath("$.data.lines[0].pendingQuantity").value(25))
                .andExpect(jsonPath("$.data.lines[0].receivingLimit").value(270))
                .andExpect(jsonPath("$.data.lines[0].overReceivedQuantity").value(5));
        call(f, get(ORDERS + "/{po}/amendments", po), AMEND)
                .andExpect(jsonPath("$.data[0].previousQuantity").value(240))
                .andExpect(jsonPath("$.data[0].revisedQuantity").value(270))
                .andExpect(jsonPath("$.data[0].operatorId").value("test-operator"));
        call(f, get(ORDERS + "/{po}/trackings", po))
                .andExpect(jsonPath("$.data[0].orderedQuantity").value(270))
                .andExpect(jsonPath("$.data[0].pendingQuantity").value(25));
        receive(f, po, 26).andExpect(status().isBadRequest());
        receive(f, po, 25).andExpect(status().isOk());
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(270));
        order(f, po).andExpect(jsonPath("$.data.status").value("RECEIVED"));
    }

    @Test
    void approvalFlowSnapshotsOnlyWhenTheFinalApproverConfirms() throws Exception {
        Fixture f = fixture();
        tolerance(f, 7);
        String po = submit(f);
        String body = """
                {"approvers":[{"approverId":"buyer-lead"},{"approverId":"director"}]}
                """;
        var approvals = mapper.readTree(call(f, post(ORDERS + "/{po}/approvals", po).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        String endpoint = ORDERS + "/{po}/approvals/{approval}/approve";
        call(f, post(endpoint, po, approvals.get(0).path("approvalId").asText()).content("{}"))
                .andExpect(status().isOk());
        order(f, po).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        tolerance(f, 4);
        call(f, post(endpoint, po, approvals.get(1).path("approvalId").asText()).content("{}"))
                .andExpect(status().isOk());
        order(f, po).andExpect(jsonPath("$.data.receiptTolerancePercent").value(4))
                .andExpect(jsonPath("$.data.lines[0].receivingLimit").value(249));
    }

    @Test
    void concurrentReceiptsCannotBothUseTheSameRemainingAllowance() throws Exception {
        Fixture f = fixture();
        tolerance(f, 5);
        String po = submit(f, """
                [{"lineId":"L1","sellerSku":"MUG-01","quantity":240,"unitCost":10},
                 {"lineId":"L2","sellerSku":"MUG-02","quantity":10,"unitCost":10}]
                """);
        call(f, patch(ORDERS + "/{po}/approve", po)).andExpect(status().isOk());
        receive(f, po, 230).andExpect(status().isOk());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CyclicBarrier(2);
            Callable<String> request = () -> {
                start.await(10, TimeUnit.SECONDS);
                var response = receive(f, po, 15).andReturn().getResponse();
                if (response.getStatus() == 200) {
                    return "ACCEPTED";
                }
                assertThat(response.getStatus()).isEqualTo(400);
                return mapper.readTree(response.getContentAsString()).path("code").asText();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED", "PO_RECEIVE_EXCEEDS_ORDERED");
        } finally {
            executor.shutdownNow();
        }
        order(f, po).andExpect(jsonPath("$.data.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.data.lines[0].receivedQuantity").value(245))
                .andExpect(jsonPath("$.data.lines[1].pendingQuantity").value(10));
        inventory(f).andExpect(jsonPath("$.data[0].onHand").value(245));
        call(f, get(RECEIPTS).param("purchaseOrderId", po), RECEIVE)
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private Fixture fixture() throws Exception {
        Fixture tenant = new Fixture("overreceipt-" + UUID.randomUUID(), null, null);
        String supplier = id(call(tenant, post(SUPPLIERS).content("""
                {"name":"Test supplier","countryCode":"CN"}
                """)), "/data/supplier/supplierId");
        String warehouse = id(call(tenant, post("/wms/api/in/v1/warehouses").content("""
                {"code":"RCV","name":"Receiving Warehouse","countryCode":"US"}
                """)), "/data/warehouseId");
        return new Fixture(tenant.tenant(), supplier, warehouse);
    }

    private void tolerance(Fixture f, int percent) throws Exception {
        call(f, patch(SUPPLIERS + "/{id}/receipt-tolerance", f.supplier())
                .content("{\"percent\":" + percent + "}"), CONFIGURE).andExpect(status().isOk());
    }

    private String approve(Fixture f) throws Exception {
        String po = submit(f);
        call(f, patch(ORDERS + "/{po}/approve", po)).andExpect(status().isOk());
        return po;
    }

    private String submit(Fixture f) throws Exception {
        return submit(f, """
                [{"lineId":"L1","sellerSku":"MUG-01","quantity":240,"unitCost":10}]
                """);
    }

    private String submit(Fixture f, String lines) throws Exception {
        String po = id(call(f, post(ORDERS).content("""
                {"supplierId":"%s","currency":"USD","purchaseType":"MARKET","lines":%s}
                """.formatted(f.supplier(), lines))), "/data/poId");
        call(f, patch(ORDERS + "/{po}/submit", po)).andExpect(status().isOk());
        return po;
    }

    private ResultActions receive(Fixture f, String po, int quantity) throws Exception {
        return call(f, post(RECEIPTS + "/{po}", po).content("""
                {"receiptId":"%s","warehouseId":"%s","receipts":[{"lineId":"L1","quantity":%d}]}
                """.formatted(UUID.randomUUID(), f.warehouse(), quantity)), RECEIVE);
    }

    private ResultActions order(Fixture f, String po) throws Exception {
        return call(f, get(ORDERS + "/{po}", po)).andExpect(status().isOk());
    }

    private ResultActions inventory(Fixture f) throws Exception {
        return call(f, get("/wms/api/in/v1/warehouses/{id}/inventory", f.warehouse())).andExpect(status().isOk());
    }

    private ResultActions call(Fixture f, MockHttpServletRequestBuilder request, String... permissions) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", f.tenant()).header("Authorization", "Bearer "
                + jwt.createToken(f.tenant(), "test-operator", "operator", Set.of(permissions)))
                .contentType(MediaType.APPLICATION_JSON));
    }

    private String id(ResultActions response, String pointer) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at(pointer).asText();
    }

    private record Fixture(String tenant, String supplier, String warehouse) {}
}
