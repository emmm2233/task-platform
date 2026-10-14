class Foo {
  // 文档说明里的不应被发现
  // @PreAuthorize("hasAuthority('ghost:comment:only')")

  @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('real:fqn:undeclared')")
  public void a() {}

  @PreAuthorize("hasAnyAuthority('account:user:view', 'short:form:undeclared')")
  public void b() {}
}