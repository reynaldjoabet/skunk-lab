import org.typelevel.otel4s.Attributes as OtelAttributes
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.model.ServerResponse
import sttp.tapir.AnyEndpoint

object Observability {

  object Attributes {

    def fromTapirRequest(request: ServerRequest): OtelAttributes =
      OtelAttributes.empty

    def fromTapirEndpoint(endpoint: AnyEndpoint): OtelAttributes =
      OtelAttributes.empty

    def fromTapirResponse[B](response: ServerResponse[B]): OtelAttributes =
      OtelAttributes.empty

    def fromError(error: Throwable): OtelAttributes =
      OtelAttributes.empty

  }

}
