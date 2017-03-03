package models

import play.api.data.Form
import play.api.data.Forms._

case class Article(
                   id: Int,
                   title: String,
                   url: String)

object Article {
  import play.api.libs.json._

  implicit object ArticleWrites extends OWrites[Article] {
    def writes(article: Article): JsObject = Json.obj(
      "id" -> article.id,
      "title" -> article.title,
      "url" -> article.url)
  }

  implicit object ArticleReads extends Reads[Article] {
    def reads(json: JsValue): JsResult[Article] = json match {
      case obj: JsObject => try {
        val id = (obj \ "id").as[Int]
        val title = (obj \ "title").as[String]
        val url = (obj \ "url").as[String]

        JsSuccess(Article(id, title, url))

      } catch {
        case cause: Throwable => JsError(cause.getMessage)
      }

      case _ => JsError("expected.jsobject")
    }
  }

  val form = Form(
    mapping(
      "id" -> number,
      "title" -> nonEmptyText,
      "url" -> nonEmptyText) {
      (id, title, url) =>
        Article(
          id,
          title,
          url)
    } { article =>
      Some(
        (article.id,
          article.title,
          article.url))
    })
}
