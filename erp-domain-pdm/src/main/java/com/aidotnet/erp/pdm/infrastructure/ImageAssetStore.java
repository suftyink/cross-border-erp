package com.aidotnet.erp.pdm.infrastructure;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ImageAssetStore {
    private final JdbcTemplate jdbc;
    public ImageAssetStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void save(String tenant,String actor,Asset asset) {
        jdbc.update("""
                INSERT INTO pdm_image_asset(tenant_id,asset_id,object_key,content_type,size_bytes,width_px,height_px,created_by)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,asset.assetId(),asset.objectKey(),asset.contentType(),asset.sizeBytes(),asset.widthPx(),asset.heightPx(),actor);
    }
    public Optional<Asset> find(String tenant,String id) {
        return jdbc.query("SELECT * FROM pdm_image_asset WHERE tenant_id=? AND asset_id=?",(r,i) -> new Asset(r.getString("asset_id"),r.getString("object_key"),
                r.getString("content_type"),r.getLong("size_bytes"),r.getInt("width_px"),r.getInt("height_px")),tenant,id).stream().findFirst();
    }
    public record Asset(String assetId,String objectKey,String contentType,long sizeBytes,int widthPx,int heightPx) {}
}
