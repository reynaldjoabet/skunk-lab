package meterbill.domain

/**
  * Request validation. The limits mirror the column definitions and CHECK constraints in
  * V1__init.sql, so bad input is reported to the client as a 422 naming every problem instead of
  * surfacing as a database error.
  */
object Validation {

  final case class Invalid(problems: List[String]) extends Exception(problems.mkString("; "))

  private val Slug = "^[a-z0-9][a-z0-9-]*[a-z0-9]$".r
  // Deliberately loose: one '@' with something on both sides and no whitespace.
  private val Email = "^[^@\\s]+@[^@\\s]+$".r

  private def check(ok: Boolean, problem: => String): List[String] =
    if (ok) Nil else List(problem)

  private def result[A](value: A, problems: List[String]): Either[Invalid, A] =
    if (problems.isEmpty) Right(value) else Left(Invalid(problems))

  def tenant(req: CreateTenantReq): Either[Invalid, CreateTenantReq] = {
    val clean = req.copy(slug = req.slug.trim, name = req.name.trim, email = req.email.trim)
    result(
      clean,
      check(
        clean.slug.length <= 63 && Slug.matches(clean.slug),
        "slug must be 2-63 characters of a-z, 0-9 or '-', and must not start or end with '-'"
      ) ++
        check(clean.name.nonEmpty && clean.name.length <= 255, "name must be 1-255 characters") ++
        check(
          clean.email.length <= 255 && Email.matches(clean.email),
          "email must be a valid address of at most 255 characters"
        )
    )
  }

  def usage(req: RecordUsageReq): Either[Invalid, RecordUsageReq] = {
    val clean = req.copy(idempotencyKey = req.idempotencyKey.trim, metric = req.metric.trim)
    result(
      clean,
      check(
        clean.idempotencyKey.nonEmpty && clean.idempotencyKey.length <= 255,
        "idempotencyKey must be 1-255 characters"
      ) ++
        check(
          clean.metric.nonEmpty && clean.metric.length <= 50,
          "metric must be 1-50 characters"
        ) ++
        check(clean.quantity > 0, "quantity must be greater than 0")
    )
  }

}
