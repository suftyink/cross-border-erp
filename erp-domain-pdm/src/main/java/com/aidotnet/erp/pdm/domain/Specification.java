package com.aidotnet.erp.pdm.domain;

import java.util.List;

/** ID 和编码表达稳定含义；名称和说明仅作显示。 */
public final class Specification {
    private Specification() {}
    public static String key(List<Selection> selections) {
        if (selections.isEmpty()) return null;
        return selections.stream().sorted(java.util.Comparator.comparing(Selection::attributeId).thenComparing(Selection::valueId))
                .map(item -> item.attributeId() + ":" + item.valueId()).collect(java.util.stream.Collectors.joining("|"));
    }
    public record Selection(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String attributeId, @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=64) String valueId) {}
    public record Value(String valueId, String attributeId, String code, String name, String description) {}
    public record Attribute(String attributeId, String code, String name, String description, List<Value> values) {}
    public record Dimension(String attributeId, String attributeCode, String attributeName, List<Value> values) {}
    public record Options(String spuId, List<Dimension> dimensions) {}
    public record Range(String attributeId, List<String> valueIds) {}
    public record Item(String attributeId, String attributeCode, String attributeName, String valueId, String valueCode, String valueName) {}
}
