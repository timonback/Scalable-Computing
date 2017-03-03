package models

import play.api.data.Form
import play.api.data.Forms.{ mapping, number}

case class Rating(
                 id: Int,
                 user: Int,
                 article: Int,
                 rating: Int)

object Rating {

  import play.api.libs.json._

  implicit object RatingWrites extends OWrites[Rating] {
    def writes(rating: Rating): JsObject = Json.obj(
      "id" -> rating.id,
      "user" -> rating.user,
      "article" -> rating.article,
      "rating" -> rating.rating)
  }

  implicit object RatingReads extends Reads[Rating] {
    def reads(json: JsValue): JsResult[Rating] = json match {
      case obj: JsObject => try {
        val id = (obj \ "id").as[Int]
        val user = (obj \ "user").as[Int]
        val article = (obj \ "article").as[Int]
        val rating = (obj \ "rating").as[Int]

        JsSuccess(Rating(id, user, article, rating))

      } catch {
        case cause: Throwable => JsError(cause.getMessage)
      }

      case _ => JsError("expected.jsobject")
    }
  }

  val form = Form(
    mapping(
      "id" -> number,
      "user" -> number,
      "article" -> number,
      "rating" -> number) {
      (id, user, article, rating) =>
        Rating(
          id,
          user,
          article,
          rating)
    } { Rating =>
      Some(
        (Rating.id,
          Rating.user,
          Rating.article,
          Rating.rating))
    })
}