/**
 * ⭐ 本文件里的类型是【唯一真值】—— 四端共用同一份手写契约。
 *
 * ⚠️ 曾经这里还有一条 OpenAPI 自动生成链（generated.ts + gen:api + check:api）。
 *    已删除，原因是【它并没有真的接线】，而留着比没有更糟：
 *      · generated.ts 在三个 app 里没有任何一处被 import ⇒ 不进 bundle；
 *      · 反证实测：把它改名后构建产物【逐字节不变】；
 *      · 三端实际编译的是本文件里的手写副本；
 *      · 而 client 的注释却写着"由 openapi.yaml 自动生成" —— 注释与实现相反。
 *    ⇒ 一个"看着像有契约层"的东西会让人以为漂移已被解决，
 *      而真实响应与 openapi.yaml 早就不一致了（实测：spec 写 price 是 string，
 *      真实返回的是 Decimal 数字；spec 还声明了 live 根本不返回的字段）。
 *
 * ⛔ 所以现在的规矩很简单：
 *    · 改后端接口 → 【同步改本文件】
 *    · api/openapi.yaml 仍然是接口文档，但它【不再驱动类型】，
 *      不要指望改它能影响前端。
 */
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
