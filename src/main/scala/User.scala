package example.users

import cats.effect.*
import cats.syntax.all.*

import _root_.skunk.*
import _root_.skunk.codec.all.*
import _root_.skunk.implicits.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.given

// --- Refined domain types -------------------------------------------------

type UserId = UserId.T
object UserId extends RefinedType[Long, Positive] {
  val codec: Codec[UserId] = int8.imap(applyUnsafe)(_.value)
}

type Email = Email.T
object Email extends RefinedType[String, Match["""^[^@\s]+@[^@\s]+\.[^@\s]+$"""]] {
  val codec: Codec[Email] = varchar.imap(applyUnsafe)(_.value)
}

type Age = Age.T
object Age extends RefinedType[Int, Interval.Closed[0, 150]] {
  val codec: Codec[Age] = int4.imap(applyUnsafe)(_.value)
}

type PageSize = Int :| Interval.Closed[1, 100]

final case class User(id: UserId, email: Email, age: Age)
object User {
  val codec: Codec[User] = (UserId.codec *: Email.codec *: Age.codec).to[User]
}

// --- Repository -----------------------------------------------------------

final class UserRepository(pool: Resource[IO, Session[IO]]) {

  def find(id: UserId): IO[Option[User]] =
    pool.use { s =>
      s.prepare(Queries.find).flatMap(_.option(id))
    }

  def page(limit: PageSize, after: UserId): IO[List[User]] =
    pool.use { s =>
      s.prepare(Queries.page).flatMap(_.stream((after, limit: Int), 64).compile.toList)
    }

  def insert(email: Email, age: Age): IO[UserId] =
    pool.use { s =>
      s.prepare(Queries.insert).flatMap(_.unique((email, age)))
    }

}

private object Queries {

  val find: Query[UserId, User] =
    sql"SELECT id, email, age FROM users WHERE id = ${UserId.codec}".query(User.codec)

  val page: Query[(UserId, Int), User] =
    sql"""
      SELECT id, email, age FROM users
      WHERE id > ${UserId.codec}
      ORDER BY id
      LIMIT $int4
    """.query(User.codec)

  val insert: Query[(Email, Age), UserId] =
    sql"INSERT INTO users (email, age) VALUES (${Email.codec}, ${Age.codec}) RETURNING id"
      .query(UserId.codec)

}
