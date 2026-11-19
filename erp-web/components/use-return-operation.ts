'use client';

import { useEffect, useRef, useState } from 'react';
import { isAxiosError } from 'axios';
import { post } from '@/lib/api/client';
import { useAuthStore } from '@/lib/store';

export interface ReturnAttempt { path: string; body: unknown; label: string; permission: string }

/** 结果未知时保留原路径和请求编号，刷新后仍可重试同一笔作业。 */
export function useReturnOperation<T>(scope: string, onSaved: (result: T) => Promise<unknown>) {
  const tenant = useAuthStore((s) => s.tenantId);
  const key = `erp:pending-return:${scope}:${tenant}`;
  const [pending, setPending] = useState<ReturnAttempt>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const busy = useRef(false);
  useEffect(() => {
    try { const stored = sessionStorage.getItem(key); setPending(stored ? JSON.parse(stored) as ReturnAttempt : undefined); }
    catch { setError('原操作记录无法读取，请联系管理员核对。'); }
  }, [key]);
  const send = async (attempt: ReturnAttempt) => {
    if (busy.current) return;
    busy.current = true; setSaving(true); setError(undefined);
    try {
      sessionStorage.setItem(key, JSON.stringify(attempt)); setPending(attempt);
      const result = await post<T>(attempt.path, attempt.body);
      sessionStorage.removeItem(key); setPending(undefined);
      await onSaved(result);
    } catch (failure) {
      const response = isAxiosError(failure) ? failure.response : undefined;
      if (response && response.status >= 400 && response.status < 500 && ![401, 403, 408, 429].includes(response.status)) {
        sessionStorage.removeItem(key); setPending(undefined);
      }
      setError(response?.data?.message ?? '操作结果未确认，请重试原操作。');
    } finally { busy.current = false; setSaving(false); }
  };
  return { pending, saving, error, setError, send, disabled: saving || !!pending };
}
