package meterbill.domain

class ValidationSuite extends munit.FunSuite {

  test("a valid tenant is accepted and trimmed") {
    val result = Validation.tenant(CreateTenantReq(" acme-corp ", " Acme ", " billing@acme.com "))
    assertEquals(result, Right(CreateTenantReq("acme-corp", "Acme", "billing@acme.com")))
  }

  test("every tenant problem is reported at once") {
    val result = Validation.tenant(CreateTenantReq("-Bad-", "", "not-an-email"))
    assertEquals(result.left.map(_.problems.size), Left(3))
  }

  test("slugs are limited to 63 characters and need at least 2") {
    def slug(s: String) = Validation.tenant(CreateTenantReq(s, "Acme", "a@b.c")).isRight
    assert(slug("a" * 63))
    assert(!slug("a" * 64))
    assert(!slug("a"))
  }

  test("valid usage is accepted") {
    assert(Validation.usage(RecordUsageReq("evt-1", "api_calls", 1)).isRight)
  }

  test("usage needs a positive quantity, a metric and a key within the column limits") {
    assert(Validation.usage(RecordUsageReq("evt-1", "api_calls", 0)).isLeft)
    assert(Validation.usage(RecordUsageReq("evt-1", "api_calls", -5)).isLeft)
    assert(Validation.usage(RecordUsageReq("evt-1", " ", 1)).isLeft)
    assert(Validation.usage(RecordUsageReq("evt-1", "m" * 51, 1)).isLeft)
    assert(Validation.usage(RecordUsageReq("k" * 256, "api_calls", 1)).isLeft)
  }

}
