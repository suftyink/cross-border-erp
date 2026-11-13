import apiClient, { post, get } from './client';
import type { User, AuthToken, Result } from '@/types';

export async function login(username: string, password: string, tenantId: string) {
  const response = await apiClient.post<Result<AuthToken>>('/iam/api/in/v1/auth/login', { username, password }, {
    headers: { 'X-Tenant-Id': tenantId },
  });
  return response.data.data;
}

export async function getCurrentUser() {
  return get<User>('/iam/api/in/v1/auth/me');
}

export async function logout() {
  return post<void>('/iam/api/in/v1/auth/logout');
}

export async function verifyToken() {
  return post<AuthToken>('/iam/api/in/v1/auth/verify');
}
