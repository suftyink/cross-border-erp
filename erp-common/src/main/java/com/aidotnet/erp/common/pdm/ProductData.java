package com.aidotnet.erp.common.pdm;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** 可复制到后续单据的商品资料；尺寸为 cm，重量为 kg，金额使用指定币种。 */
public record ProductData(
        @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal weightKg,
        @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=16,fraction=2) BigDecimal declaredValue,
        @Size(max=8) String currency,
        @Size(max=4000) String description,
        @Size(max=10) List<@NotNull @Valid Barcode> barcodes,
        @Valid Dimensions productDimensionsCm,
        @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal packageWeightKg,
        @Valid Dimensions packageDimensionsCm,
        @Positive @JsonDeserialize(using=StrictIntegerDeserializer.class) Integer unitsPerCarton,
        @Valid Dimensions cartonDimensionsCm,
        @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal cartonWeightKg,
        @Valid Declaration declaration,
        @Size(max=2000) String remarks,
        @Size(max=10) List<@NotNull @Valid ImageReference> images) {

    public ProductData {
        barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
        images = images == null ? List.of() : List.copyOf(images);
    }
    public static ProductData empty() {
        return new ProductData(null,null,null,null,List.of(),null,null,null,null,null,null,null,null,List.of());
    }
    public ProductData withCore(BigDecimal weight,BigDecimal value,String code) {
        return new ProductData(weight,value,code,description,barcodes,productDimensionsCm,packageWeightKg,packageDimensionsCm,
                unitsPerCarton,cartonDimensionsCm,cartonWeightKg,declaration,remarks,images);
    }
    public BigDecimal shippingWeightKg() {
        if (packageWeightKg != null && packageWeightKg.signum() > 0) return packageWeightKg;
        return weightKg != null && weightKg.signum() > 0 ? weightKg : null;
    }
    public record Barcode(@NotBlank @Size(max=16) String type,@NotBlank @Size(max=128) String value) {}
    public record Dimensions(@JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal lengthCm,
                             @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal widthCm,
                             @JsonFormat(shape=JsonFormat.Shape.STRING) @PositiveOrZero @Digits(integer=6,fraction=4) BigDecimal heightCm) {}
    public record Declaration(@Size(max=255) String nameChinese,@Size(max=255) String nameEnglish,@Size(max=20) String hsCode,
                              @Size(max=8) String countryOfOrigin,@Size(max=255) String material,@Size(max=255) String usage) {}
    public record ImageReference(@Size(max=64) String assetId,@Size(max=2048) String url) {}
}
