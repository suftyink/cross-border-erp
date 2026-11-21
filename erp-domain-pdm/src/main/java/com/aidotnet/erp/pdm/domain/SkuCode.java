package com.aidotnet.erp.pdm.domain;

import com.aidotnet.erp.common.exception.BizException;
import java.util.Locale;
import java.util.UUID;

/** 本地 SKU 编码规则，不用于规范化平台 MSKU。 */
public final class SkuCode {
    private SkuCode() {}

    public static String normalize(String value) {
        String code = value == null ? "" : value.strip();
        if (code.isEmpty() || code.length() > 128 || !code.matches("[A-Za-z0-9_-]+")) {
            throw new BizException("SKU_CODE_INVALID", "本地 SKU 编码限 128 位字母、数字、连字符或下划线");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    public static String generate() {
        return "SKU-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
