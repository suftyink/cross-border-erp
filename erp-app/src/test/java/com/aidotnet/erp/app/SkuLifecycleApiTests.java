package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class SkuLifecycleApiTests {
    private static final String SKUS = "/pdm/api/in/v1/skus";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void migratesOnlyProvenHistoryAndUnambiguousVariantLinksInPostgres() throws Exception {
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            var isolated = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            String schema = "sku_migration_" + UUID.randomUUID().toString().replace("-", "");
            try {
                isolated.execute("CREATE SCHEMA " + schema);
                isolated.execute("SET LOCAL search_path TO " + schema);
                isolated.execute("CREATE TABLE pdm_sku(sku_id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),spu_id VARCHAR(64),seller_sku VARCHAR(128),status VARCHAR(32))");
                isolated.execute("CREATE TABLE pdm_product_variant(variant_id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR(64),spu_id VARCHAR(64),sku_id VARCHAR(64),seller_sku VARCHAR(128),status VARCHAR(32))");
                isolated.execute("INSERT INTO pdm_sku VALUES ('active','t','p','ACTIVE','ACTIVE'),('inactive','t','p','INACTIVE','INACTIVE'),('draft','t','p','DRAFT','DRAFT'),('ambiguous','t','p','AMBIGUOUS','DRAFT')");
                isolated.execute("INSERT INTO pdm_product_variant VALUES ('known','t','p','active','ACTIVE','ACTIVE'),('a','t','p','ambiguous','AMBIGUOUS','DRAFT'),('b','t','p','ambiguous','AMBIGUOUS','DRAFT'),('conflict','t','p','inactive','OTHER','ACTIVE')");
                var migration = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V59__sku_lifecycle_and_reference_guard.sql"));
                migration.populate(connection);
                migration.populate(connection);
                org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, isolated.queryForObject("SELECT ever_enabled FROM pdm_sku WHERE sku_id='active'", Boolean.class));
                org.junit.jupiter.api.Assertions.assertNull(isolated.queryForObject("SELECT ever_enabled FROM pdm_sku WHERE sku_id='inactive'", Boolean.class));
                org.junit.jupiter.api.Assertions.assertNull(isolated.queryForObject("SELECT ever_enabled FROM pdm_sku WHERE sku_id='draft'", Boolean.class));
                org.junit.jupiter.api.Assertions.assertEquals(4, isolated.queryForObject("SELECT count(*) FROM pdm_sku WHERE identity_locked", Integer.class));
                org.junit.jupiter.api.Assertions.assertEquals("active", isolated.queryForObject("SELECT canonical_sku_id FROM pdm_product_variant WHERE variant_id='known'", String.class));
                org.junit.jupiter.api.Assertions.assertEquals(3, isolated.queryForObject("SELECT count(*) FROM pdm_product_variant WHERE canonical_sku_id IS NULL", Integer.class));
                org.junit.jupiter.api.Assertions.assertEquals(4, isolated.queryForObject("SELECT count(*) FROM pdm_sku_history", Integer.class));
            } finally { connection.rollback(); }
        }
    }

    @Test
    void parentEditsAndLegacyVariantStateCannotBypassTheCanonicalLifecycle() throws Exception {
        String tenant = "sku-parent-lock-" + UUID.randomUUID();
        String category = category(tenant);
        String sku = create(tenant, "BRIDGE", category);
        String parent = id(call(tenant, get(SKUS + "/{id}", sku)), "/data/spuId");
        String variant = id(call(tenant, post("/pdm/api/in/v1/product-variants").content("""
                {"spuId":"%s","sellerSku":"BRIDGE","variantName":"Variant","baseUnit":"件"}
                """.formatted(parent))), "/data/variantId");
        call(tenant, post("/pdm/api/in/v1/product-variants/{id}/activate", variant)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(sku)).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        call(tenant, delete(SKUS + "/{id}", sku)).andExpect(status().isBadRequest());
        call(tenant, put("/pdm/api/in/v1/products/{id}", parent).content("{\"categoryId\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_PARENT_LOCKED"));
        call(tenant, post(SKUS + "/{id}/disable", sku)).andExpect(status().isOk());
        call(tenant, get("/pdm/api/in/v1/product-variants/{id}", variant)).andExpect(jsonPath("$.data.status").value("INACTIVE"));
        call(tenant, get(SKUS + "/{id}/history", sku)).andExpect(jsonPath("$.data[1].action").value("ENABLE"))
                .andExpect(jsonPath("$.data[2].action").value("DISABLE"));
        jdbc.update("UPDATE pdm_product_variant SET seller_sku='CONFLICT' WHERE tenant_id=? AND variant_id=?", tenant, variant);
        call(tenant, post("/pdm/api/in/v1/product-variants/{id}/activate", variant)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VARIANT_HISTORY_REVIEW_REQUIRED"));
    }

    @Test
    void validatesEnableAndRollsBackSaveAndEnableAsOneAction() throws Exception {
        String tenant = "sku-atomic-" + UUID.randomUUID();
        call(tenant, post(SKUS + "/save-and-enable").content("{\"sellerSku\":\"ATOMIC\",\"newSpu\":{\"title\":\"Parent\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_INCOMPLETE"));
        call(tenant, get(SKUS)).andExpect(jsonPath("$.data.length()").value(0));
        String sku = create(tenant, "INCOMPLETE", null);
        call(tenant, put(SKUS + "/{id}/save-and-enable", sku).content("{\"title\":\"Must rollback\"}"))
                .andExpect(status().isBadRequest());
        call(tenant, get(SKUS + "/{id}", sku)).andExpect(jsonPath("$.data.title").value("Item"))
                .andExpect(jsonPath("$.data.everEnabled").value(false));
    }

    @Test
    void batchesIndependentlyAndReportsEligibilityWithoutLeakingOtherTenantData() throws Exception {
        String tenant = "sku-batch-" + UUID.randomUUID();
        String good = create(tenant, "GOOD", category(tenant));
        String incomplete = create(tenant, "BAD", null);
        String foreign = create("other-" + tenant, "SECRET", null);
        call(tenant, post(SKUS + "/batch-status").content(mapper.writeValueAsString(java.util.Map.of(
                "action", "ENABLE", "skuIds", java.util.List.of(good, incomplete, foreign)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].success").value(true))
                .andExpect(jsonPath("$.data[1].code").value("SKU_INCOMPLETE"))
                .andExpect(jsonPath("$.data[2].success").value(false));
        call(tenant, get(SKUS + "/eligible")).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(good));
        call(tenant, post(SKUS + "/validate").content("""
                {"selections":[{"skuId":"%s"},{"skuId":"%s"},{"skuId":"%s"},{"skuId":"missing"}]}
                """.formatted(good, incomplete, foreign)))
                .andExpect(jsonPath("$.data[0].valid").value(true))
                .andExpect(jsonPath("$.data[1].code").value("SKU_NOT_ACTIVE"))
                .andExpect(jsonPath("$.data[2].code").value("SKU_CROSS_TENANT"))
                .andExpect(jsonPath("$.data[2].item").isEmpty())
                .andExpect(jsonPath("$.data[3].code").value("SKU_NOT_FOUND"));
        call(tenant, post(SKUS + "/" + good + "/disable")).andExpect(status().isOk());
        call(tenant, get(SKUS + "/eligible")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void protectsZeroBalancesAndClosedLegacyReferencesAndIsolatesTenants() throws Exception {
        String tenant = "sku-legacy-" + UUID.randomUUID();
        String sku = create(tenant, "ZERO-BALANCE", null);
        jdbc.update("INSERT INTO wms_inventory_balance(tenant_id,warehouse_id,seller_sku,on_hand,reserved,in_transit,frozen,updated_at) VALUES (?,?,?,0,0,0,0,CURRENT_TIMESTAMP)", tenant, "W1", "ZERO-BALANCE");
        call(tenant, delete(SKUS + "/{id}", sku)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_REFERENCED"));
        jdbc.update("UPDATE wms_inventory_balance SET on_hand=0,reserved=0 WHERE tenant_id=?", tenant);
        call(tenant, delete(SKUS + "/{id}", sku)).andExpect(status().isBadRequest());
        String own = create("other-" + tenant, "ZERO-BALANCE", null);
        call("other-" + tenant, delete(SKUS + "/{id}", own)).andExpect(status().isOk());
    }

    @Test
    void oldVariantActivationUsesCanonicalSkuAndCannotSkipEnableValidation() throws Exception {
        String tenant = "sku-variant-" + UUID.randomUUID();
        String sku = create(tenant, "VARIANT-01", null);
        String parent = id(call(tenant, get(SKUS + "/{id}", sku)), "/data/spuId");
        String variant = id(call(tenant, post("/pdm/api/in/v1/product-variants").content("""
                {"spuId":"%s","sellerSku":"VARIANT-01","variantName":"Variant","baseUnit":"件","variantAttributes":"{}"}
                """.formatted(parent))), "/data/variantId");
        call(tenant, post("/pdm/api/in/v1/product-variants/{id}/activate", variant)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_INCOMPLETE"));
        call(tenant, get(SKUS + "/{id}", sku)).andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void permissionAndTenantChecksCoverNewWritesAndHistory() throws Exception {
        String tenant = "sku-perm-" + UUID.randomUUID();
        String sku = create(tenant, "PERM", category(tenant));
        String readOnly = jwt.createToken(tenant, "reader", "reader", Set.of("pdm:read"));
        for (var request : java.util.List.of(post(SKUS + "/{id}/enable", sku), post(SKUS + "/{id}/disable", sku), delete(SKUS + "/{id}", sku),
                post(SKUS + "/batch-status").content("{\"action\":\"ENABLE\",\"skuIds\":[\"" + sku + "\"]}"))) {
            mvc.perform(request.header("X-Tenant-Id", tenant).header("Authorization", "Bearer " + readOnly).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        call("other-" + tenant, get(SKUS + "/{id}/history", sku)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(post("/pdm/api/in/v1/product-variants").header("X-Tenant-Id", tenant)
                .header("Authorization", "Bearer " + jwt.createToken("other-" + tenant, "writer", "writer", Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":\"foreign\",\"variantName\":\"forbidden\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void saveAndEnableHistoryRetainsItsTransactionalOrder() throws Exception {
        String tenant = "sku-history-" + UUID.randomUUID();
        String category = category(tenant);
        String sku = id(call(tenant, post(SKUS + "/save-and-enable").content("""
                {"sellerSku":"HISTORY","title":"Item","baseUnit":"件","newSpu":{"title":"Parent","categoryId":"%s"}}
                """.formatted(category))), "/data/skuId");
        call(tenant, get(SKUS + "/{id}/history", sku)).andExpect(jsonPath("$.data[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data[1].action").value("ENABLE"));
    }

    @Test
    void enableAndIdentityEditSerializeAtTheHttpBoundary() throws Exception {
        String tenant = "sku-race-" + UUID.randomUUID();
        String sku = create(tenant, "RACE", category(tenant));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var enabling = pool.submit(() -> { start.await(); return call(tenant, post(SKUS + "/{id}/enable", sku)).andReturn().getResponse().getStatus(); });
            var editing = pool.submit(() -> { start.await(); return call(tenant, put(SKUS + "/{id}", sku).content("{\"baseUnit\":\"套\"}")).andReturn().getResponse().getStatus(); });
            start.countDown();
            org.junit.jupiter.api.Assertions.assertEquals(200, enabling.get(20, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTrue(java.util.Set.of(200,400).contains(editing.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            call(tenant, get(SKUS + "/{id}", sku)).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.identityLocked").value(true));
            call(tenant, put(SKUS + "/{id}", sku).content("{\"baseUnit\":\"台\"}")).andExpect(status().isBadRequest());
        } finally { pool.shutdownNow(); }
    }

    private String category(String tenant) throws Exception {
        return id(call(tenant, post("/pdm/api/in/v1/categories").content("{\"name\":\"Category\"}")), "/data/categoryId");
    }

    private String create(String tenant, String code, String category) throws Exception {
        return id(call(tenant, post(SKUS).content(mapper.writeValueAsString(java.util.Map.of("sellerSku", code, "title", "Item", "baseUnit", "件",
                "newSpu", category == null ? java.util.Map.of("title", "Parent") : java.util.Map.of("title", "Parent", "categoryId", category))))), "/data/skuId");
    }

    @Test
    void enablesWithoutApprovalAndKeepsIdentityLockedAfterDeactivation() throws Exception {
        String tenant = "sku-life-" + UUID.randomUUID();
        String category = id(call(tenant, post("/pdm/api/in/v1/categories").content("{\"name\":\"Kitchen\"}")), "/data/categoryId");
        String sku = id(call(tenant, post(SKUS).content("""
                {"sellerSku":"MUG-LIFE","title":"Mug","baseUnit":"件", "newSpu":{"title":"Mugs","categoryId":"%s"}}
                """.formatted(category))), "/data/skuId");
        call(tenant, post(SKUS + "/{sku}/enable", sku).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.everEnabled").value(true))
                .andExpect(jsonPath("$.data.identityLocked").value(true));
        call(tenant, post(SKUS + "/{sku}/disable", sku).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE"));
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"sellerSku\":\"CHANGED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"title\":\"New description\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("New description"));
        call(tenant, post(SKUS + "/{sku}/enable", sku).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        call(tenant, get(SKUS + "/{sku}", sku))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.everEnabled").value(true));
    }

    private ResultActions call(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", tenant)
                .header("Authorization", "Bearer " + jwt.createToken(tenant, "sku-maintainer", "maintainer", Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void aReferenceThroughTheLegacyPurchaseEntrancePermanentlyProtectsTheSku() throws Exception {
        String tenant = "sku-ref-" + UUID.randomUUID();
        String sku = id(call(tenant, post(SKUS).content("""
                {"sellerSku":"REFERENCE-01","title":"Referenced","baseUnit":"件","newSpu":{"title":"Parent"}}
                """)), "/data/skuId");
        String supplier = id(call(tenant, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Supplier\",\"countryCode\":\"CN\"}")), "/data/supplier/supplierId");
        call(tenant, post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","purchaseType":"MARKET","currency":"USD","lines":[
                  {"lineId":"L1","sellerSku":"REFERENCE-01","quantity":1,"unitCost":10}]}
                """.formatted(supplier))).andExpect(status().isOk());
        // 即使源单据行后来被替换，引用事实也不会被余额或源行的存在性抹掉。
        jdbc.update("DELETE FROM scm_purchase_order_line WHERE po_id IN (SELECT po_id FROM scm_purchase_order WHERE tenant_id=?)", tenant);
        call(tenant, get(SKUS + "/{sku}", sku))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.identityLocked").value(true))
                .andExpect(jsonPath("$.data.everEnabled").value(false));
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"sellerSku\":\"RENAMED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(tenant, delete(SKUS + "/{sku}", sku))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_REFERENCED"));
    }

    private String id(ResultActions response, String pointer) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at(pointer).asText();
    }

    @Test
    void deletionAndNewPurchaseReferenceCannotBothWin() throws Exception {
        String tenant = "sku-ref-race-" + UUID.randomUUID();
        String sku = create(tenant, "RACE-REF", null);
        String supplier = id(call(tenant, post("/scm/api/in/v1/suppliers").content("{\"name\":\"Supplier\",\"countryCode\":\"CN\"}")), "/data/supplier/supplierId");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var deleting = pool.submit(() -> { start.await(); return call(tenant, delete(SKUS + "/{id}", sku)).andReturn().getResponse().getStatus(); });
            var referring = pool.submit(() -> {
                start.await();
                return call(tenant, post("/scm/api/in/v1/purchase-orders").content("""
                    {"supplierId":"%s","purchaseType":"MARKET","currency":"USD","lines":[{"lineId":"L1","sellerSku":"RACE-REF","quantity":1,"unitCost":10}]}
                    """.formatted(supplier))).andReturn().getResponse().getStatus();
            });
            start.countDown();
            var results = new java.util.ArrayList<>(java.util.List.of(deleting.get(20, java.util.concurrent.TimeUnit.SECONDS), referring.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            results.sort(Integer::compareTo);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(200, 400), results);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void editsAnUnusedDraftIdentityAndDeletesItWithoutLeavingAReusableStaleReference() throws Exception {
        String tenant = "sku-free-" + UUID.randomUUID();
        String sku = id(call(tenant, post(SKUS).content("""
                {"sellerSku":"UNUSED-01","title":"Draft","baseUnit":"件","newSpu":{"title":"Parent"}}
                """)), "/data/skuId");
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"sellerSku\":\" unused_new \",\"baseUnit\":\"套\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sellerSku").value("UNUSED_NEW"))
                .andExpect(jsonPath("$.data.baseUnit").value("套"));
        call(tenant, delete(SKUS + "/{sku}", sku)).andExpect(status().isOk());
        call(tenant, get(SKUS + "/{sku}", sku)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
    }
}
