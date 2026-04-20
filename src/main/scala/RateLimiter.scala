import cats.effect.std.Semaphore
import cats.effect.Concurrent
import cats.syntax.all._

trait RateLimiter[F[_]] {
  def throttle[A](fa: F[A]): F[A]
}

object RateLimiter {

  // Simple semaphore-based concurrency limiter
  def make[F[_]: Concurrent](maxConcurrent: Int): F[RateLimiter[F]] =
    Semaphore[F](maxConcurrent.toLong).map { sem =>
      new RateLimiter[F] {
        def throttle[A](fa: F[A]): F[A] = sem.permit.use(_ => fa)
      }
    }

}
