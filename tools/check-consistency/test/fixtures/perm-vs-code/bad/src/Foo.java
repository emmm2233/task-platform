class Foo {
  @PreAuthorize("hasAuthority('account:user:delete')")
  public void a() {}
}