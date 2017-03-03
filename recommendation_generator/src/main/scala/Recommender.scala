import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.Rating
import com.mongodb.spark.MongoSpark
import org.apache.spark.sql.{DataFrame, SQLContext, SparkSession}

// [Inspired by]
// http://spark.apache.org/docs/latest/mllib-collaborative-filtering.html
// https://www.codementor.io/jadianes/building-a-recommender-with-apache-spark-python-example-app-part1-du1083qbw

object Recommender extends App {

  override
  def main(args: Array[String]) = {
    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toInt
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")


    val ss = SparkSession
      .builder()
      .master("local")
      .appName("recommender")
      .config("spark.mongodb.input.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".ratings")
      .config("spark.mongodb.output.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".recommendations")
      .getOrCreate()
    val sc = ss.sparkContext

    // Load random data:
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

    // Or load from database
    //case class UserRating(userid: Int, article: Int, rating: Int)
    //val ratings = MongoSpark.load(sc).toDF[Rating]

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

    // Generate recommendations
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

    // Generate recommendations
    val users = predictionsTotal.groupBy(_._1)
    val recommendations = users.map(x => {
      val recommendations = x._2.toList.sortBy(_._3).takeRight(5)
      (recommendations.head._1,recommendations.map(x => x._2))
    })

    // Print recommendations
    recommendations.foreach(x=>{
      println(x)
    })

    // Store recommendations
    val df : DataFrame =  ss.createDataFrame( recommendations )
    val lpDF = df.withColumnRenamed("_1", "userid").withColumnRenamed("_2", "recommendations")
    lpDF.printSchema()
    MongoSpark.write(lpDF).option("collection", "recommendations").mode("overwrite").save()

    sc.stop()
  }
}
