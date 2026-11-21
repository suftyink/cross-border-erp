package com.aidotnet.erp.pdm.application;

import com.aidotnet.erp.common.exception.BizException;
import com.aidotnet.erp.common.storage.MinioStorageService;
import com.aidotnet.erp.pdm.infrastructure.ImageAssetStore;
import com.aidotnet.erp.pdm.infrastructure.ImageAssetStore.Asset;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductImageService {
    public static final long MAX_BYTES=5L*1024*1024;
    private final MinioStorageService storage;
    private final ImageAssetStore assets;
    public ProductImageService(MinioStorageService storage,ImageAssetStore assets) { this.storage=storage;this.assets=assets; }

    public UploadedImage upload(String tenant,String actor,MultipartFile file) throws Exception {
        if(file.isEmpty() || file.getSize()>MAX_BYTES) throw new BizException("IMAGE_SIZE_INVALID","图片必须大于 0 且不超过 5 MB");
        String mime=file.getContentType()==null?"":file.getContentType().toLowerCase(Locale.ROOT);
        if(!mime.equals("image/png") && !mime.equals("image/jpeg")) throw new BizException("IMAGE_TYPE_INVALID","仅支持 PNG 或 JPEG 图片");
        if(tenant.contains("/") || tenant.contains("\\")) throw new BizException("TENANT_INVALID","租户标识不能用于文件命名空间");
        byte[] bytes=file.getBytes();
        int[] size=inspect(bytes,mime);
        String id=UUID.randomUUID().toString(),key=tenant+"/pdm-images/"+id+(mime.equals("image/png")?".png":".jpg");
        // 对象名不可复用。元数据保存失败时不返回可引用的资产 ID，孤立对象不参与商品资料。
        storage.upload(key,new ByteArrayInputStream(bytes),bytes.length,mime);
        Asset asset=new Asset(id,key,mime,bytes.length,size[0],size[1]);
        assets.save(tenant,actor,asset);
        return new UploadedImage(id,mime,bytes.length,size[0],size[1]);
    }

    private int[] inspect(byte[] bytes,String mime) {
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IOException("Unsupported image");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true);
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!(format.equals("png") && mime.equals("image/png")) && !(format.equals("jpeg") && mime.equals("image/jpeg"))) throw new IOException("MIME mismatch");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>4096 || height>4096 || (long)width*height>16_000_000) throw new BizException("IMAGE_DIMENSIONS_INVALID","图片边长不超过 4096 像素，总像素不超过 1600 万");
                if(reader.read(0)==null) throw new IOException("Image decode failed");
                return new int[]{width,height};
            } finally {reader.dispose();}
        } catch(BizException error) {throw error;}
        catch(IOException | RuntimeException error) {throw new BizException("IMAGE_CONTENT_INVALID","图片内容无效或与类型不一致");}
    }
    public Asset asset(String tenant,String id) {
        return assets.find(tenant,id).orElseThrow(() -> new BizException("IMAGE_NOT_FOUND","图片不存在或不属于当前租户"));
    }
    public InputStream read(Asset asset) throws Exception {return storage.download(asset.objectKey());}
    public ResolvedImage resolve(String tenant,String id,int seconds) throws Exception {
        if(seconds<1 || seconds>900) throw new BizException("IMAGE_URL_EXPIRY_INVALID","图片地址有效期应为 1 至 900 秒");
        Asset asset=asset(tenant,id);
        return new ResolvedImage(id,storage.getPresignedUrl(asset.objectKey(),java.time.Duration.ofSeconds(seconds)),java.time.Instant.now().plusSeconds(seconds));
    }
    public record UploadedImage(String assetId,String contentType,long sizeBytes,int widthPx,int heightPx) {}
    public record ResolvedImage(String assetId,String url,java.time.Instant expiresAt) {}
}
