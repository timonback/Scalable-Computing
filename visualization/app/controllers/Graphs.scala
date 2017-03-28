package controllers

import javax.inject.Inject

import models.Article
import play.api.i18n.MessagesApi
import play.api.mvc.{Action, Controller}
import services.RecommendationService
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.Future


class Graphs @Inject()(val messagesApi: MessagesApi, recommendationsService: RecommendationService)
  extends Controller {


  def index = Action {
    implicit request =>
      Ok(views.html.graph())
  }

  def data = Action.async { implicit request =>
    var users:Set[Int] = Set.empty
    var articles:Set[Int] = Set.empty
    var edges:Map[Int,Int] = Map.empty

    def recommendationsFuture = recommendationsService.all(request)

    val userIdsFuture = for {
      recommendations <- recommendationsFuture
    } yield recommendations.map(recommendation => recommendation.userid).distinct
    val articleIdsFuture = for {
      recommendations <- recommendationsFuture
    } yield recommendations.flatMap(recommendation => recommendation.recommendations).distinct
    var connectionsFuture  = for {
      recommendations <- recommendationsFuture
    } yield recommendations.flatMap(recommendation =>
      recommendation.recommendations.map(
        recommendationIndex => Pair(recommendation.userid, recommendationIndex)
      ).distinct
    )

    for {
      userIds <- userIdsFuture
      articleIds <- articleIdsFuture
      connections <- connectionsFuture

      result = Ok(
        Json.obj(
          "nodes" -> (
            userIds.map(userId => Json.obj(
              "x" -> Math.random(),
              "y" -> Math.random(),
              "size" -> 10,
              "color" -> "#0000ff",
              "id" -> ("user" + userId.toString()),
              "label" -> ("user" + userId.toString())
            ))
            ++
            articleIds.map(articleId => Json.obj(
              "x" -> 0,
              "y" -> 0,
              "size" -> 10,
              "color" -> "#ff0000",
              "id" -> ("article" + articleId.toString()),
              "label" -> ("article" + articleId.toString())
            ))
        )
          ,
          "edges" ->
            connections.map(connection => Json.obj(
              "source" -> ("user" + connection._1),
              "target" -> ("article" + connection._2.toString),
              "size" -> 1,
              "id" -> ("user" + connection._1 + "-" + connection._2.toString),
              "label" -> ("user" + connection._1 + "-" + connection._2.toString)
            ))
          ))
    } yield result
  }
}