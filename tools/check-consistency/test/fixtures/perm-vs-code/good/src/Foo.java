/**
 * 三层鉴权的说明：第 2 层用 @PreAuthorize + @EnableMethodSecurity。
 * 下面这行是文档示例，⛔ 不是真代码：
 *   @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('ghost:comment:only')")
 *   @PreAuthorize("hasAuthority('ghost:short:comment')")
 */
class Foo {
  // 行注释里的 @PreAuthorize("hasAuthority('ghost:line:comment')") 也不该被发现
  @PreAuthorize("hasAuthority('account:user:view')")
  public void a() {}
}