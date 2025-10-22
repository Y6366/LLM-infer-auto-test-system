// types.ts
export interface ApiResponse<T = any> {
  version?: string;            // "1.0"
  timestamp?: string;          // ISO-8601
  requestId?: string;          // 链路ID
  status: Status;              // 机器码/HTTP/人类可读消息
  data?: T;                    // 成功时的载荷
  errors?: ErrorDetail[];      // 失败时的错误项
  details?: Record<string, any>; // 诊断/上下文（traceId、service、hint...）
  meta?: Meta;                 // 元信息（如分页）
  links?: Links;               // HATEOAS 链接
}

export interface Status {
  code: string;   // "OK" 或业务错误码（ORD_xxx）
  http: number;   // HTTP 状态码镜像
  message: string;// 人类可读消息
}

export interface ErrorDetail {
  code: string;         // VALIDATION_FAILED / SKU_NOT_FOUND ...
  field?: string;       // items[0].quantity
  message: string;      // 错误描述
  expected?: string;    // 期望
  actual?: string;      // 实际（注意脱敏）
}

export interface Meta {
  pagination?: Pagination;
  extra?: Record<string, any>;
}

export interface Pagination {
  page?: number;        // 1 基
  size?: number;
  total?: number;
  hasNext?: boolean;
  cursor?: string;      // 游标分页可选
}

export interface Links {
  self?: string;
  next?: string;
  prev?: string;
  related?: Record<string, string>;
}

/** 统一前端错误对象，便于 UI/埋点/重试策略 */
export interface ApiError extends Error {
  code: string;                     // 与 status.code 对齐或前端自定义（NETWORK_ERROR 等）
  http?: number;                    // HTTP 状态（可空：如网络断开）
  requestId?: string;
  traceId?: string;
  details?: Record<string, any>;
  errors?: ErrorDetail[];           // 字段级错误
  raw?: unknown;                    // 原始响应/异常对象
}

/** 类型守卫：成功判定 */
export function isApiSuccess<T>(r: ApiResponse<T>): r is ApiResponse<T> & { data: T } {
  return !!r && r.status?.code === 'OK' && r.status?.http >= 200 && r.status?.http < 300;
}

/** 类型守卫：错误判定 */
export function isApiErrorResp<T>(r: ApiResponse<T>): boolean {
  return !!r && r.status?.code !== 'OK';
}
