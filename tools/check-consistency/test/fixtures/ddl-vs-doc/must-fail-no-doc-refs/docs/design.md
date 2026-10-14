# 设计（反向验证夹具 2/2）

这里的引用全都【不满足】门禁的抽取条件，因此 refs 为空：

    escrow.released_amount      ← 在代码块里（docs.mjs 明确跳过 fenced block）

ESCROW.RELEASED_AMOUNT          ← 大写，不被匹配

escrow released_amount          ← 没有反引号，也不被匹配

期望：门禁报 `ddl-vs-doc/nothing-checked`，而不是静默绿灯。
