/**
 * ⭐ 生成物与手写类型的关系（不写清一定会漂移）：
 *
 *  - `api`（来自 generated.ts）是【线上真实形状】的唯一真值，
 *    由 api/openapi.yaml 自动生成，⛔ 不要手改。
 *  - 本文件里的 Envelope / PageResult / TaskSummary 等是【便利别名】，
 *    供前端日常引用（生成物的路径下标写起来很长）。
 *
 * ⚠️ 改后端接口的正确顺序：
 *    改 openapi.yaml → pnpm gen:api → 再看便利别名是否要跟着调。
 * ⛔ 反过来会造成"前端类型对、实际接口不对"，要到运行时才炸。
 */
export * as api from './generated';

/**
 * 四端共用的接口契约类型。
 *
 * ⭐ 与后端 `api/openapi.yaml` 对齐。改后端接口时必须同步改这里 ——
 * ⛔ 不要在 web-user / web-admin / apps/mobile 里各写一份。
 *
 * ⚠️ 尚未从 OpenAPI 自动生成（见 README）。在那之前这里就是唯一真值。
 */

/** 后端统一响应信封。 */
export interface Envelope<T> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
  timestamp?: string;
  /**
   * ⚠️ 必须看它，不能只看 HTTP 状态码：
   * 业务失败也可能带 200（例如非法游标返回 200 + success=false）。
   */
  success: boolean;
}

/** 游标分页结果。⛔ 刻意没有 total —— 精确总数要 COUNT(*)，大厅不划算。 */
export interface PageResult<T> {
  list: T[];
  /** 为 null 表示已到末页。前端据此驱动无限滚动。 */
  cursor: string | null;
  hasMore: boolean;
}

/** 任务大厅列表项。 */
export interface TaskSummary {
  taskId: number;
  taskNo: string;
  title: string;
  categoryCode: string;
  categoryName: string;
  taskForm: string;
  payMode: string;
  status: string;
  /** ⭐ 状态中文名。四端据此展示，⛔ 不硬编码状态字符串。 */
  statusName: string;
  /** ⭐ 状态分组，前端据此配色。 */
  statusGroup: string;
  price: number | null;
  budgetMin: number | null;
  budgetMax: number | null;
  deadline: string | null;
  publishedAt: string | null;
  publisherNickname: string | null;
}

/** 分类节点。 */
export interface CategoryNode {
  id: number;
  parentId: number;
  level: number;
  code: string;
  name: string;
  icon: string | null;
  /** 允许的任务形态：ONLINE / OFFLINE / HYBRID。 */
  allowForms: string[];
  /** 允许的付款方式：FIXED / BID / BY_QUANTITY / BY_DAY / MILESTONE。 */
  allowPayModes: string[];
  children?: CategoryNode[];
}

/** 业务错误码（与后端 ErrorCode 的段位约定一致）。 */
export const ErrorCode = {
  PARAM_INVALID: 10001,
  UNAUTHORIZED: 20001,
  NOT_FOUND: 20007,
  TASK_NOT_FOUND: 30001,
  TASK_STATUS_INVALID: 30002,
  NOT_TASK_OWNER: 30003,
  GRAB_BUSY: 31001,
  GRAB_TAKEN: 31002,
  BID_CLOSED: 31003,
  AMOUNT_MISMATCH: 40007,
} as const;
