package recommender

import breeze.linalg._
import breeze.optimize.linear.PowerMethod.BDM
import com.mongodb.spark.MongoSpark
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry, _}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector, Matrices, Vector}
import _root_.kafka.serializer.DefaultDecoder
import _root_.kafka.serializer.StringDecoder
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._

object StreamingRecommender extends App {
  var sc: SparkContext = _

  override
  def main(args: Array[String]) = {
    val sparkAddress = sys.env.get("SPARK_ADDRESS").getOrElse("localhost")
    val sparkPort = sys.env.get("SPARK_PORT").getOrElse("7077")

    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toLong
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")

    val useDummyDataOpt = sys.env.get("USE_DUMMY_DATA")

    var sparkUrl = "spark://" + sparkAddress + ":" + sparkPort

    val mongoUrl = "mongodb://" + dbAddress + ":" + dbPort + "/" + dbKeySpace

    println("Spark expected at: " + sparkUrl)
    println("Mongo expected at: " + mongoUrl)


    val ss = SparkSession
      .builder()
      .master(sparkUrl)
      .appName("recommender")
      .config("spark.mongodb.input.uri", mongoUrl + ".articleFactors")
      .config("spark.mongodb.output.uri", mongoUrl + ".recommendations")
      .getOrCreate()
    sc = ss.sparkContext
    sc.setLogLevel("ERROR")

    var model = loadModel()

    // TODO: new rating -> remove from recommendations of user (make user key in db?)
    // TODO: check length array, length == 0? then:

    val ratings = loadRatings() // TODO: read from DB all ratings by user (make user key in db?)

    if(true){ // TODO: check if updated
      model = loadModel()
    }
    val numIterations = 12
    val numLatentFactors = 35
    val numArticles = ratings.groupBy(_.article).map(a=>a._1).collect()
    val regularization = 0.1
    val numPredictions = 10

    val recommendations = recommendArticlesForNewUsers(ratings,numIterations,numLatentFactors,regularization,model,numPredictions)
    BatchRecommender.storeRecommendations(ss,recommendations,true)

    sc.stop()
  }

  def loadModel(): ALSModel = {
    var temp = MongoSpark.load(sc).toDF.rdd
    val factors = temp.map(row => (row.getLong(0),row.getAs[Array[Double]](1) ))

    ALSModel(null,factors)
  }

  def loadRatings(): RDD[Rating] ={
    null
  }

  def recommendArticlesForNewUsers(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int, regularization: Double, model: ALSModel,number :Int): RDD[(Long, Array[Long])] = {
    // Initialize User Factors
    var userFactors: RDD[(Long, Array[Double])] = null
    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = BatchRecommender.alsStep(ratings, numLatentFactors, regularization, model.articleFactors, true)
    }
    var newModel = ALSModel(userFactors, model.articleFactors)
    // Recommend Articles
    BatchRecommender.recommendArticles(number, newModel,ratings)
  }
}