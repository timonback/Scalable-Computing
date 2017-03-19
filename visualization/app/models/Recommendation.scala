package models

import play.api.data.Form
import play.api.data.Forms._

case class Recommendation(
                    userid: Int,
                    recommendations: List[Int])

object Recommendation {
  import play.api.libs.json._

  implicit object RecommendationWrites extends OWrites[Recommendation] {
    def writes(recommendation: Recommendation): JsObject = Json.obj(
      "userid" -> recommendation.userid,
      "recommendations" -> recommendation.recommendations)
  }

  implicit object RecommendationReads extends Reads[Recommendation] {
    def reads(json: JsValue): JsResult[Recommendation] = json match {
      case obj: JsObject => try {
        val userid = (obj \ "userid").as[Int]
        val recommendations = (obj \ "recommendations").as[List[Int]]

        JsSuccess(Recommendation(userid, recommendations))

      } catch {
        case cause: Throwable => JsError(cause.getMessage)
      }

      case _ => JsError("expected.jsobject")
    }
  }

  val form = Form(
    mapping(
      "userid" -> number,
      "recommendations" -> list(number)) {
      (userid, recommendations) =>
        Recommendation(
          userid,
          recommendations)
    } { recommendation =>
      Some(
        (recommendation.userid,
          recommendation.recommendations))
    })
}
