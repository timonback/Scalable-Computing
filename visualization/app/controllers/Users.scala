package controllers

import java.util.UUID
import javax.inject.Inject

import org.joda.time.DateTime

import scala.concurrent.{Future, Promise}
import play.api.i18n.MessagesApi
import play.api.mvc.{Action, Controller, Request, Session}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import models.User
import play.api.libs.json.{JsObject, Json}
import play.modules.reactivemongo.json._
import services.UserService

import scala.util.Random

class Users @Inject()(val messagesApi: MessagesApi, userService: UserService)
  extends Controller {


  val index = Action.async { implicit request =>

    val activeSort = request.queryString.get("sort").
      flatMap(_.headOption).getOrElse("none")

    userService.all(request).map { users =>
      Ok(views.html.users(users, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  def showCreationForm = Action { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Ok(views.html.editUser(None, User.form))
  }

  def showLoginForm = Action { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    Ok(views.html.login(User.form))
  }

  def showEditForm(id: String) = Action.async { implicit request =>

    val futureUser = userService.findById(id)

    for {
      maybeUser <- futureUser
      result <- Promise.successful(maybeUser.map { user =>
        implicit val messages = messagesApi.preferred(request)

        Ok(views.html.editUser(Some(id), User.form.fill(user)))
      }).future
    } yield result.getOrElse(NotFound)
  }


  def create = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    User.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editUser(None, errors))),

      // if no error, then insert the user into the 'users' collection
      user =>
        userService.insert(
          user.copy(
            id = Math.abs(new Random().nextInt()),
            creationDate = Some(new DateTime()),
            updateDate = Some(new DateTime())
          )
        )
    ).map(_ => Redirect(routes.Users.index))
  }

  def edit(id: String) = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)
    import reactivemongo.bson.BSONDateTime

    User.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.editUser(Some(id), errors))),

      user => {
        val modifier = Json.obj(
          "$set" -> Json.obj(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "username" -> user.username,
            "password" -> user.password,
            "email" -> user.email)
        )

        // ok, let's do the update
        userService.update(id, modifier).map(_ =>
          //Redirect(routes.Users.showEditForm(id)))
          Ok(views.html.editUser(Some(id), User.form.fill(user))))
      })
  }

  def delete(id: String) = Action {
    userService.remove(id)
    Redirect(routes.Users.index)
  }

  def login = Action.async { implicit request =>
    implicit val messages = messagesApi.preferred(request)

    User.form.bindFromRequest.fold(
      errors => Future.successful(
        Ok(views.html.login(errors))),

      user => {
        def futureUser = userService.findByUsername(user.username)

        for {
          maybeUser <- futureUser
          result <- Promise.successful(maybeUser.map { foundUser => {
            if (foundUser.password.equals(user.password)) {
              Redirect(routes.Home.index).withSession(new Session(Map("username" -> foundUser.username)))
            } else {
              Redirect(routes.Users.login)
            }
          }
          }).future
        } yield result.getOrElse(Redirect(routes.Users.login))
      })
  }

  def logout = Action(
    Redirect(routes.Home.index()).withNewSession
  )

  private def getSort(request: Request[_]): Option[JsObject] =
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "username" || order._1 == "email" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> implicitly[Json.JsValueWrapper](Json.toJson(order._2))

      Json.obj(sortBy: _*)
    }

}
