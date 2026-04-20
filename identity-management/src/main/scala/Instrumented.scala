package idmgmt

import scala.concurrent.duration.MILLISECONDS

import cats.effect.*
import cats.syntax.all.*
import cats.Monad

import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.metrics.Histogram
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.StatusCode
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute

object Instrumented {

  case class Metrics[F[_]](
    latency: Histogram[F, Double],
    errors: Counter[F, Long]
  )

  def makeMetrics[F[_]: Monad](prefix: String)(using m: Meter[F]): F[Metrics[F]] =
    for {
      lat <- m.histogram[Double](s"$prefix.latency")
               .withUnit("ms")
               .withDescription(s"Operation latency for $prefix")
               .create
      err <- m.counter[Long](s"$prefix.errors")
               .withUnit("{errors}")
               .withDescription(s"Error count for $prefix")
               .create
    } yield Metrics(lat, err)

  def trace[F[_]: Temporal: Tracer, A](
    spanName: String,
    metrics: Metrics[F],
    attrs: Attribute[?]*
  )(fa: F[A]): F[A] =
    Tracer[F]
      .span(spanName, attrs*)
      .surround {
        for {
          start <- Clock[F].monotonic
          result <- fa.onError { case err: Throwable =>
                      for {
                        end    <- Clock[F].monotonic
                        elapsed = (end - start).toUnit(MILLISECONDS)
                        opAttr  = Attribute("operation", spanName)
                        _      <- metrics.latency.record(elapsed, opAttr)
                        _      <- metrics.errors.add(1L, opAttr)
                        _ <- Tracer[F]
                               .currentSpanOrNoop
                               .flatMap { span =>
                                 span.addAttribute(Attribute("error", true)) *>
                                   span.setStatus(StatusCode.Error, err.getMessage)
                               }
                      } yield ()
                    }
          end    <- Clock[F].monotonic
          elapsed = (end - start).toUnit(MILLISECONDS)
          opAttr  = Attribute("operation", spanName)
          _      <- metrics.latency.record(elapsed, opAttr)
        } yield result
      }

}
