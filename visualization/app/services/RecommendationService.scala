package services

import javax.inject.Inject

import models.Recommendation
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Request
import play.modules.reactivemongo.{MongoController, ReactiveMongoApi, ReactiveMongoComponents}
import reactivemongo.play.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RecommendationService @Inject()(val reactiveMongoApi: ReactiveMongoApi,
                               implicit val materializer: akka.stream.Materializer)
  extends MongoController with ReactiveMongoComponents {

  private def collectionName = "recommendations"
  private def collection = MongoDB.database.
    map(_.collection[reactivemongo.play.json.collection.JSONCollection](collectionName))


  def all(request: Request[_]): Future[List[Recommendation]] = {
    // get a sort document (see getSort method for more information)
    val sort: JsObject = getSort(request).getOrElse(Json.obj())

    // the cursor of documents
    val found = collection.map(_.find(Json.obj()).sort(sort).cursor[Recommendation]())

    found.flatMap(_.collect[List]())
  }

  def findById(id: String): Future[Option[Recommendation]] = {
    collection.flatMap(_.find(Json.obj("_id" -> id.toInt)).one[Recommendation])
  }

  def insert(user: Recommendation) = {
    collection.flatMap(_.insert(user))
  }

  def remove(id: String) = {
    collection.map(coll => coll.remove(Json.obj("_id" -> id.toInt)))
  }

  def update(id: String, modifier: JsObject) = {
    collection.map(_.update(Json.obj("_id" -> id.toInt), modifier))
  }

  private def getSort(request: Request[_]): Option[JsObject] =
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "userid" || order._1 == "recommendations"
      } yield order._1 -> implicitly[Json.JsValueWrapper](Json.toJson(order._2))

      Json.obj(sortBy: _*)
    }
}