class Foo {
  @PreAuthorize("hasAuthority('account:user:view')")
  public void a() {}
}