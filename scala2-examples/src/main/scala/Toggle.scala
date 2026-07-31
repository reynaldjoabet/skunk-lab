import java.util.{Date, UUID}

case class Toggle(
    id: Option[UUID] = Option(UUID.randomUUID()),
    service: String,
    name: String,
    value: String,
    timestamp: Date = new Date()
)
