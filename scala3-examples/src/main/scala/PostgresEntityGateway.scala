import java.util.UUID

//import cats.implicits._
import cats.effect.implicits._
import cats.effect.kernel._
import cats.effect.syntax.all._
import cats.syntax.all._

object PostgresEntityGateway {

  def dsl[F[_]: Async](
      resource: Resource[F, skunk.Session[F]]
  ): F[EntityGateway[F, UUID]] =
    Async[F].delay {
      new EntityGateway[F, UUID] {

        override def writeMany(
            todos: Vector[Todo[UUID]]
        ): F[Vector[Todo.Existing[UUID]]] =
          todos.traverse {
            case data: Todo.Data           => insertOne(data)
            case todo: Todo.Existing[UUID] => updateOne(todo)
          }

        private def insertOne(data: Todo.Data): F[Todo.Existing[UUID]] =
          resource.use { session =>
            session
              .prepare(Statement.Insert.one)
              .flatMap { preparedQuery =>
                preparedQuery.unique(data)
              }
          }

        private def updateOne(
            todo: Todo.Existing[UUID]
        ): F[Todo.Existing[UUID]] =
          resource.use { session =>
            session
              .prepare(Statement.Update.one)
              .flatMap { preparedQuery =>
                preparedQuery.unique(todo)
              }
          }

        override def readManyById(
            ids: Vector[UUID]
        ): F[Vector[Todo.Existing[UUID]]] =
          resource.use { session =>
            session
              .prepare(Statement.Select.many(ids.size))
              .flatMap { preparedQuery =>
                preparedQuery.stream(ids.to(List), ChunkSizeInBytes).compile.toVector
              }
          }

        override def readManyByPartialDescription(
            partialDescription: String
        ): F[Vector[Todo.Existing[UUID]]] =
          resource.use { session =>
            session
              .prepare(Statement.Select.byDescription)
              .flatMap { preparedQuery =>
                preparedQuery.stream(partialDescription, ChunkSizeInBytes).compile.toVector
              }
          }

        override val readAll: F[Vector[Todo.Existing[UUID]]] =
          resource.use { session =>
            session.execute(Statement.Select.all).map(_.to(Vector))
          }

        override def deleteMany(todos: Vector[Todo.Existing[UUID]]): F[Unit] =
          resource.use { session =>
            session
              .prepare(Statement.Delete.many(todos.size))
              .flatMap { preparedCommand =>
                preparedCommand.execute(todos.to(List).map(_.id)).void
              }
          }

        override val deleteAll: F[Unit] =
          resource.use { session =>
            session.execute(Statement.Delete.all).void
          }

      }
    }

  private val ChunkSizeInBytes: Int =
    1024

}
