package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.pdm.domain.SkuCode;
import com.aidotnet.erp.pdm.domain.SkuGeneration.*;
import com.aidotnet.erp.pdm.domain.Specification;
import com.aidotnet.erp.pdm.domain.Specification.*;
import com.aidotnet.erp.pdm.domain.Spu;
import com.aidotnet.erp.pdm.infrastructure.ProductStore;
import com.aidotnet.erp.pdm.infrastructure.SkuGenerationStore;
import com.aidotnet.erp.pdm.infrastructure.SpecificationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuGenerationService {
    private static final int MAX_ROWS = 200;
    private final ProductStore products;
    private final SpecificationStore specifications;
    private final SkuGenerationStore generations;
    private final ObjectMapper json;
    private final SkuDraftService drafts;

    public SkuGenerationService(ProductStore products, SpecificationStore specifications,
                                SkuGenerationStore generations, ObjectMapper json, SkuDraftService drafts) {
        this.products = products;
        this.specifications = specifications;
        this.generations = generations;
        this.json = json;
        this.drafts = drafts;
    }

    @Transactional
    public Preview preview(String tenant, String actor, String spuId, List<Range> requested) {
        specifications.lock(tenant);
        Spu parent = products.lockSpu(tenant, spuId).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        Options options = specifications.options(tenant, spuId);
        List<Dimension> chosen = choose(options, requested);
        List<List<Item>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (Dimension dimension : chosen) {
            if (combinations.size() > MAX_ROWS / dimension.values().size()) {
                throw new BizException("GENERATION_TOO_LARGE", "单次最多预览 200 个组合，请缩小所选规格值范围");
            }
            List<List<Item>> next = new ArrayList<>();
            for (List<Item> previous : combinations) for (Value value : dimension.values()) {
                List<Item> items = new ArrayList<>(previous);
                items.add(new Item(dimension.attributeId(), dimension.attributeCode(), dimension.attributeName(), value.valueId(), value.code(), value.name()));
                next.add(List.copyOf(items));
            }
            combinations = next;
        }
        var existing = generations.existing(tenant, spuId);
        List<Row> rows = combinations.stream().map(items -> {
            String key = Specification.key(items.stream().map(i -> new Selection(i.attributeId(), i.valueId())).toList());
            Existing occupant = existing.get(key);
            String title = (parent.title() == null ? "" : parent.title() + " ")
                    + items.stream().map(Item::valueName).collect(java.util.stream.Collectors.joining(" / "));
            int end = Math.min(512, title.length());
            if (end < title.length() && Character.isHighSurrogate(title.charAt(end - 1))) end--;
            return new Row(key, items, occupant == null ? SkuCode.generate() : occupant.sellerSku(), title.substring(0, end), "件", occupant);
        }).toList();
        Preview result = new Preview(UUID.randomUUID().toString(), spuId, Instant.now().plus(30, ChronoUnit.MINUTES), rows);
        generations.save(tenant, spuId, actor, result.previewId(), fingerprint(parent, options), encode(result), result.expiresAt());
        return result;
    }

    @Transactional
    public GenerationResult commit(String tenant, String actor, String spuId, String previewId, List<RequestedRow> requested) {
        specifications.lock(tenant);
        Spu parent = products.lockSpu(tenant, spuId).orElseThrow(() -> new BizException("SPU_NOT_FOUND", "SPU不存在"));
        var stored = generations.lock(tenant, spuId, actor, previewId)
                .orElseThrow(() -> new BizException("GENERATION_PREVIEW_INVALID", "预览不存在或不属于当前操作人，请重新预览"));
        Preview preview = decode(stored.previewJson(), Preview.class);
        Map<String, Row> candidates = preview.rows().stream().collect(java.util.stream.Collectors.toMap(Row::combinationKey, row -> row));
        List<RequestedRow> rows = prepare(candidates, requested);
        String requestHash = hash(encode(rows));
        if (stored.resultJson() != null) {
            if (!Objects.equals(requestHash, stored.requestHash())) throw new BizException("GENERATION_REPLAY_CHANGED", "这次预览已经确认，请勿修改后重复提交；需要新增商品时请重新预览");
            return decode(stored.resultJson(), GenerationResult.class);
        }
        if (!stored.expiresAt().isAfter(Instant.now())) throw new BizException("GENERATION_PREVIEW_EXPIRED", "预览已过期，请重新预览");
        if (!stored.fingerprint().equals(fingerprint(parent, specifications.options(tenant, spuId)))) {
            throw new BizException("GENERATION_PREVIEW_STALE", "SPU 或规格范围已变化，请更新预览后再确认");
        }
        Map<String, Existing> occupied = generations.existing(tenant, spuId);
        var codes = new HashSet<String>();
        for (RequestedRow row : rows) {
            Existing conflict = occupied.get(row.combinationKey());
            if (conflict != null) throw new BizException("GENERATION_COMBINATION_EXISTS", "组合已被 SKU " + conflict.sellerSku() + " (" + conflict.status() + ") 占用，请更新预览");
            if (!codes.add(row.sellerSku())) throw new BizException("SKU_DUPLICATED", "选中行的本地编码重复：" + row.sellerSku());
            if (products.findSkuBySellerSku(tenant, row.sellerSku()).isPresent()) throw new BizException("SKU_DUPLICATED", "本地 SKU 编码已存在：" + row.sellerSku());
        }
        List<SkuDraftService.SkuDraftView> created = new ArrayList<>();
        // 与编码引用契约保持同一锁顺序，整次确认的创建和回执属于同一事务。
        for (RequestedRow row : rows.stream().sorted(Comparator.comparing(RequestedRow::sellerSku)).toList()) {
            List<Selection> selection = candidates.get(row.combinationKey()).specifications().stream()
                    .map(item -> new Selection(item.attributeId(), item.valueId())).toList();
            created.add(drafts.create(tenant, new SkuDraftService.CreateDraft(spuId, row.sellerSku(), row.title(), row.baseUnit(), null, selection)));
        }
        GenerationResult result = new GenerationResult(previewId, List.copyOf(created));
        generations.complete(tenant, previewId, requestHash, encode(result));
        return result;
    }

    private List<RequestedRow> prepare(Map<String, Row> candidates, List<RequestedRow> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > MAX_ROWS) throw new BizException("GENERATION_SELECTION_INVALID", "请选择 1 至 200 个新组合");
        var seen = new HashSet<String>();
        List<RequestedRow> rows = new ArrayList<>();
        for (RequestedRow request : requested) {
            Row candidate = request == null ? null : candidates.get(request.combinationKey());
            if (candidate == null || !seen.add(request.combinationKey())) throw new BizException("GENERATION_SELECTION_INVALID", "只能提交本次预览内的组合，且不能重复选择");
            if (candidate.existing() != null) throw new BizException("GENERATION_COMBINATION_EXISTS", "已有组合不能重复生成：" + candidate.existing().sellerSku());
            String code = SkuCode.normalize(request.sellerSku() == null || request.sellerSku().isBlank() ? candidate.sellerSku() : request.sellerSku());
            rows.add(new RequestedRow(request.combinationKey(), code, request.title() == null ? candidate.title() : clean(request.title()),
                    request.baseUnit() == null ? candidate.baseUnit() : clean(request.baseUnit())));
        }
        return rows.stream().sorted(Comparator.comparing(RequestedRow::combinationKey)).toList();
    }

    private List<Dimension> choose(Options options, List<Range> requested) {
        if (options.dimensions().isEmpty()) throw new BizException("GENERATION_NO_DIMENSIONS", "请先为 SPU 配置规格维度和允许的值");
        if (requested == null) return options.dimensions();
        if (requested.size() != options.dimensions().size()) throw new BizException("GENERATION_RANGE_INVALID", "请为 SPU 每个规格维度选择值");
        var seen = new HashSet<String>();
        List<Dimension> result = new ArrayList<>();
        for (Range range : requested) {
            if (!seen.add(range.attributeId()) || range.valueIds() == null || range.valueIds().isEmpty()
                    || new HashSet<>(range.valueIds()).size() != range.valueIds().size()) {
                throw new BizException("GENERATION_RANGE_INVALID", "规格维度和值不能为空或重复");
            }
            Dimension allowed = options.dimensions().stream().filter(d -> d.attributeId().equals(range.attributeId())).findFirst()
                    .orElseThrow(() -> new BizException("SPEC_VALUE_NOT_ALLOWED", "规格属性不在本 SPU 范围内"));
            List<Value> values = allowed.values().stream().filter(v -> range.valueIds().contains(v.valueId())).toList();
            if (values.size() != range.valueIds().size()) throw new BizException("SPEC_VALUE_NOT_ALLOWED", "规格值不在本 SPU 允许范围内");
            result.add(new Dimension(allowed.attributeId(), allowed.attributeCode(), allowed.attributeName(), values));
        }
        return result.stream().sorted(Comparator.comparing(Dimension::attributeCode)).toList();
    }

    private String fingerprint(Spu parent, Options options) {
        return hash(encode(new Snapshot(parent.sku(), parent.title(), parent.categoryId(), parent.brandId(), options.dimensions())));
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法记录规格生成预览", error); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法读取规格生成记录", error); }
    }
    private String clean(String value) { return value.isBlank() ? null : value.strip(); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private record Snapshot(String code, String title, String categoryId, String brandId, List<Dimension> dimensions) {}
    public record GenerationResult(String previewId, List<SkuDraftService.SkuDraftView> created) {}
}
