package com.aidotnet.erp.pdm.api;

import com.aidotnet.erp.common.api.Result;
import com.aidotnet.erp.common.security.RequirePermission;
import com.aidotnet.erp.pdm.application.SpecificationService;
import com.aidotnet.erp.pdm.domain.Specification.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pdm/api/in/v1")
public class SpecificationController {
    private final SpecificationService service;
    public SpecificationController(SpecificationService service) { this.service=service; }
    @GetMapping("/specification-attributes")
    public Result<List<Attribute>> list() { return Result.ok(service.list(PdmTenant.current())); }
    @PostMapping("/specification-attributes") @RequirePermission("pdm:product:write")
    public Result<Attribute> create(@Valid @RequestBody DictionaryRequest request) { return Result.ok(service.createAttribute(PdmTenant.current(),request.command())); }
    @PutMapping("/specification-attributes/{attr}") @RequirePermission("pdm:product:write")
    public Result<Attribute> update(@PathVariable String attr,@Valid @RequestBody DictionaryRequest request) { return Result.ok(service.updateAttribute(PdmTenant.current(),attr,request.command())); }
    @DeleteMapping("/specification-attributes/{attr}") @RequirePermission("pdm:product:write")
    public Result<Void> delete(@PathVariable String attr) { service.deleteAttribute(PdmTenant.current(),attr);return Result.ok(null); }
    @PostMapping("/specification-attributes/{attr}/values") @RequirePermission("pdm:product:write")
    public Result<Value> createValue(@PathVariable String attr,@Valid @RequestBody DictionaryRequest request) { return Result.ok(service.createValue(PdmTenant.current(),attr,request.command())); }
    @PutMapping("/specification-attributes/{attr}/values/{value}") @RequirePermission("pdm:product:write")
    public Result<Value> updateValue(@PathVariable String attr,@PathVariable String value,@Valid @RequestBody DictionaryRequest request) { return Result.ok(service.updateValue(PdmTenant.current(),attr,value,request.command())); }
    @DeleteMapping("/specification-attributes/{attr}/values/{value}") @RequirePermission("pdm:product:write")
    public Result<Void> deleteValue(@PathVariable String attr,@PathVariable String value) { service.deleteValue(PdmTenant.current(),attr,value);return Result.ok(null); }
    @GetMapping("/products/{spu}/specification-options")
    public Result<Options> options(@PathVariable String spu) { return Result.ok(service.options(PdmTenant.current(),spu)); }
    @PutMapping("/products/{spu}/specification-options") @RequirePermission("pdm:product:write")
    public Result<Options> configure(@PathVariable String spu,@Valid @RequestBody RangeRequest request) {
        return Result.ok(service.configure(PdmTenant.current(),spu,request.dimensions().stream().map(d -> new Range(d.attributeId(),d.valueIds())).toList()));
    }
    public record DictionaryRequest(@Size(max=64) String code,@NotBlank @Size(max=128) String name,@Size(max=512) String description,@Size(max=64) String attributeId) {
        SpecificationService.Dictionary command() { return new SpecificationService.Dictionary(code,name,description,attributeId); }
    }
    public record RangeRequest(@NotNull @Size(max=20) List<@NotNull @Valid DimensionRequest> dimensions) {}
    public record DimensionRequest(@NotBlank @Size(max=64) String attributeId,@NotEmpty @Size(max=200) List<@NotBlank @Size(max=64) String> valueIds) {}
}
