package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.pdm.ProductData;
import com.aidotnet.erp.common.pdm.ProductData.*;
import com.aidotnet.erp.pdm.domain.ProductStatus;
import com.aidotnet.erp.pdm.domain.Sku;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuDetailsStore;
import com.aidotnet.erp.pdm.infrastructure.SkuHistoryStore;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuDetailsService {
    private final ProductStore products;
    private final SkuDetailsStore details;
    private final SkuIdentityPolicy identities;
    private final SkuSpecificationService specifications;
    private final SkuHistoryStore history;
    public SkuDetailsService(ProductStore products,SkuDetailsStore details,SkuIdentityPolicy identities,SkuSpecificationService specifications,SkuHistoryStore history) {
        this.products=products;this.details=details;this.identities=identities;this.specifications=specifications;this.history=history;
    }
    public ProductData get(String tenant,String id) {
        return current(products.findSku(tenant,id).orElseThrow(() -> new BizException("SKU_NOT_FOUND","SKU不存在")));
    }
    public ProductData current(Sku sku) { return details.find(sku); }

    @Transactional
    public ProductData replace(String tenant,String id,ProductData request) {
        specifications.lock(tenant);
        Sku observed=products.findSku(tenant,id).orElseThrow(() -> new BizException("SKU_NOT_FOUND","SKU不存在"));
        products.lockSpu(tenant,observed.spuId()).orElseThrow(() -> new BizException("SPU_NOT_FOUND","SPU不存在"));
        Sku sku=identities.lock(tenant,id,null);
        if (sku.status()!=ProductStatus.DRAFT && sku.status()!=ProductStatus.ACTIVE && sku.status()!=ProductStatus.INACTIVE) throw new BizException("SKU_NOT_EDITABLE","历史状态需核实后才能维护资料");
        ProductData before=current(sku),after=normalize(tenant,request);
        Sku updated=new Sku(sku.skuId(),tenant,sku.spuId(),sku.sellerSku(),sku.title(),sku.baseUnit(),after.weightKg(),after.declaredValue(),after.currency(),
                sku.status(),sku.createdAt(),java.time.Instant.now(),sku.everEnabled(),sku.identityLocked());
        products.saveSku(updated);details.save(tenant,id,after);
        if (!before.equals(after)) history.appendDetails(tenant,id,before,after);
        return after;
    }
    private ProductData normalize(String tenant,ProductData data) {
        var barcodes=new HashSet<String>();
        List<Barcode> cleaned=data.barcodes().stream().map(b -> {
            String type=upper(b.type()),value=clean(b.value());
            if (type==null || !List.of("UPC","EAN","GTIN","OTHER").contains(type)) throw new BizException("BARCODE_TYPE_INVALID","外部条码类型无效");
            if (value==null || !barcodes.add(type+":"+value)) throw new BizException("BARCODE_INVALID","外部条码不能为空或重复");
            if ((type.equals("UPC") && !value.matches("[0-9]{12}")) || (type.equals("EAN") && !value.matches("([0-9]{8}|[0-9]{13})"))
                    || (type.equals("GTIN") && !value.matches("([0-9]{8}|[0-9]{12,14})"))) throw new BizException("BARCODE_INVALID","UPC/EAN/GTIN 条码长度或字符无效");
            return new Barcode(type,value);
        }).toList();
        var imageKeys=new HashSet<String>();
        List<ImageReference> images=data.images().stream().map(image -> {
            String asset=clean(image.assetId()),url=clean(image.url());
            if ((asset==null)==(url==null)) throw new BizException("IMAGE_REFERENCE_INVALID","每张图片请选择上传对象或外部 URL");
            if (asset!=null && !details.ownsImage(tenant,asset)) throw new BizException("IMAGE_NOT_FOUND","图片不存在或不属于当前租户");
            if (url!=null) validateUrl(url);
            if (!imageKeys.add(asset==null?url:"asset:"+asset)) throw new BizException("IMAGE_DUPLICATED","图片不能重复添加");
            return new ImageReference(asset,url);
        }).toList();
        String currency=upper(data.currency());
        if (currency!=null && !currency.matches("[A-Z]{3}")) throw new BizException("CURRENCY_INVALID","币种请填写三位字母代码");
        Declaration d=data.declaration();
        if (d!=null) {
            String country=upper(d.countryOfOrigin());
            if (country!=null && !country.matches("[A-Z]{2}")) throw new BizException("ORIGIN_COUNTRY_INVALID","原产国请填写两位字母代码");
            d=new Declaration(clean(d.nameChinese()),clean(d.nameEnglish()),clean(d.hsCode()),country,clean(d.material()),clean(d.usage()));
        }
        return new ProductData(data.weightKg(),data.declaredValue(),currency,clean(data.description()),cleaned,data.productDimensionsCm(),data.packageWeightKg(),
                data.packageDimensionsCm(),data.unitsPerCarton(),data.cartonDimensionsCm(),data.cartonWeightKg(),d,clean(data.remarks()),images);
    }
    private void validateUrl(String value) {
        try {
            URI url=URI.create(value);
            if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())) || url.getHost()==null || url.getUserInfo()!=null) throw new IllegalArgumentException();
            String query=url.getRawQuery();
            if (query!=null && query.toLowerCase(Locale.ROOT).matches(".*(x-amz-signature|x-amz-credential|x-amz-expires)=.*")) throw new BizException("IMAGE_URL_TEMPORARY","请保存上传图片引用或长期图片 URL，不能保存临时签名链接");
        } catch (IllegalArgumentException error) { throw new BizException("IMAGE_URL_INVALID","图片 URL 必须是有效的 HTTP 或 HTTPS 地址"); }
    }
    private String clean(String value) { return value==null || value.isBlank()?null:value.strip(); }
    private String upper(String value) { String clean=clean(value);return clean==null?null:clean.toUpperCase(Locale.ROOT); }
}
