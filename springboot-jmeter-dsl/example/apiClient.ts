// apiClient.ts
import axios, { AxiosError, AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import { ApiResponse, ApiError, isApiSuccess } from './types';

export type TokenProvider = () => string | null | undefined;

export interface ApiClientOptions {
  baseURL: string;
  getToken?: TokenProvider;                 // 从本地/Pinia/Redux 取 JWT
  onError?: (err: ApiError) => void;        // 全局错误回调（可弹 Toast）
  injectRequestId?: boolean;                // 默认 true：附带 X-Request-Id
  defaultHeaders?: Record<string, string>;  // 额外头
}

declare module 'axios' {
  // 在 config 上支持传入幂等键等扩展字段
  interface AxiosRequestConfig {
    idempotencyKey?: string;                // 传则自动加 Idempotency-Key
    requestId?: string;                     // 自定义 requestId；否则自动生成
    skipAuth?: boolean;                     // 跳过认证头
  }
}

/** 简单 UUID（不引第三方库） */
function uuid4() {
  // 浏览器原生
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  // 兜底实现
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function createApiClient(opts: ApiClientOptions): AxiosInstance {
  const {
    baseURL,
    getToken,
    onError,
    injectRequestId = true,
    defaultHeaders = {},
  } = opts;

  const instance = axios.create({
    baseURL,
    timeout: 10000,
    headers: {
      'Content-Type': 'application/json',
      ...defaultHeaders,
    },
  });

  // ----- 请求拦截：加 Authorization / X-Request-Id / Idempotency-Key -----
  instance.interceptors.request.use((config) => {
    // JWT
    if (!config.skipAuth && getToken) {
      const token = getToken();
      if (token) {
        config.headers = config.headers ?? {};
        (config.headers as any).Authorization = `Bearer ${token}`;
      }
    }

    // Request-Id（便于后端回传到 ApiResponse.requestId）
    if (injectRequestId) {
      const rid = config.requestId ?? uuid4();
      config.requestId = rid;
      config.headers = config.headers ?? {};
      (config.headers as any)['X-Request-Id'] = rid;
    }

    // 幂等键（POST/PUT/PATCH/DELETE 按需设置）
    if (config.idempotencyKey) {
      config.headers = config.headers ?? {};
      (config.headers as any)['Idempotency-Key'] = config.idempotencyKey;
    }

    return config;
  });

  // ----- 响应拦截：统一解包 / 归一化错误 -----
  instance.interceptors.response.use(
    (resp: AxiosResponse<ApiResponse<any>>) => {
      const api = resp.data;

      // 非标准返回（后端未包 ApiResponse），直接返回原始数据
      if (!api || !api.status) return resp;

      if (isApiSuccess(api)) {
        // 成功：直接把 data 挂回到响应对象上，方便调用处 .data.data
        return {
          ...resp,
          data: api, // 保留完整 envelope；也可返回 api.data 看团队偏好
        };
      }

      // 业务错误 → 走 reject，交给统一错误处理
      const err: ApiError = {
        name: 'ApiError',
        message: api.status?.message || 'Unknown API error',
        code: api.status?.code || 'API_ERROR',
        http: api.status?.http,
        requestId: api.requestId,
        traceId: api.details?.traceId,
        errors: api.errors,
        details: api.details,
        raw: api,
      };
      onError?.(err);
      return Promise.reject(err);
    },
    (e: AxiosError) => {
      // 网络/超时/非 JSON
      const err: ApiError = {
        name: 'NetworkError',
        message: e.message || 'Network Error',
        code: e.code || 'NETWORK_ERROR',
        http: e.response?.status,
        requestId: (e.config as any)?.requestId,
        raw: e,
      };
      // 若后端返回了 ApiResponse，但被解析为错误分支
      try {
        const api = e.response?.data as any;
        if (api?.status) {
          err.code = api.status.code || err.code;
          err.http = api.status.http || err.http;
          err.message = api.status.message || err.message;
          err.requestId = api.requestId || err.requestId;
          err.details = api.details || err.details;
          err.errors = api.errors || err.errors;
        }
      } catch { /* ignore */ }

      onError?.(err);
      return Promise.reject(err);
    }
  );

  return instance;
}
