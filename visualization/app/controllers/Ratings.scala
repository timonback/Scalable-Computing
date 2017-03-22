package controllers

import javax.inject.Inject

import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{Action, Controller}
import services.RatingService


class Ratings @Inject()(val messagesApi: MessagesApi, ratingService: RatingService)
  extends Controller {


  def index = Action.async { implicit request =>

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    ratingService.all(request).map { ratings =>
      Ok(views.html.ratings(ratings, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }
}