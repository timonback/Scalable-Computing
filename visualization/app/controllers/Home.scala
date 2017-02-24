package controllers

import javax.inject._
import play.api._
import play.api.mvc._

@Singleton
class Home extends Controller {

  def index = Action {
    implicit request =>
    Ok(views.html.index("Hello"))
  }

}