package com.aidotnet.erp.common.pdm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 仅执行来源域声明的静态查询，每个来源/商品取一条引用示例，避免按 SKU 逐条查询。 */
public abstract class SqlSkuUsageProbe implements SkuUsageProbe {
    private final NamedParameterJdbcTemplate jdbc;
    private final String domain;
    private final List<Source> sources;

    protected SqlSkuUsageProbe(JdbcTemplate jdbc, String domain, Source... sources) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.domain = domain;
        this.sources = List.of(sources);
    }

    @Override
    public List<Usage> find(String tenantId, Collection<Identity> identities) {
        Map<String, String> codes = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        for (Identity identity : identities) {
            if (identity.sellerSku() != null) codes.put(identity.sellerSku().strip().toUpperCase(Locale.ROOT), identity.skuId());
            if (identity.skuId() != null) ids.put(identity.skuId(), identity.skuId());
        }
        List<Usage> found = new ArrayList<>();
        for (Source source : sources) {
            Map<String, String> targets = source.byId() ? ids : codes;
            if (targets.isEmpty()) continue;
            String sql = "SELECT DISTINCT ON (product_key) product_key, reference_id FROM (" + source.sql()
                    + ") source_rows ORDER BY product_key, reference_id";
            jdbc.query(sql, Map.of("tenantId", tenantId, "keys", targets.keySet()), row -> {
                String skuId = targets.get(row.getString("product_key"));
                if (skuId != null) found.add(new Usage(skuId, domain, source.kind(), row.getString("reference_id")));
            });
        }
        return List.copyOf(found);
    }

    public record Source(String kind, boolean byId, String sql) {}
}
