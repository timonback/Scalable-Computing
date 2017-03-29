package recommender

import breeze.linalg._
import breeze.optimize.linear.PowerMethod.BDM
import com.mongodb.spark.MongoSpark
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry, _}
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode, SparkSession}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector, Matrices, Vector}
import _root_.kafka.serializer.DefaultDecoder
import _root_.kafka.serializer.StringDecoder
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming._
import com.mongodb.spark._
import com.mongodb.spark.config.ReadConfig
import com.mongodb.spark.sql.fieldTypes.ObjectId
import org.bson.Document

object StreamingRecommender extends App {
  var sc: SparkContext = _
  var ss: SparkSession = _

  var sparkAddress : String = _
  var sparkPort : Int = _
  var zkAddress : String = _
  var zkPort : Int = _
  var kafkaTopic : String = _
  var dbAddress : String = _
  var dbPort : Int = _
  var dbKeySpace : String = _
  var useDummyDataOpt : Option[String] = _
  var sparkUrl : String = _
  var zkUrl : String = _
  var dbUrl : String = _

  override
  def main(args: Array[String]) = {

    sparkAddress = sys.env.get("SPARK_ADDRESS").getOrElse("localhost")
    sparkPort = sys.env.get("SPARK_PORT").getOrElse("7077").toInt
    dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    zkAddress = sys.env.get("KAFKA_ADDRESS").getOrElse("localhost")
    zkPort = sys.env.get("KAFKA_PORT").getOrElse("2181").toInt
    kafkaTopic = sys.env.get("KAFKA_TOPIC").getOrElse("ratings")
    dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toInt
    dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")
    useDummyDataOpt = sys.env.get("USE_DUMMY_DATA")
    sparkUrl = "spark://" + sparkAddress + ":" + sparkPort
    zkUrl = zkAddress+":"+zkPort
    dbUrl = "mongodb://" + dbAddress + ":" + dbPort + "/" + dbKeySpace

    println("Spark expected at: " + sparkUrl)
    println("Zookeeper expected at: " + zkUrl)
    println("Mongo expected at: " + dbUrl)

    ss = SparkSession
      .builder()
      .master("local")
      .appName("streaming_recommender")
      .config("spark.mongodb.input.uri", dbUrl + ".articleFactors")
      .config("spark.mongodb.output.uri", dbUrl + ".recommendations")
      .getOrCreate()
    sc = ss.sparkContext

    val Array(zkQuorum, group, topics, numThreads) = Array(zkUrl,"ratingConsumer",kafkaTopic,"1")
    val ssc = new StreamingContext(sc, Seconds(2))
    ssc.checkpoint("checkpoint")

    val topicMap = topics.split(",").map((_, numThreads.toInt)).toMap

    val lines = KafkaUtils.createStream(ssc, zkQuorum, group, topicMap).map(_._2)
    val words = lines.flatMap(_.split(" "))

    lines.map(processLine)
    lines.print()

    ssc.start()
    ssc.awaitTermination()

    sc.stop()
  }

  def loadModel(): ALSModel = {

    var inputCollection = dbUrl + ".articleFactors"
    var temp = MongoSpark.load(sc,ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF.rdd
    var factors = temp.map(row => (row.getAs[Long]("articleId"),row.getAs[Array[Double]]("latentFactors")))

    ALSModel(null,factors)
  }

  def processLine(a:String): String ={
    val si = a.split(",")
    val userId = si(0).toLong
    val articleId = si(1).toLong
    var inputCollection = dbUrl + ".recommendations"
    var temp = MongoSpark.load(sc,ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF
    val rows = temp.filter(row => row.getAs[Long]("userid") == userId )

    if(rows.count() == 1){
      val row = rows.first()
      val newArray : Array[Long] = row.getAs[Seq[Long]]("recommendations").filter(a=>a!=articleId).toArray

      // Update recommendations in DB
      val newDocs = Seq( (ObjectId(row.get(0).toString.substring(1, row.get(0).toString.length()-1)),userId, newArray) )

      val df : DataFrame =  ss.createDataFrame( newDocs )
      val lpDF = df.withColumnRenamed("_1", "_id").withColumnRenamed("_2", "userid").withColumnRenamed("_3", "recommendations")
      lpDF.printSchema()
      MongoSpark.write(lpDF).option("collection", "recommendations").mode(SaveMode.Append).save()

      // Generate new recommendations
      if(newArray.isEmpty) generateNewRatings(userId)
    }
    a
  }

  def loadRatings(userId : Long): RDD[Rating] ={
    var inputCollection = dbUrl + ".ratings"
    var temp = MongoSpark.load(sc,ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF
    val rows = temp.filter(row => row.getAs[Long]("userId") == userId )
    rows.rdd.map(r=>Rating(r.getAs[Long]("userId"),r.getAs[Long]("articleId"),r.getAs[Double]("rating")))
  }

  def generateNewRatings(userId : Long): Unit ={
    var model = loadModel()
    val ratings = loadRatings(userId)
    model = loadModel()

    val numIterations = 12
    val numLatentFactors = 35
    val numArticles = ratings.groupBy(_.article).map(a=>a._1).collect()
    val regularization = 0.1
    val numPredictions = 10

    val recommendations = recommendArticlesForNewUsers(ratings,numIterations,numLatentFactors,regularization,model,numPredictions)
    BatchRecommender.storeRecommendations(ss,recommendations,true)
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
