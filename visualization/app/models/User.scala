package models

import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms.{longNumber, mapping, nonEmptyText, number, optional, text}

case class User(
                 id: Int,
                 username: String,
                 password: String,
                 email: Option[String],
                 creationDate: Option[DateTime],
                 updateDate: Option[DateTime])

object User {
  import play.api.libs.json._

  implicit object UserWrites extends OWrites[User] {
    def writes(user: User): JsObject = Json.obj(
      "id" -> user.id,
      "username" -> user.username,
      "password" -> user.password,
      "email" -> user.email,
      "creationDate" -> user.creationDate.fold(-1L)(_.getMillis),
      "updateDate" -> user.updateDate.fold(-1L)(_.getMillis))
  }

  implicit object UserReads extends Reads[User] {
    def reads(json: JsValue): JsResult[User] = json match {
      case obj: JsObject => try {
        val id = (obj \ "id").asOpt[Int].getOrElse(-1)
        val username = (obj \ "username").as[String]
        val password = (obj \ "password").as[String]
        val email = (obj \ "email").asOpt[String]
        val creationDate = (obj \ "creationDate").asOpt[Long]
        val updateDate = (obj \ "updateDate").asOpt[Long]

        JsSuccess(User(id, username, password, email,
          creationDate.map(new DateTime(_)),
          updateDate.map(new DateTime(_))))

      } catch {
        case cause: Throwable => JsError(cause.getMessage)
      }

      case _ => JsError("expected.jsobject")
    }
  }

  val form = Form(
    mapping(
      "id" -> optional(number),
      "username" -> nonEmptyText,
      "password" -> nonEmptyText,
      "email" -> optional(text),
      "creationDate" -> optional(longNumber),
      "updateDate" -> optional(longNumber)) {
      (id, username, password, email, creationDate, updateDate) =>
        User(
          id.getOrElse(-1),
          username,
          password,
          email,
          creationDate.map(new DateTime(_)),
          updateDate.map(new DateTime(_)))
    } { user =>
      Some(
        (Option(user.id),
          user.username,
          user.password,
          user.email,
          user.creationDate.map(_.getMillis),
          user.updateDate.map(_.getMillis)))
    })
}
