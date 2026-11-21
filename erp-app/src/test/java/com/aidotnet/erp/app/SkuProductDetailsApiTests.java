package com.aidotnet.erp.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aidotnet.erp.common.security.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.mock.web.MockMultipartFile;

@ErpAppTest
@AutoConfigureMockMvc
class SkuProductDetailsApiTests {
    private static final String PDM="/pdm/api/in/v1";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtProvider jwt;

    @Test
    void rejectsInvalidNumericAndImageReferencesWithoutChangingSavedDetails() throws Exception {
        String tenant="boundaries-"+UUID.randomUUID(),sku=create(tenant);
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{\"weightKg\":\"999999.9999\",\"declaredValue\":\"9999999999999999.99\",\"currency\":\"usd\",\"unitsPerCarton\":24}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.declaredValue").value("9999999999999999.99"));
        String[] invalid={"{\"weightKg\":-1}","{\"weightKg\":0.00001}","{\"weightKg\":1000000}",
                "{\"declaredValue\":-1}","{\"declaredValue\":0.001}","{\"unitsPerCarton\":1.5}","{\"unitsPerCarton\":0}",
                "{\"packageDimensionsCm\":{\"heightCm\":-1}}","{\"cartonWeightKg\":-1}",
                "{\"currency\":\"NOT-CURRENCY\"}","{\"images\":[{\"url\":\"javascript:alert(1)\"}]}",
                "{\"images\":[{\"url\":\"https://example.test/a?X-Amz-Signature=temporary\"}]}",
                "{\"images\":[{\"url\":\"https://example.test/a\"},{\"url\":\"https://example.test/a\"}]}",
                "{\"barcodes\":[{\"type\":\"UPC\",\"value\":\"123\"}]}"};
        for(String body:invalid) call(tenant,put(PDM+"/skus/{id}/details",sku).content(body)).andExpect(status().isBadRequest());
        call(tenant,get(PDM+"/skus/{id}/details",sku)).andExpect(jsonPath("$.data.weightKg").value("999999.9999"))
                .andExpect(jsonPath("$.data.declaredValue").value("9999999999999999.99")).andExpect(jsonPath("$.data.unitsPerCarton").value(24));
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{}" )).andExpect(status().isOk());
        call(tenant,get(PDM+"/skus/{id}/details",sku)).andExpect(jsonPath("$.data.weightKg").isEmpty()).andExpect(jsonPath("$.data.images").isEmpty());
        call(tenant,get(PDM+"/skus/{id}/history",sku)).andExpect(jsonPath("$.data[1].action").value("DETAILS"))
                .andExpect(jsonPath("$.data[1].after.declaredValue").value("9999999999999999.99"));
    }

    @Test
    void protectsProductWritesAndTenantHeadersAndAcceptsOnlyDecodedRasterImages() throws Exception {
        String tenant="permissions-"+UUID.randomUUID(),sku=create(tenant);
        String reader=jwt.createToken(tenant,"reader","reader",Set.of());
        mvc.perform(put(PDM+"/skus/{id}/details",sku).header("Authorization","Bearer "+reader).header("X-Tenant-Id",tenant)
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(multipart(PDM+"/images/upload").file(new MockMultipartFile("file","a.png","image/png",png()))
                .header("Authorization","Bearer "+reader).header("X-Tenant-Id",tenant)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(PDM+"/skus/{id}/details",sku).header("Authorization","Bearer "+reader).header("X-Tenant-Id","other"))
                .andExpect(status().isForbidden());
        upload(tenant,new MockMultipartFile("file","empty.png","image/png",new byte[0])).andExpect(status().isBadRequest());
        upload(tenant,new MockMultipartFile("file","wrong.jpg","image/jpeg",png())).andExpect(status().isBadRequest());
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",bytes);
        upload(tenant,new MockMultipartFile("file","good.jpg","image/jpeg",bytes.toByteArray())).andExpect(status().isOk());
        bytes.reset();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4097,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        upload(tenant,new MockMultipartFile("file","wide.png","image/png",bytes.toByteArray())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_DIMENSIONS_INVALID"));
    }

    @Test
    void calculatesFreightFromSavedProductWeightAndKeepsExistingShipmentMeasurements() throws Exception {
        String tenant="freight-"+UUID.randomUUID(),sku=create(tenant),tms="/tms/api/v1";
        String carrier=data(call(tenant,post(tms+"/carriers").content("{\"code\":\"TEST\",\"name\":\"Test\",\"status\":\"ACTIVE\",\"apiEnabled\":true,\"authorizationStatus\":\"AUTHORIZED\"}"))).path("carrierId").asText();
        String method=data(call(tenant,post(tms+"/carriers/{id}/methods",carrier).content("{\"methodCode\":\"AIR\",\"methodName\":\"Air\",\"enabled\":true,\"estimatedDaysMin\":5,\"estimatedDaysMax\":7}"))).path("methodId").asText();
        call(tenant,post(tms+"/carriers/{id}/methods/{mid}/channel-rules",carrier,method).content("{\"zoneCode\":\"US\",\"originCountry\":\"CN\",\"destinationCountry\":\"US\",\"weightMinKg\":0,\"weightMaxKg\":10,\"baseCost\":1,\"costPerKg\":10,\"currency\":\"USD\"}" )).andExpect(status().isOk());
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{\"weightKg\":0.1,\"packageWeightKg\":0.2}" )).andExpect(status().isOk());
        String estimate="{\"originCountry\":\"CN\",\"destinationCountry\":\"US\",\"zoneCode\":\"US\",\"items\":[{\"skuId\":\""+sku+"\",\"quantity\":3}]}";
        call(tenant,post(tms+"/shipping-rates/estimate").content(estimate)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chargeableWeight").value(0.6)).andExpect(jsonPath("$.data.estimatedFreight").value(7));
        String shipment=data(call(tenant,post(tms+"/shipments").content("{\"orderId\":\"order-history\",\"carrierId\":\""+carrier+"\",\"trackingNo\":\"HISTORY-1\",\"destinationCountry\":\"US\",\"weight\":0.6}"))).path("shipmentId").asText();
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{\"packageWeightKg\":0.4}" )).andExpect(status().isOk());
        call(tenant,get(tms+"/shipments/{id}",shipment)).andExpect(status().isOk()).andExpect(jsonPath("$.data.weight").value(0.6));
        call(tenant,post(tms+"/shipping-rates/estimate").content(estimate)).andExpect(status().isOk()).andExpect(jsonPath("$.data.chargeableWeight").value(1.2));
        call(tenant,post(tms+"/shipping-rates/estimate").content(estimate.replace("\"quantity\":3","\"quantity\":1.5"))).andExpect(status().isBadRequest());
        call(tenant,post(tms+"/shipping-rates/estimate").content(estimate.replace("{\"originCountry\"","{\"weight\":1,\"originCountry\""))).andExpect(status().isBadRequest());
        String token=jwt.createToken(tenant,"writer","writer",Set.of("pdm:product:write"));
        mvc.perform(post(tms+"/shipping-rates/estimate").header("Authorization","Bearer "+token).header("X-Tenant-Id","other")
                .contentType(MediaType.APPLICATION_JSON).content(estimate)).andExpect(status().isForbidden());
        call(tenant,get(PDM+"/skus/{id}/readiness",sku).param("usage","DECLARATION")).andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("英文申报品名")));
    }

    @Test
    void permitsBasicActivationButRejectsFreightUseWithTheSpecificMissingSkuField() throws Exception {
        String tenant="usage-"+UUID.randomUUID(),sku=create(tenant);
        call(tenant,post(PDM+"/skus/{id}/enable",sku)).andExpect(status().isOk());
        call(tenant,get(PDM+"/skus/{id}/readiness",sku).param("usage","FREIGHT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(false));
        call(tenant,post("/tms/api/v1/shipping-rates/estimate").content("{\"originCountry\":\"CN\",\"destinationCountry\":\"US\",\"items\":[{\"skuId\":\""+sku+"\",\"quantity\":3}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SKU_USAGE_INCOMPLETE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DETAIL-01")));
    }

    @Test
    void uploadsAStableImageReferenceAndReadsItAgainAfterReload() throws Exception {
        String tenant="image-"+UUID.randomUUID(),sku=create(tenant);
        byte[] image=png();
        String asset=data(upload(tenant,new MockMultipartFile("file","product.png","image/png",image))).path("assetId").asText();
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{\"images\":[{\"assetId\":\""+asset+"\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.images[0].assetId").value(asset))
                .andExpect(jsonPath("$.data.images[0].url").isEmpty());
        for(int i=0;i<2;i++) {
            call(tenant,get(PDM+"/images/{id}/content",asset)).andExpect(status().isOk())
                    .andExpect(content().contentType("image/png")).andExpect(content().bytes(image));
        }
        call(tenant,get(PDM+"/skus/{id}/details",sku)).andExpect(jsonPath("$.data.images[0].assetId").value(asset));
    }

    private byte[] png() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        return bytes.toByteArray();
    }
    private ResultActions upload(String tenant,MockMultipartFile file) throws Exception {
        return mvc.perform(multipart(PDM+"/images/upload").file(file).header("X-Tenant-Id",tenant)
                .header("Authorization","Bearer "+jwt.createToken(tenant,"writer","writer",Set.of("pdm:product:write"))));
    }

    @Test
    void validatesImageContentSizeAndTenantAndPreventsTheGenericFileApiBypass() throws Exception {
        String tenant="secure-image-"+UUID.randomUUID(),other="other-"+tenant;
        upload(tenant,new MockMultipartFile("file","bad.svg","image/svg+xml","<svg/>".getBytes())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_TYPE_INVALID"));
        upload(tenant,new MockMultipartFile("file","fake.png","image/png","not an image".getBytes())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_CONTENT_INVALID"));
        upload(tenant,new MockMultipartFile("file","large.png","image/png",new byte[5*1024*1024+1])).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_SIZE_INVALID"));
        String asset=data(upload(tenant,new MockMultipartFile("file","valid.png","image/png",png()))).path("assetId").asText();
        call(other,get(PDM+"/images/{id}/content",asset)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        String sku=create(other);
        call(other,put(PDM+"/skus/{id}/details",sku).content("{\"images\":[{\"assetId\":\""+asset+"\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        String url=data(call(tenant,get(PDM+"/images/{id}/url",asset))).path("url").asText();
        String path=java.net.URI.create(url).getPath(),object=path.substring(path.indexOf('/',1)+1);
        call(other,get("/platform/sys/api/in/v1/files/presigned-url/"+object)).andExpect(status().isForbidden());
        call(tenant,delete("/platform/sys/api/in/v1/files/"+object)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_MANAGED_OBJECT"));
        call(tenant,get("/platform/sys/api/in/v1/files/download/"+object)).andExpect(status().isOk()).andExpect(content().bytes(png()));
    }

    @Test
    void renewsExpiredSignedUrlsWithoutChangingTheSavedAssetReference() throws Exception {
        String tenant="renew-image-"+UUID.randomUUID(),sku=create(tenant);
        String asset=data(upload(tenant,new MockMultipartFile("file","product.png","image/png",png()))).path("assetId").asText();
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("{\"images\":[{\"assetId\":\""+asset+"\"}]}" )).andExpect(status().isOk());
        String shortUrl=data(call(tenant,get(PDM+"/images/{id}/url",asset).param("expirySeconds","2"))).path("url").asText();
        var client=java.net.http.HttpClient.newHttpClient();
        org.junit.jupiter.api.Assertions.assertEquals(200,client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(shortUrl)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        Thread.sleep(3100);
        org.junit.jupiter.api.Assertions.assertEquals(403,client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(shortUrl)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        String renewed=data(call(tenant,get(PDM+"/images/{id}/url",asset))).path("url").asText();
        org.junit.jupiter.api.Assertions.assertEquals(200,client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(renewed)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        call(tenant,get(PDM+"/skus/{id}/details",sku)).andExpect(jsonPath("$.data.images[0].assetId").value(asset)).andExpect(jsonPath("$.data.images[0].url").isEmpty());
    }

    @Test
    void maintainsOptionalDetailsOnAnInactiveSkuWithoutChangingItsIdentityOrState() throws Exception {
        String tenant="details-"+UUID.randomUUID(),sku=create(tenant);
        call(tenant,post(PDM+"/skus/{id}/enable",sku)).andExpect(status().isOk());
        call(tenant,post(PDM+"/skus/{id}/disable",sku)).andExpect(status().isOk());
        call(tenant,put(PDM+"/skus/{id}/details",sku).content("""
                {"weightKg":0.1256,"declaredValue":19.99,"currency":"USD","description":"产品说明",
                 "barcodes":[{"type":"EAN","value":"4006381333931"}],
                 "productDimensionsCm":{"lengthCm":12.3456,"widthCm":4.5,"heightCm":2},
                 "packageWeightKg":0.2,"packageDimensionsCm":{"lengthCm":14,"widthCm":6,"heightCm":3},
                 "unitsPerCarton":24,"cartonDimensionsCm":{"lengthCm":40,"widthCm":30,"heightCm":20},"cartonWeightKg":5.8,
                 "declaration":{"nameChinese":"杯子","nameEnglish":"Cup","hsCode":"691200","countryOfOrigin":"CN","material":"陶瓷","usage":"饮水"},
                 "remarks":"不要压箱","images":[{"url":"https://example.invalid/product.jpg"}]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.unitsPerCarton").value(24));
        call(tenant,get(PDM+"/skus/{id}/details",sku)).andExpect(jsonPath("$.data.weightKg").value("0.1256"))
                .andExpect(jsonPath("$.data.productDimensionsCm.lengthCm").value("12.3456"))
                .andExpect(jsonPath("$.data.barcodes[0].value").value("4006381333931"));
        call(tenant,get(PDM+"/skus/{id}",sku)).andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.sellerSku").value("DETAIL-01"))
                .andExpect(jsonPath("$.data.baseUnit").value("件")).andExpect(jsonPath("$.data.identityLocked").value(true))
                .andExpect(jsonPath("$.data.weightKg").value(0.1256));
    }

    private String create(String tenant) throws Exception {
        String category=data(call(tenant,post(PDM+"/categories").content("{\"name\":\"分类\"}"))).path("categoryId").asText();
        return data(call(tenant,post(PDM+"/skus").content("{\"sellerSku\":\"DETAIL-01\",\"title\":\"Cup\",\"baseUnit\":\"件\",\"newSpu\":{\"title\":\"Cups\",\"categoryId\":\""+category+"\"}}"))).path("skuId").asText();
    }
    private ResultActions call(String tenant,MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("X-Tenant-Id",tenant).header("Authorization","Bearer "+jwt.createToken(tenant,"writer","writer",Set.of("pdm:product:write")))
                .contentType(MediaType.APPLICATION_JSON));
    }
    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
}
