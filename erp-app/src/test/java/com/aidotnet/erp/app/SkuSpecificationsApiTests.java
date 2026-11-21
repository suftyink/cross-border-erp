package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ErpAppTest
@AutoConfigureMockMvc
class SkuSpecificationsApiTests {
    private static final String PDM = "/pdm/api/in/v1";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void migrationQuarantinesLegacyFreeTextWithoutInventingDictionaryIdentities() throws Exception {
        try (var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            var isolated=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            String schema="spec_migration_"+UUID.randomUUID().toString().replace("-","");
            try {
                isolated.execute("CREATE SCHEMA "+schema);isolated.execute("SET LOCAL search_path TO "+schema);
                isolated.execute("CREATE TABLE pdm_sku(tenant_id VARCHAR(64),sku_id VARCHAR(64),spu_id VARCHAR(64),seller_sku VARCHAR(128),identity_locked BOOLEAN DEFAULT FALSE,PRIMARY KEY(tenant_id,sku_id))");
                isolated.execute("CREATE TABLE pdm_product_variant(tenant_id VARCHAR(64),canonical_sku_id VARCHAR(64),sku_id VARCHAR(64),seller_sku VARCHAR(128),variant_attributes TEXT,variant_attrs TEXT)");
                isolated.execute("INSERT INTO pdm_sku(tenant_id,sku_id,spu_id,seller_sku) VALUES ('t','legacy','p','OLD'),('t','fallback','p','OLD-2'),('t','ordinary','p2','ORDINARY')");
                isolated.execute("INSERT INTO pdm_product_variant VALUES ('t','legacy','legacy','OLD','{}','{\"color\":\"red\"}'),('t','fallback','fallback','OLD-2','','{\"size\":\"L\"}')");
                var migration=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V60__structured_sku_specifications.sql"));
                migration.populate(connection);migration.populate(connection);
                org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,isolated.queryForObject("SELECT specification_review_required FROM pdm_sku WHERE sku_id='legacy'",Boolean.class));
                org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE,isolated.queryForObject("SELECT specification_review_required FROM pdm_sku WHERE sku_id='fallback'",Boolean.class));
                org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE,isolated.queryForObject("SELECT specification_review_required FROM pdm_sku WHERE sku_id='ordinary'",Boolean.class));
                org.junit.jupiter.api.Assertions.assertEquals(0,isolated.queryForObject("SELECT count(*) FROM pdm_spec_attribute",Integer.class));
                org.junit.jupiter.api.Assertions.assertEquals(0,isolated.queryForObject("SELECT count(*) FROM pdm_sku_spec_value",Integer.class));
                org.junit.jupiter.api.Assertions.assertEquals("{\"color\":\"red\"}",isolated.queryForObject("SELECT variant_attrs FROM pdm_product_variant WHERE sku_id='legacy'",String.class));
            } finally {connection.rollback();}
        }
    }

    @Test
    void historyKeepsCreateFirstAndSpecOnlyEditOmitsEmptyUpdate() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"HIST-1",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))).path("skuId").asText();
        JsonNode createdHistory=data(call(f.tenant(),get(PDM+"/skus/{id}/history",sku)));
        org.junit.jupiter.api.Assertions.assertEquals(1,createdHistory.size());
        org.junit.jupiter.api.Assertions.assertEquals("CREATE",createdHistory.get(0).path("action").asText());
        org.junit.jupiter.api.Assertions.assertEquals("红色",createdHistory.get(0).path("after").path("specifications").get(0).path("valueName").asText());

        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of(
                "specifications",List.of(choice(f.color(),f.blue()),choice(f.size(),f.small())))))).andExpect(status().isOk());
        JsonNode editedHistory=data(call(f.tenant(),get(PDM+"/skus/{id}/history",sku)));
        org.junit.jupiter.api.Assertions.assertEquals(2,editedHistory.size());
        org.junit.jupiter.api.Assertions.assertEquals("SPECIFICATION",editedHistory.get(1).path("action").asText());

        String active=data(call(f.tenant(),post(PDM+"/skus/save-and-enable").content(json.writeValueAsString(Map.of(
                "spuId",f.spu(),"sellerSku","HIST-2","title","HIST-2","baseUnit","件",
                "specifications",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))))))).path("skuId").asText();
        JsonNode activeHistory=data(call(f.tenant(),get(PDM+"/skus/{id}/history",active)));
        org.junit.jupiter.api.Assertions.assertEquals("CREATE",activeHistory.get(0).path("action").asText());
        org.junit.jupiter.api.Assertions.assertEquals("ENABLE",activeHistory.get(1).path("action").asText());
    }

    @Test
    void referencedDraftCannotChangeSpecificationsAndLegacyReviewBlocksCatalogUse() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"REF-SPEC",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))).path("skuId").asText();
        String supplier=data(call(f.tenant(),post("/scm/api/in/v1/suppliers").content("{\"name\":\"SpecSup\",\"countryCode\":\"CN\"}"))).path("supplier").path("supplierId").asText();
        call(f.tenant(),post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","purchaseType":"MARKET","currency":"USD","lines":[{"lineId":"L1","sellerSku":"REF-SPEC","quantity":1,"unitCost":10}]}
                """.formatted(supplier))).andExpect(status().isOk());
        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of(
                "specifications",List.of(choice(f.color(),f.blue()),choice(f.size(),f.small()))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.blue())),Map.of("attributeId",f.size(),"valueIds",List.of(f.small())))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_RANGE_IN_USE"));

        String legacySku=data(create(f,"LEGACY-REV",List.of())).path("skuId").asText();
        jdbc.update("UPDATE pdm_sku SET specification_review_required=TRUE,identity_locked=TRUE,status='ACTIVE',ever_enabled=TRUE WHERE tenant_id=? AND sku_id=?",f.tenant(),legacySku);
        jdbc.update("INSERT INTO pdm_product_variant(variant_id,tenant_id,spu_id,canonical_sku_id,sku_id,seller_sku,variant_name,variant_attributes,variant_attrs,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),f.tenant(),f.spu(),legacySku,legacySku,"LEGACY-REV","Legacy","","{\"size\":\"L\"}","ACTIVE");
        JsonNode issues=data(call(f.tenant(),get(PDM+"/skus/migration-issues")));
        org.junit.jupiter.api.Assertions.assertTrue(issues.toString().contains("LEGACY-REV"));
        org.junit.jupiter.api.Assertions.assertTrue(issues.toString().contains("VARIANT"));
        call(f.tenant(),post(PDM+"/skus/validate").content(json.writeValueAsString(Map.of("selections",List.of(Map.of("skuId",legacySku))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].valid").value(false))
                .andExpect(jsonPath("$.data[0].code").value("SKU_INCOMPLETE"));
    }

    @Test
    void allowsSameCombinationAcrossDifferentSpusAndSafeRangeUpdatesAndCatalogProjection() throws Exception {
        Fixture f=fixture();
        String spu2=parent(f.tenant());
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",spu2).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.red(),f.blue())),Map.of("attributeId",f.size(),"valueIds",List.of(f.small()))))))).andExpect(status().isOk());
        String sku1=data(create(f,"SPU1-RED-S",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))).path("skuId").asText();
        call(f.tenant(),post(PDM+"/skus").content(json.writeValueAsString(Map.of(
                "spuId",spu2,"sellerSku","SPU2-RED-S","title","SPU2-RED-S","baseUnit","件",
                "specifications",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))))).andExpect(status().isOk());
        call(f.tenant(),post(PDM+"/skus/{id}/enable",sku1)).andExpect(status().isOk());

        call(f.tenant(),get(PDM+"/skus/eligible")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuId").value(sku1))
                .andExpect(jsonPath("$.data[0].specifications.length()").value(2));

        String green=value(f.tenant(),f.color(),"GREEN","绿色");
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.red(),green)),Map.of("attributeId",f.size(),"valueIds",List.of(f.small())))))))
                .andExpect(status().isOk());
        String material=data(call(f.tenant(),post(PDM+"/specification-attributes").content("{\"code\":\"MATERIAL\",\"name\":\"材质\"}"))).path("attributeId").asText();
        String cotton=value(f.tenant(),material,"COTTON","棉");
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.red(),green)),Map.of("attributeId",f.size(),"valueIds",List.of(f.small())),
                Map.of("attributeId",material,"valueIds",List.of(cotton)))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_RANGE_IN_USE"));
    }

    @Test
    void enforcesDictionaryCodesDeletionCrossTenantIsolationAndLegacyEndpointUniqueness() throws Exception {
        Fixture f=fixture(); Fixture foreign=fixture();
        call(f.tenant(),post(PDM+"/specification-attributes").content("{\"code\":\" color \",\"name\":\"重复属性\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_CODE_DUPLICATED"));
        call(f.tenant(),post(PDM+"/specification-attributes/{id}/values",f.color()).content("{\"code\":\" red \",\"name\":\"重复值\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_CODE_DUPLICATED"));
        call(f.tenant(),post(PDM+"/specification-attributes/{id}/values",f.size()).content("{\"code\":\"RED\",\"name\":\"跨属性同码允许\"}"))
                .andExpect(status().isOk());
        String unusedAttr=data(call(f.tenant(),post(PDM+"/specification-attributes").content("{\"code\":\"STYLE\",\"name\":\"款式\"}"))).path("attributeId").asText();
        String unusedVal=value(f.tenant(),unusedAttr,"CLASSIC","经典");
        call(f.tenant(),delete(PDM+"/specification-attributes/{a}/values/{v}",unusedAttr,unusedVal)).andExpect(status().isOk());
        call(f.tenant(),delete(PDM+"/specification-attributes/{a}",unusedAttr)).andExpect(status().isOk());
        call(f.tenant(),delete(PDM+"/specification-attributes/{a}",f.color()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_REFERENCED"));

        call(f.tenant(),get(PDM+"/products/{id}/specification-options",foreign.spu()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPU_NOT_FOUND"));
        call(f.tenant(),put(PDM+"/specification-attributes/{a}",foreign.color()).content("{\"name\":\"越权改名\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_ATTRIBUTE_NOT_FOUND"));
        call(f.tenant(),delete(PDM+"/specification-attributes/{a}/values/{v}",foreign.color(),foreign.red()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_ATTRIBUTE_NOT_FOUND"));

        String readOnly="Bearer "+jwt.createToken(f.tenant(),"reader","reader",Set.of());
        for (var req:List.of(
                put(PDM+"/specification-attributes/{a}",f.color()).content("{\"name\":\"禁止\"}"),
                delete(PDM+"/specification-attributes/{a}",f.color()),
                post(PDM+"/specification-attributes/{a}/values",f.color()).content("{\"code\":\"X\",\"name\":\"禁止\"}"),
                put(PDM+"/specification-attributes/{a}/values/{v}",f.color(),f.red()).content("{\"name\":\"禁止\"}"),
                delete(PDM+"/specification-attributes/{a}/values/{v}",f.color(),f.red()),
                put(PDM+"/products/{id}/specification-options",f.spu()).content("{\"dimensions\":[]}"))) {
            mvc.perform(req.header("X-Tenant-Id",f.tenant()).header("Authorization",readOnly).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        call(f.tenant(),post(PDM+"/skus").content(json.writeValueAsString(Map.of(
                "sellerSku","ROLLBACK-SPU","title","Should fail","newSpu",Map.of("title","Orphan"),
                "specifications",List.of(choice(f.color(),f.red()))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_VALUE_NOT_ALLOWED"));
        org.junit.jupiter.api.Assertions.assertEquals(1,data(call(f.tenant(),get(PDM+"/products"))).size());

        create(f,"LEG-1",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))).andExpect(status().isOk());
        call(f.tenant(),post(PDM+"/products/{spu}/skus",f.spu()).content(json.writeValueAsString(Map.of(
                "sellerSku","LEG-2","title","Dup","baseUnit","件",
                "specifications",List.of(choice(f.size(),f.small()),choice(f.color(),f.red()))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_COMBINATION_DUPLICATED"));
    }

    @Test
    void allowsOnlyUnusedDraftsToMoveAndCannotEnableAnUnspecifiedMultiVariant() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"MOVABLE",List.of())).path("skuId").asText();
        call(f.tenant(),post(PDM+"/skus/{id}/enable",sku)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKU_INCOMPLETE"));
        String ordinary=parent(f.tenant());
        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of("spuId",ordinary,"specifications",List.of()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.spuId").value(ordinary));
        call(f.tenant(),post(PDM+"/skus/{id}/enable",sku)).andExpect(status().isOk());
        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of("spuId",f.spu()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",ordinary).content(json.writeValueAsString(Map.of("dimensions",List.of(Map.of("attributeId",f.color(),"valueIds",List.of(f.red())))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_RANGE_IN_USE"));
    }

    @Test
    void normalizesCombinationOrderAndKeepsEveryStateOccupyingItsCombination() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"RED-S",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))))
                .path("skuId").asText();
        call(f.tenant(),get(PDM+"/skus/{id}",sku)).andExpect(jsonPath("$.data.specifications.length()").value(2));
        for (String next:List.of("enable","disable")) {
            call(f.tenant(),post(PDM+"/skus/{id}/"+next,sku)).andExpect(status().isOk());
            create(f,"DUP-"+next,List.of(choice(f.size(),f.small()),choice(f.color(),f.red())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_COMBINATION_DUPLICATED"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RED-S")));
        }
        create(f,"BLUE-S",List.of(choice(f.color(),f.blue()),choice(f.size(),f.small()))).andExpect(status().isOk());
    }

    @Test
    void legacyVariantProjectsTheCanonicalSpecificationsAndRejectsFreeText() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"CANONICAL",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))).path("skuId").asText();
        JsonNode variant=data(call(f.tenant(),post(PDM+"/product-variants").content(json.writeValueAsString(Map.of(
                "spuId",f.spu(),"sellerSku","CANONICAL","variantName","红色小号")))));
        org.junit.jupiter.api.Assertions.assertEquals(sku,variant.path("skuId").asText());
        org.junit.jupiter.api.Assertions.assertEquals(2,variant.path("specifications").size());
        call(f.tenant(),post(PDM+"/product-variants").content(json.writeValueAsString(Map.of(
                "spuId",f.spu(),"sellerSku","RAW","variantName","自由文本","variantAttributes","{\"color\":\"red\"}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STRUCTURED_SPEC_REQUIRED"));
    }

    @Test
    void protectsUsedDictionariesAndLockedCombinationsWhileKeepingDisplaySnapshots() throws Exception {
        Fixture f=fixture();
        String sku=data(create(f,"LOCKED",List.of(choice(f.color(),f.red()),choice(f.size(),f.small())))).path("skuId").asText();
        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of("specifications",List.of(choice(f.color(),f.blue()),choice(f.size(),f.small()))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.specifications[0].valueName").value("蓝色"));
        call(f.tenant(),post(PDM+"/skus/{id}/enable",sku)).andExpect(status().isOk());
        call(f.tenant(),post(PDM+"/skus/{id}/disable",sku)).andExpect(status().isOk());
        call(f.tenant(),put(PDM+"/skus/{id}",sku).content(json.writeValueAsString(Map.of("specifications",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(f.tenant(),delete(PDM+"/specification-attributes/{attr}/values/{value}",f.color(),f.blue()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_REFERENCED"));
        call(f.tenant(),put(PDM+"/specification-attributes/{attr}/values/{value}",f.color(),f.blue()).content("{\"code\":\"GREEN\",\"name\":\"绿色\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_IDENTITY_LOCKED"));
        call(f.tenant(),put(PDM+"/specification-attributes/{attr}/values/{value}",f.color(),f.blue()).content("{\"name\":\"海蓝色\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valueId").value(f.blue()));
        call(f.tenant(),get(PDM+"/skus/{id}",sku)).andExpect(jsonPath("$.data.specifications[0].valueName").value("海蓝色"));
        JsonNode events=data(call(f.tenant(),get(PDM+"/skus/{id}/history",sku)));
        org.junit.jupiter.api.Assertions.assertTrue(events.toString().contains("蓝色"));
        org.junit.jupiter.api.Assertions.assertFalse(events.toString().contains("海蓝色"),"历史记录保存当时显示资料");
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content("{\"dimensions\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_RANGE_IN_USE"));
    }

    @Test
    void rejectsForeignAndInvalidSelectionsAndEnforcesMaintenancePermission() throws Exception {
        Fixture f=fixture(); Fixture foreign=fixture();
        create(f,"FOREIGN",List.of(choice(foreign.color(),foreign.red()),choice(f.size(),f.small())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_VALUE_NOT_ALLOWED"));
        create(f,"WRONG-ATTRIBUTE",List.of(choice(f.color(),f.small()),choice(f.size(),f.small())))
                .andExpect(status().isBadRequest());
        create(f,"DUP-ATTRIBUTE",List.of(choice(f.color(),f.red()),choice(f.color(),f.blue())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_ATTRIBUTE_DUPLICATED"));
        create(f,"PARTIAL",List.of(choice(f.color(),f.red())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_COMBINATION_INCOMPLETE"));
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(Map.of("attributeId",foreign.color(),"valueIds",List.of(foreign.red())))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_ATTRIBUTE_NOT_FOUND"));
        mvc.perform(post(PDM+"/specification-attributes").header("X-Tenant-Id",f.tenant())
                .header("Authorization","Bearer "+jwt.createToken(f.tenant(),"reader","reader",Set.of()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"X\",\"name\":\"禁止\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void concurrentCreationCannotDuplicateACombinationAndDraftDeletionReleasesIt() throws Exception {
        Fixture f=fixture();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(() -> {start.await();return create(f,"RACE-A",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))).andReturn().getResponse().getStatus();});
            var b=pool.submit(() -> {start.await();return create(f,"RACE-B",List.of(choice(f.size(),f.small()),choice(f.color(),f.red()))).andReturn().getResponse().getStatus();});
            start.countDown(); var results=new java.util.ArrayList<>(List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS)));
            results.sort(Integer::compareTo); org.junit.jupiter.api.Assertions.assertEquals(List.of(200,400),results);
        } finally {pool.shutdownNow();}
        JsonNode rows=data(call(f.tenant(),get(PDM+"/skus")));org.junit.jupiter.api.Assertions.assertEquals(1,rows.size());
        call(f.tenant(),delete(PDM+"/skus/{id}",rows.get(0).path("skuId").asText())).andExpect(status().isOk());
        create(f,"REPLACEMENT",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))).andExpect(status().isOk());
    }

    private Fixture fixture() throws Exception {
        String tenant="spec-combination-"+UUID.randomUUID();
        String color=data(call(tenant,post(PDM+"/specification-attributes").content("{\"code\":\"COLOR\",\"name\":\"颜色\"}"))).path("attributeId").asText();
        String red=value(tenant,color,"RED","红色"),blue=value(tenant,color,"BLUE","蓝色");
        String size=data(call(tenant,post(PDM+"/specification-attributes").content("{\"code\":\"SIZE\",\"name\":\"尺寸\"}"))).path("attributeId").asText();
        String small=value(tenant,size,"S","小号");
        String spu=parent(tenant);
        call(tenant,put(PDM+"/products/{id}/specification-options",spu).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",color,"valueIds",List.of(red,blue)),Map.of("attributeId",size,"valueIds",List.of(small))))))).andExpect(status().isOk());
        return new Fixture(tenant,spu,color,red,blue,size,small);
    }
    private String value(String tenant,String attr,String code,String name) throws Exception {
        return data(call(tenant,post(PDM+"/specification-attributes/{id}/values",attr).content(json.writeValueAsString(Map.of("code",code,"name",name))))).path("valueId").asText();
    }
    private Map<String,String> choice(String attr,String value) { return Map.of("attributeId",attr,"valueId",value); }
    private ResultActions create(Fixture f,String code,List<Map<String,String>> specs) throws Exception {
        return call(f.tenant(),post(PDM+"/skus").content(json.writeValueAsString(Map.of("spuId",f.spu(),"sellerSku",code,"title",code,"baseUnit","件","specifications",specs))));
    }
    private record Fixture(String tenant,String spu,String color,String red,String blue,String size,String small) {}

    @Test
    void maintainsSharedAttributesAndSelectsAnExplicitSpuRange() throws Exception {
        String tenant = "spec-dictionary-" + UUID.randomUUID();
        JsonNode color = data(call(tenant, post(PDM + "/specification-attributes").content("{\"code\":\"color\",\"name\":\"颜色\"}")));
        String attr = color.path("attributeId").asText();
        JsonNode red = data(call(tenant, post(PDM + "/specification-attributes/{id}/values", attr).content("{\"code\":\"RED\",\"name\":\"红色\"}")));
        String parent = parent(tenant);
        call(tenant, put(PDM + "/products/{id}/specification-options", parent).content("""
                {"dimensions":[{"attributeId":"%s","valueIds":["%s"]}]}
                """.formatted(attr, red.path("valueId").asText()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimensions[0].attributeName").value("颜色"))
                .andExpect(jsonPath("$.data.dimensions[0].values[0].name").value("红色"));
        call(tenant, get(PDM + "/specification-attributes")).andExpect(jsonPath("$.data[0].code").value("COLOR"))
                .andExpect(jsonPath("$.data[0].values.length()").value(1));
    }

    private String parent(String tenant) throws Exception {
        String category = data(call(tenant, post(PDM + "/categories").content("{\"name\":\"分类\"}"))).path("categoryId").asText();
        return data(call(tenant, post(PDM + "/products").content("{\"sku\":\"SPU-"+UUID.randomUUID()+"\",\"title\":\"Parent\",\"categoryId\":\"" + category + "\"}"))).path("spuId").asText();
    }

    private ResultActions call(String tenant, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id", tenant).header("Authorization", "Bearer " + jwt.createToken(tenant,"writer","writer",Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
}
