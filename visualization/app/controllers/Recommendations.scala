package controllers

import javax.inject.Inject

import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{Action, Controller}
import services.RecommendationService


class Recommendations @Inject()(val messagesApi: MessagesApi, recommendationService: RecommendationService)
  extends Controller {


  val index = Action.async { implicit request =>

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    recommendationService.all(request).map { recommendations =>
      Ok(views.html.recommendations(recommendations, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }
}