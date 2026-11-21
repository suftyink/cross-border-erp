'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Image, Input, Space, Typography, Upload, message } from 'antd';
import apiClient from '@/lib/api/client';
import { productDataApi, productImagePath, type ProductImageReference } from '@/lib/pdm-product-data';
import { specificationError } from '@/lib/pdm-specification';

export function ProductImage({ image, size = 64 }: { image: ProductImageReference; size?: number }) {
  const [src, setSrc] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    setSrc(undefined); setFailed(false);
    if (image.url) { setSrc(image.url); return; }
    if (!image.assetId) return;
    const controller = new AbortController(); let objectUrl: string | undefined;
    void apiClient.get<Blob>(`${productImagePath}/${encodeURIComponent(image.assetId)}/content`, { responseType: 'blob', signal: controller.signal })
      .then(response => { if (!controller.signal.aborted) { objectUrl = URL.createObjectURL(response.data); setSrc(objectUrl); } })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [image.assetId, image.url, retry]);
  if (failed) return <Button size="small" style={{ width: size, minHeight: size }} onClick={() => setRetry(v => v + 1)}>重试图片</Button>;
  return src ? <Image src={src} width={size} height={size} style={{ objectFit: 'contain' }} alt="商品图片" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
    : <Typography.Text type="secondary" style={{ display: 'inline-flex', width: size, height: size, alignItems: 'center' }}>加载图片…</Typography.Text>;
}

export function ProductImagesEditor({ value = [], onChange, disabled, onUploadingChange }: {
  value?: ProductImageReference[]; onChange?: (value: ProductImageReference[]) => void; disabled: boolean; onUploadingChange?: (value: boolean) => void;
}) {
  const [uploading, setUploading] = useState(false);
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string>();
  const addUrl = () => {
    try {
      const parsed = new URL(url.trim());
      if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) throw new Error();
      if (value.some(image => image.url === url.trim())) { setError('图片 URL 已添加'); return; }
      onChange?.([...value, { url: url.trim() }]); setUrl(''); setError(undefined);
    } catch { setError('请输入有效的 HTTP 或 HTTPS 图片 URL'); }
  };
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Typography.Text type="secondary">最多 10 张。上传支持 PNG／JPEG，最大 5 MB、4096 像素边长及 1600 万像素；外部 URL 由浏览器读取。</Typography.Text>
    {error && <Alert type="error" showIcon message={error} />}
    <Space wrap>
      <Upload accept="image/png,image/jpeg" showUploadList={false} disabled={disabled || uploading || value.length >= 10}
        beforeUpload={file => {
          if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 5 * 1024 * 1024 || !file.size) {
            setError('请选择不超过 5 MB 的 PNG 或 JPEG 图片'); return Upload.LIST_IGNORE;
          }
          return true;
        }} customRequest={async request => {
          setUploading(true); onUploadingChange?.(true); setError(undefined);
          try { const result = await productDataApi.upload(request.file as File); onChange?.([...value, { assetId: result.assetId }]); request.onSuccess?.(result); }
          catch (failure) { setError(specificationError(failure)); request.onError?.(new Error('上传失败')); }
          finally { setUploading(false); onUploadingChange?.(false); }
        }}><Button loading={uploading}>上传图片</Button></Upload>
      <Input aria-label="外部图片 URL" placeholder="https://…" maxLength={2048} value={url} style={{ width: 360 }} disabled={disabled || uploading || value.length >= 10}
        onChange={event => setUrl(event.target.value)} onPressEnter={event => { event.preventDefault(); addUrl(); }} />
      <Button disabled={disabled || uploading || !url.trim() || value.length >= 10} onClick={addUrl}>添加 URL</Button>
    </Space>
    <Space wrap align="start">{value.map((image, index) => <Space key={image.assetId ?? image.url} direction="vertical" align="center">
      <ProductImage image={image} size={88} />
      <Typography.Text type="secondary">{image.assetId ? '已上传图片' : '外部 URL'}</Typography.Text>
      <Button danger size="small" disabled={disabled || uploading} onClick={() => { onChange?.(value.filter((_, i) => i !== index)); message.info('图片已从当前资料移除，保存后生效'); }}>移除图片 {index + 1}</Button>
    </Space>)}</Space>
  </Space>;
}
