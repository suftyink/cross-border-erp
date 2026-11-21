package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;

@ErpAppTest
@AutoConfigureMockMvc
class SkuDraftApiTests {
    private static final String SKUS = "/pdm/api/in/v1/skus";
    private static final String PRODUCTS = "/pdm/api/in/v1/products";
    private static final Set<String> WRITE = Set.of("pdm:product:write");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProvider jwt;

    @Test
    void createsAnIncompleteDraftAndQueriesTheSameSkuAcrossBothEntrances() throws Exception {
        String tenant = "sku-" + UUID.randomUUID();
        String category = id(call(tenant, post("/pdm/api/in/v1/categories")
                .content("{\"name\":\"Kitchen\"}")), "/data/categoryId");
        String brand = id(call(tenant, post("/pdm/api/in/v1/brands")
                .content("{\"name\":\"Example\"}")), "/data/brandId");
        String spu = id(call(tenant, post(PRODUCTS).content("""
                {"sku":"MUG-SPU","title":"Mugs","categoryId":"%s","brandId":"%s"}
                """.formatted(category, brand))), "/data/spuId");
        String sku = id(call(tenant, post(SKUS).content("""
                {"spuId":"%s","sellerSku":" mug-01 ","title":"Black mug","baseUnit":"件"}
                """.formatted(spu)))
                .andExpect(jsonPath("$.data.sellerSku").value("MUG-01"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.baseUnit").value("件")), "/data/skuId");
        call(tenant, get(SKUS).param("keyword", "MUG-01").param("spuId", spu).param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(sku))
                .andExpect(jsonPath("$.data[0].categoryId").value(category))
                .andExpect(jsonPath("$.data[0].brandId").value(brand));
        call(tenant, get(PRODUCTS + "/{spu}/skus", spu))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuId").value(sku))
                .andExpect(jsonPath("$.data[0].baseUnit").value("件"));
    }

    private ResultActions call(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", tenant)
                .header("Authorization", "Bearer " + jwt.createToken(tenant, "sku-worker", "worker", WRITE))
                .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void rejectsNonAsciiCodesWithoutLeavingAnOrdinaryProductBehind() throws Exception {
        String tenant = "sku-invalid-" + UUID.randomUUID();
        call(tenant, post(SKUS).content("""
                {"sellerSku":"ß","newSpu":{"title":"Should not exist"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_CODE_INVALID"));
        call(tenant, get(PRODUCTS)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void createsOrdinaryProductAtomicallyAndRollsBackTheParentWhenTheCodeConflicts() throws Exception {
        String tenant = "sku-atomic-" + UUID.randomUUID();
        String sku = createOrdinary(tenant, " mug-02 ");
        call(tenant, get(SKUS + "/{sku}", sku))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sellerSku").value("MUG-02"))
                .andExpect(jsonPath("$.data.spuCode").isNotEmpty());
        call(tenant, post(SKUS).content("""
                {"sellerSku":"MUG-02","newSpu":{"title":"Rolled back parent"}}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_DUPLICATED"));
        call(tenant, get(PRODUCTS)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        createOrdinary("sku-other-" + UUID.randomUUID(), "MUG-02");
    }

    @Test
    void editsDraftDetailsKeepsItsIdentityAndFindsItWithCombinedFilters() throws Exception {
        String tenant = "sku-edit-" + UUID.randomUUID();
        String sku = createOrdinary(tenant, "EDIT-01");
        String spu = id(call(tenant, get(SKUS + "/{sku}", sku)), "/data/spuId");
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"title\":\"Updated mug\",\"baseUnit\":\"件\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("Updated mug"))
                .andExpect(jsonPath("$.data.baseUnit").value("件"));
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"sellerSku\":\"CHANGED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(tenant, put(SKUS + "/{sku}", sku).content("{\"baseUnit\":\"套\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(tenant, get(SKUS).param("keyword", "Updated").param("spuId", spu).param("status", "DRAFT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].skuId").value(sku));
        call(tenant, get(PRODUCTS + "/{spu}/skus", spu))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].title").value("Updated mug"));
        call(tenant, get(SKUS).param("keyword", "Updated").param("status", "ACTIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void generatesAValidCodeAndKeepsMissingDraftFieldsVisible() throws Exception {
        String tenant = "sku-generated-" + UUID.randomUUID();
        call(tenant, get(SKUS + "/generated-code"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.matchesPattern("SKU-[A-Z0-9_-]+")));
        String sku = id(call(tenant, post(SKUS).content("{\"newSpu\":{}}"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.missingFields.length()").value(3)), "/data/skuId");
        call(tenant, get(SKUS + "/{sku}", sku)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerSku").value(org.hamcrest.Matchers.matchesPattern("SKU-[A-Z0-9_-]+")));
    }

    @Test
    void enforcesWritePermissionsAndTenantIsolationOnBothEntrances() throws Exception {
        String tenant = "sku-security-" + UUID.randomUUID();
        String sku = createOrdinary(tenant, "SECURE-01");
        String spu = id(call(tenant, get(SKUS + "/{sku}", sku)), "/data/spuId");
        String other = "sku-isolated-" + UUID.randomUUID();
        call(other, get(SKUS)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        call(other, get(SKUS + "/{sku}", sku))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
        call(other, put(SKUS + "/{sku}", sku).content("{\"title\":\"Foreign edit\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_NOT_FOUND"));
        String readToken = "Bearer " + jwt.createToken(tenant, "reader", "reader", Set.of());
        mvc.perform(post(SKUS).header("X-Tenant-Id", tenant).header("Authorization", readToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newSpu\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put(SKUS + "/{sku}", sku).header("X-Tenant-Id", tenant).header("Authorization", readToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Unauthorized\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(PRODUCTS + "/{spu}/skus", spu).header("X-Tenant-Id", tenant).header("Authorization", readToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sellerSku\":\"FORBIDDEN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(SKUS + "/access").header("X-Tenant-Id", tenant).header("Authorization", readToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canMaintain").value(false));
        call(other, post(SKUS).content("{\"spuId\":\"%s\",\"sellerSku\":\"FOREIGN\"}".formatted(spu)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPU_NOT_FOUND"));
        call(tenant, get(SKUS + "/{sku}", sku)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellerSku").value("SECURE-01"));
    }

    @Test
    void legacyCreationUsesTheSameNormalizationAndAllowsMissingLogisticsDetails() throws Exception {
        String tenant = "sku-legacy-" + UUID.randomUUID();
        String original = createOrdinary(tenant, "LEGACY-01");
        String spu = id(call(tenant, get(SKUS + "/{sku}", original)), "/data/spuId");
        call(tenant, post(PRODUCTS + "/{spu}/skus", spu).content("{\"sellerSku\":\" legacy-02 \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sellerSku").value("LEGACY-02"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        call(tenant, post(SKUS).content("{\"spuId\":\"%s\",\"sellerSku\":\"Legacy-02\"}".formatted(spu)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_DUPLICATED"));
    }

    @Test
    @Sql(statements = {
            "INSERT INTO pdm_spu(spu_id,tenant_id,title,status) VALUES('sku01-old-spu','sku01-old-tenant','Old product','ACTIVE')",
            "INSERT INTO pdm_sku(sku_id,tenant_id,spu_id,seller_sku,status) VALUES('sku01-old-sku','sku01-old-tenant','sku01-old-spu','OLD-01','ACTIVE')"
    })
    void keepsPreexistingActiveSkuReadOnlyInTheDraftEntrance() throws Exception {
        call("sku01-old-tenant", get(SKUS + "/sku01-old-sku"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false));
        call("sku01-old-tenant", put(SKUS + "/sku01-old-sku").content("{\"title\":\"Do not modify\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_NOT_EDITABLE"));
    }

    private String createOrdinary(String tenant, String code) throws Exception {
        return id(call(tenant, post(SKUS).content(mapper.writeValueAsString(Map.of(
                "sellerSku", code, "newSpu", Map.of("title", "Parent " + code))))), "/data/skuId");
    }

    @Test
    void concurrentFirstUnitAssignmentsCannotOverwriteTheFixedUnit() throws Exception {
        String tenant = "sku-race-" + UUID.randomUUID();
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String sku = createOrdinary(tenant, "UNIT-" + round);
                var barrier = new CyclicBarrier(2);
                var first = workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call(tenant, put(SKUS + "/{sku}", sku).content("{\"baseUnit\":\"件\"}"))
                            .andReturn().getResponse().getStatus();
                });
                var second = workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call(tenant, put(SKUS + "/{sku}", sku).content("{\"baseUnit\":\"套\"}"))
                            .andReturn().getResponse().getStatus();
                });
                var statuses = new java.util.ArrayList<>(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
                statuses.sort(Integer::compareTo);
                assertEquals(List.of(200, 400), statuses, "只有首次确定单位的请求可以成功");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void maintainsAnUnbrandedSpuAndProtectsInheritedFieldsWithProductWritePermission() throws Exception {
        String tenant = "sku-parent-" + UUID.randomUUID();
        String category = id(call(tenant, post("/pdm/api/in/v1/categories").content("{\"name\":\"Shared category\"}")), "/data/categoryId");
        String spu = id(call(tenant, post(PRODUCTS).content("""
                {"sku":"UNBRANDED","title":"Unbranded parent","categoryId":"%s"}
                """.formatted(category))), "/data/spuId");
        String sku = id(call(tenant, post(SKUS).content("{\"spuId\":\"%s\",\"sellerSku\":\"UNBRANDED-01\"}".formatted(spu))), "/data/skuId");
        call(tenant, put(PRODUCTS + "/{spu}", spu).content("{\"title\":\"Updated without brand\",\"brandId\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.brandId").doesNotExist());
        String reader = "Bearer " + jwt.createToken(tenant, "reader", "reader", Set.of());
        mvc.perform(put(PRODUCTS + "/{spu}", spu).header("Authorization", reader).header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categoryId\":\"OTHER\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/pdm/api/in/v1/categories").header("Authorization", reader).header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Unauthorized\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String foreign = "sku-parent-other-" + UUID.randomUUID();
        String foreignCategory = id(call(foreign, post("/pdm/api/in/v1/categories").content("{\"name\":\"Foreign\"}")), "/data/categoryId");
        call(tenant, put(PRODUCTS + "/{spu}", spu).content("{\"categoryId\":\"%s\"}".formatted(foreignCategory)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        call(tenant, get(SKUS + "/{sku}", sku)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spuTitle").value("Updated without brand"))
                .andExpect(jsonPath("$.data.categoryId").value(category));
    }

    private String id(ResultActions response, String pointer) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at(pointer).asText();
    }
}
