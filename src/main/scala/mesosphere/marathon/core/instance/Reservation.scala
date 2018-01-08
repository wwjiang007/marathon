package mesosphere.marathon
package core.instance

import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.state.Timestamp
import play.api.libs.json._

/**
  * Represents a reservation for all resources that are needed for launching an instance
  * and associated persistent local volumes.
  */
case class Reservation(volumeIds: Seq[LocalVolumeId], state: Reservation.State)

object Reservation {
  /**
    * A timeout that eventually leads to a state transition
    *
    * @param initiated When this timeout was setup
    * @param deadline When this timeout should become effective
    * @param reason The reason why this timeout was set up
    */
  case class Timeout(initiated: Timestamp, deadline: Timestamp, reason: Timeout.Reason)

  object Timeout {
    sealed trait Reason
    object Reason {
      /** A timeout because the instance could not be relaunched */
      case object RelaunchEscalationTimeout extends Reason
      /** A timeout because we got no ack for reserved resources or persistent volumes */
      case object ReservationTimeout extends Reason
    }

    implicit object ReasonFormat extends Format[Timeout.Reason] {
      override def reads(json: JsValue): JsResult[Timeout.Reason] = {
        json.validate[String].map {
          case "RelaunchEscalationTimeout" => Reason.RelaunchEscalationTimeout
          case "ReservationTimeout" => Reason.ReservationTimeout
        }
      }

      override def writes(o: Timeout.Reason): JsValue = {
        JsString(o.toString)
      }
    }

    implicit val timeoutFormat: Format[Timeout] = Json.format[Timeout]
  }

  sealed trait State extends Product with Serializable {
    /** Defines when this state should time out and for which reason */
    def timeout: Option[Timeout]
  }

  object State {
    /** A newly reserved resident instance */
    case class New(timeout: Option[Timeout]) extends State
    /** A launched resident instance, never has a timeout */
    case object Launched extends State {
      override def timeout: Option[Timeout] = None
    }
    /** A resident instance that has been running before but terminated and can be relaunched */
    case class Suspended(timeout: Option[Timeout]) extends State
    /** A resident instance whose reservation and persistent volumes are being destroyed */
    case class Garbage(timeout: Option[Timeout]) extends State
    /** An unknown resident instance created because of unknown reservations/persistent volumes */
    case class Unknown(timeout: Option[Timeout]) extends State

    implicit object StateFormat extends Format[State] {
      override def reads(json: JsValue): JsResult[State] = {
        implicit val timeoutFormat: Format[Timeout] = Timeout.timeoutFormat
        (json \ "timeout").validateOpt[Timeout].flatMap { timeout =>
          (json \ "name").validate[String].map {
            case "new" => New(timeout)
            case "launched" => Launched
            case "suspended" => Suspended(timeout)
            case "garbage" => Garbage(timeout)
            case _ => Unknown(timeout)
          }
        }
      }

      override def writes(o: State): JsValue = {
        val timeout = Json.toJson(o.timeout)
        o match {
          case _: New => JsObject(Seq("name" -> JsString("new"), "timeout" -> timeout))
          case Launched => JsObject(Seq("name" -> JsString("launched"), "timeout" -> timeout))
          case _: Suspended => JsObject(Seq("name" -> JsString("suspended"), "timeout" -> timeout))
          case _: Garbage => JsObject(Seq("name" -> JsString("garbage"), "timeout" -> timeout))
          case _: Unknown => JsObject(Seq("name" -> JsString("unknown"), "timeout" -> timeout))
        }
      }
    }
  }

  implicit val reservationFormat: OFormat[Reservation] = Json.format[Reservation]
}
