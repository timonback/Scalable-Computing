package controllers

import javax.inject.Inject

import play.api.i18n.MessagesApi
import play.api.mvc.{Action, Controller}
import services.{ArticleService}

import play.api.libs.concurrent.Execution.Implicits.defaultContext


class Articles @Inject()(val messagesApi: MessagesApi, articleService: ArticleService)
  extends Controller {


  def index = Action.async { implicit request =>

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    articleService.all(request).map { articles =>
      Ok(views.html.articles(articles, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }
}