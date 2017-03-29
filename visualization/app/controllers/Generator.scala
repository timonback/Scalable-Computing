package controllers

import javax.inject.Inject

import models.User
import play.api.i18n.MessagesApi
import play.api.mvc.{Action, Controller}
import services.{RandomService, UserService}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json


class Generator @Inject()(val messagesApi: MessagesApi, userService: UserService, randomService: RandomService)
  extends Controller {


  def index = Action {
    Ok(Json.obj("generator"->true))
  }

  def genUsers(amountStr: String) = Action.async {implicit request =>
    userService.all(request).map { users =>
      val num = users.count(_ => true)

      for(id <- num until (num + amountStr.toInt)) {
        val username = randomService.randomAlphanumericString(15)
        val password = randomService.randomAlphanumericString(10)

        val user = User(id, username, password, Option.empty, Option.empty, Option.empty)
        userService.insert(user)
      }

      Ok(Json.obj("amountOfUsers"->(num+amountStr.toInt)))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }
}