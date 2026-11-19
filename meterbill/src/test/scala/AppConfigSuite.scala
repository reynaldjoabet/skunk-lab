package meterbill.config

class AppConfigSuite extends munit.FunSuite {

  test("defaults apply when only the password is set") {
    val cfg = AppConfig.from(Map("DB_PASSWORD" -> "secret")).toOption.get
    assertEquals(cfg.db.host, "localhost")
    assertEquals(cfg.db.port, 5432)
    assertEquals(cfg.db.poolSize, 10)
    assertEquals(cfg.db.ssl, false)
    assertEquals(cfg.http.port.value, 8080)
  }

  test("a missing password is an error") {
    assertEquals(
      AppConfig.from(Map.empty).left.map(_.problems),
      Left(List("DB_PASSWORD must be set"))
    )
  }

  test("every invalid value is reported at once") {
    val env = Map("DB_PORT" -> "abc", "DB_POOL_SIZE" -> "0", "HTTP_PORT" -> "70000")
    assertEquals(AppConfig.from(env).left.map(_.problems.size), Left(4))
  }

  test("the password is not printed with the config") {
    val cfg = AppConfig.from(Map("DB_PASSWORD" -> "hunter2secret")).toOption.get
    assert(!cfg.toString.contains("hunter2secret"))
  }

}
