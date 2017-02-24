import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
import org.apache.spark.mllib.recommendation.Rating

// [Inspired by]
// http://spark.apache.org/docs/latest/mllib-collaborative-filtering.html
// https://www.codementor.io/jadianes/building-a-recommender-with-apache-spark-python-example-app-part1-du1083qbw

object Recommender extends App {

  override
  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("CollaborativeFiltering").setMaster("local[*]")
    val sc = new SparkContext(conf)

    // Load the data
    var dinit : Array[Rating] = Array()
    for( user <- 1 to 10){
      for( article <- 1 to 100){
        val r = scala.util.Random
        if(r.nextInt(100) > 80){
          dinit +:= Rating(user,article,r.nextInt(100))
        }
      }
    }
    val ratings = sc.parallelize(dinit)

    // TODO: Learn best rank and lamda

    // Build the recommendation model using ALS
    val rank = 10
    val numIterations = 10
    val lamda = 0.01
    val model = ALS.train(ratings, rank, numIterations, lamda)

    // Evaluate the model on rating data
    val usersItems = ratings.map { case Rating(user, product, rate) =>
      (user, product)
    }
    val predictions =
      model.predict(usersItems).map { case Rating(user, product, rate) =>
        ((user, product), rate)
      }
    val actualAndPredictions = ratings.map { case Rating(user, product, rate) =>
      ((user, product), rate)
    }.join(predictions)
    val MSE = actualAndPredictions.map { case ((user, product), (r1, r2)) =>
      val err = r1 - r2
      err * err
    }.mean()
    println("MSE: "+MSE)

    // Generate and Store recommendations
    var aUserArticle :Array[Rating] = Array()
    for( user <- 1 to 10){
      for( article <- 1 to 100){
        aUserArticle +:= Rating(user,article,0)
      }
    }

    // TODO: Filter out already liked articles

    val aUserArticleDistributed = sc.parallelize(aUserArticle)
    val usersItemsTotal = aUserArticleDistributed.map { case Rating(user, product, rate) =>
      (user, product)
    }
    val predictionsTotal =
      model.predict(usersItemsTotal).map { case Rating(user, product, rate) =>
        (user, product, rate)
      }

    val kinds = predictionsTotal.groupBy(_._1)
    kinds.foreach(x => {
      val recommandations = x._2.toList.sortBy(_._3).takeRight(5)
      val userid = recommandations.head._1
      var recommandationstring : String = "Top 5 recommandations for user "+userid+":"
      recommandations.foreach(x =>  recommandationstring += " " + x._2)
      println(recommandationstring)
    })

    sc.stop()
  }
}