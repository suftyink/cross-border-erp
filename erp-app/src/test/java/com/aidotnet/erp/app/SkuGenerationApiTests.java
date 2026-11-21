package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
class SkuGenerationApiTests {
    private static final String PDM="/pdm/api/in/v1";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void truncatesSuggestedNamesWithoutSplittingAnEmojiAtTheLengthLimit() throws Exception {
        Fixture f=fixture();String title="X".repeat(510);
        call(f.tenant(),put(PDM+"/products/{id}",f.spu()).content(json.writeValueAsString(Map.of("title",title)))).andExpect(status().isOk());
        call(f.tenant(),put(PDM+"/specification-attributes/{attr}/values/{value}",f.color(),f.blue()).content(json.writeValueAsString(Map.of("name","🟦")))).andExpect(status().isOk());
        JsonNode result=preview(f);
        org.junit.jupiter.api.Assertions.assertEquals(title+" ",result.path("rows").get(0).path("title").asText());
    }

    @Test
    void generatesOnlyTheSelectedSubsetAndReplaysTheSameConfirmation() throws Exception {
        Fixture f=fixture();
        JsonNode preview=data(call(f.tenant(),post(generation(f)+"/preview").content("{}")));
        String request=json.writeValueAsString(Map.of("previewId",preview.path("previewId").asText(),"rows",List.of(
                requested(preview.path("rows").get(0)," gen-01 "),requested(preview.path("rows").get(1),"gen-02"))));
        JsonNode result=data(call(f.tenant(),post(generation(f)+"/commit").content(request)));
        org.junit.jupiter.api.Assertions.assertEquals(2,result.path("created").size());
        org.junit.jupiter.api.Assertions.assertEquals("GEN-01",result.path("created").get(0).path("sellerSku").asText());
        org.junit.jupiter.api.Assertions.assertEquals("DRAFT",result.path("created").get(0).path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(result,data(call(f.tenant(),post(generation(f)+"/commit").content(request))));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(2));
    }

    private Map<String,String> requested(JsonNode row,String code) {
        return Map.of("combinationKey",row.path("combinationKey").asText(),"sellerSku",code,"title","Generated item");
    }

    @Test
    void rollsBackEarlierRowsWhenALaterSkuHasAnExistingBusinessReference() throws Exception {
        Fixture f=fixture();
        String supplier=data(call(f.tenant(),post("/scm/api/in/v1/suppliers").content("{\"name\":\"Supplier\",\"countryCode\":\"CN\"}"))).path("supplier").path("supplierId").asText();
        call(f.tenant(),post("/scm/api/in/v1/purchase-orders").content("""
                {"supplierId":"%s","purchaseType":"MARKET","currency":"USD","lines":[{"lineId":"L1","sellerSku":"ZZZ-REFERENCED","quantity":1,"unitCost":10}]}
                """.formatted(supplier))).andExpect(status().isOk());
        JsonNode preview=preview(f);
        commit(f,preview,List.of(requested(preview.path("rows").get(0),"AAA-FIRST"),requested(preview.path("rows").get(1),"ZZZ-REFERENCED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_IDENTITY_LOCKED"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(0));
        data(commit(f,preview,List.of(requested(preview.path("rows").get(0),"AAA-FIRST"),requested(preview.path("rows").get(1),"ZZZ-FIXED"))));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void detectsACombinationCreatedAfterPreviewAndRequiresANewPreview() throws Exception {
        Fixture f=fixture(); JsonNode preview=preview(f); JsonNode occupied=preview.path("rows").get(1);
        call(f.tenant(),post(PDM+"/skus").content(json.writeValueAsString(Map.of("spuId",f.spu(),"sellerSku","OTHER-WORKER","title","Created after preview",
                "baseUnit","件","specifications",occupied.path("specifications"))))).andExpect(status().isOk());
        commit(f,preview,List.of(requested(preview.path("rows").get(0),"other-worker")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_DUPLICATED"));
        commit(f,preview,List.of(requested(preview.path("rows").get(0),"UNCOMMITTED"),requested(occupied,"CONFLICT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_COMBINATION_EXISTS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OTHER-WORKER")));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(1));
        org.junit.jupiter.api.Assertions.assertEquals(1,java.util.stream.StreamSupport.stream(preview(f).path("rows").spliterator(),false).filter(r -> !r.path("existing").isNull()).count());
    }

    @Test
    void rechecksRangeAndRejectsInvalidOrRepeatedCodesWithoutPartialCreation() throws Exception {
        Fixture f=fixture(); JsonNode preview=preview(f);
        commit(f,preview,List.of(requested(preview.path("rows").get(0)," DUP "),requested(preview.path("rows").get(1),"dup")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_DUPLICATED"));
        commit(f,preview,List.of(requested(preview.path("rows").get(0),"BAD/CODE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_CODE_INVALID"));
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.red())),Map.of("attributeId",f.size(),"valueIds",List.of(f.small(),f.large())))))))
                .andExpect(status().isOk());
        commit(f,preview,List.of(requested(preview.path("rows").get(0),"STALE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_PREVIEW_STALE"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void concurrentRetryReturnsOneReceiptAndChangedReplayCannotAddMoreSkus() throws Exception {
        Fixture f=fixture(); JsonNode preview=preview(f);
        List<Map<String,String>> rows=List.of(requested(preview.path("rows").get(0),"ONCE-A"),requested(preview.path("rows").get(1),"ONCE-B"));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(() -> {start.await();return data(commit(f,preview,rows));});
            var b=pool.submit(() -> {start.await();return data(commit(f,preview,rows));});start.countDown();
            org.junit.jupiter.api.Assertions.assertEquals(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        commit(f,preview,List.of(requested(preview.path("rows").get(2),"CHANGED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_REPLAY_CHANGED"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void differentPreviewsCompetingForOneCombinationCannotBothCreateIt() throws Exception {
        Fixture f=fixture();JsonNode first=preview(f),second=preview(f);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(() -> {start.await();return commit(f,first,List.of(requested(first.path("rows").get(0),"FIRST"))).andReturn().getResponse().getStatus();});
            var b=pool.submit(() -> {start.await();return commit(f,second,List.of(requested(second.path("rows").get(0),"SECOND"))).andReturn().getResponse().getStatus();});start.countDown();
            var results=new java.util.ArrayList<>(List.of(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS)));
            results.sort(Integer::compareTo);org.junit.jupiter.api.Assertions.assertEquals(List.of(200,400),results);
        } finally {pool.shutdownNow();}
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void previewsChosenValuesAndRejectsForeignValuesTamperedRowsAndUnauthorizedRequests() throws Exception {
        Fixture f=fixture(),foreign=fixture();
        JsonNode subset=data(call(f.tenant(),post(generation(f)+"/preview").content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",List.of(f.red(),f.blue())),Map.of("attributeId",f.size(),"valueIds",List.of(f.small()))))))));
        org.junit.jupiter.api.Assertions.assertEquals(2,subset.path("rows").size());
        call(f.tenant(),post(generation(f)+"/preview").content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",foreign.color(),"valueIds",List.of(foreign.red())),Map.of("attributeId",f.size(),"valueIds",List.of(f.small())))))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SPEC_VALUE_NOT_ALLOWED"));
        commit(f,subset,List.of(Map.of("combinationKey","forged","sellerSku","FORGED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_SELECTION_INVALID"));
        String body=json.writeValueAsString(Map.of("previewId",subset.path("previewId").asText(),"rows",List.of(requested(subset.path("rows").get(0),"NO-ACCESS"))));
        mvc.perform(post(generation(f)+"/commit").header("X-Tenant-Id",f.tenant()).header("Authorization","Bearer "+jwt.createToken(f.tenant(),"other-writer","other",Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_PREVIEW_INVALID"));
        mvc.perform(post(generation(f)+"/preview").header("X-Tenant-Id",f.tenant()).header("Authorization","Bearer "+jwt.createToken(f.tenant(),"reader","reader",Set.of()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post(generation(f)+"/commit").header("X-Tenant-Id",foreign.tenant()).header("Authorization","Bearer "+jwt.createToken(f.tenant(),"writer","writer",Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(0));
    }

    private JsonNode preview(Fixture f) throws Exception { return data(call(f.tenant(),post(generation(f)+"/preview").content("{}"))); }
    private ResultActions commit(Fixture f,JsonNode preview,List<Map<String,String>> rows) throws Exception {
        return call(f.tenant(),post(generation(f)+"/commit").content(json.writeValueAsString(Map.of("previewId",preview.path("previewId").asText(),"rows",rows))));
    }

    @Test
    void expiresUnconfirmedPreviewsButKeepsAConfirmedReceiptReplayable() throws Exception {
        Fixture f=fixture();JsonNode confirmed=preview(f),unconfirmed=preview(f);
        List<Map<String,String>> rows=List.of(requested(confirmed.path("rows").get(0),"CONFIRMED"));
        JsonNode result=data(commit(f,confirmed,rows));
        jdbc.update("UPDATE pdm_sku_generation_preview SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE tenant_id=?",f.tenant());
        org.junit.jupiter.api.Assertions.assertEquals(result,data(commit(f,confirmed,rows)));
        commit(f,unconfirmed,List.of(requested(unconfirmed.path("rows").get(1),"EXPIRED")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GENERATION_PREVIEW_EXPIRED"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void refusesAnOversizedCartesianProductWithoutCreatingBusinessData() throws Exception {
        Fixture f=fixture();var colors=new java.util.ArrayList<>(List.of(f.red(),f.blue()));var sizes=new java.util.ArrayList<>(List.of(f.small(),f.large()));
        for(int i=0;i<13;i++) { colors.add(value(f.tenant(),f.color(),"C"+i,"颜色"+i));sizes.add(value(f.tenant(),f.size(),"Z"+i,"尺寸"+i)); }
        call(f.tenant(),put(PDM+"/products/{id}/specification-options",f.spu()).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",f.color(),"valueIds",colors),Map.of("attributeId",f.size(),"valueIds",sizes)))))).andExpect(status().isOk());
        call(f.tenant(),post(generation(f)+"/preview").content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GENERATION_TOO_LARGE"));
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void previewsFourCombinationsWithoutCreatingSkusAndShowsAnInactiveOccupant() throws Exception {
        Fixture f=fixture();
        String old=data(call(f.tenant(),post(PDM+"/skus").content(json.writeValueAsString(Map.of(
                "spuId",f.spu(),"sellerSku","OLD-RED-S","title","Existing","baseUnit","件","specifications",List.of(choice(f.color(),f.red()),choice(f.size(),f.small()))))))).path("skuId").asText();
        call(f.tenant(),post(PDM+"/skus/{id}/enable",old)).andExpect(status().isOk());
        call(f.tenant(),post(PDM+"/skus/{id}/disable",old)).andExpect(status().isOk());
        JsonNode preview=data(call(f.tenant(),post(generation(f)+"/preview").content("{}")));
        org.junit.jupiter.api.Assertions.assertEquals(4,preview.path("rows").size());
        JsonNode existing=java.util.stream.StreamSupport.stream(preview.path("rows").spliterator(),false)
                .filter(row -> !row.path("existing").isNull() && !row.path("existing").isMissingNode()).findFirst().orElseThrow().path("existing");
        org.junit.jupiter.api.Assertions.assertEquals("OLD-RED-S",existing.path("sellerSku").asText());
        org.junit.jupiter.api.Assertions.assertEquals("INACTIVE",existing.path("status").asText());
        call(f.tenant(),get(PDM+"/skus")).andExpect(jsonPath("$.data.length()").value(1));
    }

    private Fixture fixture() throws Exception {
        String tenant="generation-"+UUID.randomUUID();
        String category=data(call(tenant,post(PDM+"/categories").content("{\"name\":\"分类\"}"))).path("categoryId").asText();
        String spu=data(call(tenant,post(PDM+"/products").content(json.writeValueAsString(Map.of("sku","SPU","title","杯子","categoryId",category))))).path("spuId").asText();
        String color=attribute(tenant,"COLOR","颜色"),size=attribute(tenant,"SIZE","尺寸");
        String red=value(tenant,color,"RED","红色"),blue=value(tenant,color,"BLUE","蓝色"),small=value(tenant,size,"S","小号"),large=value(tenant,size,"L","大号");
        call(tenant,put(PDM+"/products/{id}/specification-options",spu).content(json.writeValueAsString(Map.of("dimensions",List.of(
                Map.of("attributeId",color,"valueIds",List.of(red,blue)),Map.of("attributeId",size,"valueIds",List.of(small,large))))))).andExpect(status().isOk());
        return new Fixture(tenant,spu,color,red,blue,size,small,large);
    }
    private String attribute(String tenant,String code,String name) throws Exception {
        return data(call(tenant,post(PDM+"/specification-attributes").content(json.writeValueAsString(Map.of("code",code,"name",name))))).path("attributeId").asText();
    }
    private String value(String tenant,String attr,String code,String name) throws Exception {
        return data(call(tenant,post(PDM+"/specification-attributes/{id}/values",attr).content(json.writeValueAsString(Map.of("code",code,"name",name))))).path("valueId").asText();
    }
    private Map<String,String> choice(String attr,String value) { return Map.of("attributeId",attr,"valueId",value); }
    private String generation(Fixture f) { return PDM+"/products/"+f.spu()+"/sku-generation"; }
    private ResultActions call(String tenant,MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id",tenant).header("Authorization","Bearer "+jwt.createToken(tenant,"writer","writer",Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions response) throws Exception {
        return json.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
    private record Fixture(String tenant,String spu,String color,String red,String blue,String size,String small,String large) {}
}
