package recommender

import com.mongodb.spark.MongoSpark
import com.mongodb.spark.config.ReadConfig
import com.mongodb.spark.sql.fieldTypes.ObjectId
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka.KafkaUtils

object StreamingRecommender extends App {
  var sc: SparkContext = _
  var ss: SparkSession = _

  var sparkAddress: String = _
  var sparkPort: Int = _
  var kafkaAddress: String = _
  var kafkaPort: Int = _
  var kafkaTopic: String = _
  var dbAddress: String = _
  var dbPort: Int = _
  var dbKeySpace: String = _
  var useDummyDataOpt: Option[String] = _
  var sparkUrl: String = _
  var kafkaUrl: String = _
  var mongoUrl: String = _

  override
  def main(args: Array[String]): Unit = {
    sparkAddress = sys.env.getOrElse("SPARK_ADDRESS", "localhost")
    sparkPort = sys.env.getOrElse("SPARK_PORT", "7077").toInt
    dbAddress = sys.env.getOrElse("MONGO_ADDRESS", "localhost")
    kafkaAddress = sys.env.getOrElse("KAFKA_ADDRESS", "localhost")
    kafkaPort = sys.env.getOrElse("KAFKA_PORT", "2181").toInt
    kafkaTopic = sys.env.getOrElse("KAFKA_TOPIC", "ratings")
    dbPort = sys.env.getOrElse("MONGO_PORT", "27017").toInt
    dbKeySpace = sys.env.getOrElse("MONGO_KEYSPACE", "newsForYou")
    useDummyDataOpt = sys.env.get("USE_DUMMY_DATA")
    sparkUrl = "spark://" + sparkAddress + ":" + sparkPort
    kafkaUrl = kafkaAddress + ":" + kafkaPort
    mongoUrl = "mongodb://" + dbAddress + ":" + dbPort + "/" + dbKeySpace

    println("Spark expected at: " + sparkUrl)
    println("Kafka expected at: " + kafkaUrl)
    println("Mongo expected at: " + mongoUrl)

    ss = SparkSession
      .builder()
      .master(sparkUrl)
      .appName("recommender")
      .config("spark.mongodb.input.uri", mongoUrl + ".articleFactors")
      .config("spark.mongodb.output.uri", mongoUrl + ".recommendations")
      .getOrCreate()
    sc = ss.sparkContext
    
    var jarFileEnv = sys.env.get("SPARK_JAR").getOrElse("")
	  println("Add jar file(s) to spark: " + jarFileEnv)
	  for(jarFile <- jarFileEnv.split(",")) {
		  sc.addJar(jarFile)
	  }

    val Array(zkQuorum, group, topics, numThreads) = Array(kafkaUrl, "ratingConsumer", kafkaTopic, "1")
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

    var inputCollection = mongoUrl + ".articleFactors"
    var temp = MongoSpark.load(sc, ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF.rdd
    var factors = temp.map(row => (row.getAs[Long]("articleId"), row.getAs[Array[Double]]("latentFactors")))

    ALSModel(null, factors)
  }

  def processLine(a: String): String = {
    val si = a.split(",")
    val userId = si(0).toLong
    val articleId = si(1).toLong
    var inputCollection = mongoUrl + ".recommendations"
    var temp = MongoSpark.load(sc, ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF
    val rows = temp.filter(row => row.getAs[Long]("userid") == userId)

    if (rows.count() == 1) {
      val row = rows.first()
      val newArray: Array[Long] = row.getAs[Seq[Long]]("recommendations").filter(a => a != articleId).toArray

      // Update recommendations in DB
      val newDocs = Seq((ObjectId(row.get(0).toString.substring(1, row.get(0).toString.length() - 1)), userId, newArray))

      val df: DataFrame = ss.createDataFrame(newDocs)
      val lpDF = df.withColumnRenamed("_1", "_id").withColumnRenamed("_2", "userid").withColumnRenamed("_3", "recommendations")
      lpDF.printSchema()
      MongoSpark.write(lpDF).option("collection", "recommendations").mode(SaveMode.Append).save()

      // Generate new recommendations
      if (newArray.isEmpty) generateNewRatings(userId)
    }
    a
  }

  def loadRatings(userId: Long): RDD[Rating] = {
    var inputCollection = mongoUrl + ".ratings"
    var temp = MongoSpark.load(sc, ReadConfig(Map("spark.mongodb.input.uri" -> inputCollection))).toDF
    val rows = temp.filter(row => row.getAs[Long]("userId") == userId)
    rows.rdd.map(r => Rating(r.getAs[Long]("userId"), r.getAs[Long]("articleId"), r.getAs[Double]("rating")))
  }

  def generateNewRatings(userId: Long): Unit = {
    var model = loadModel()
    val ratings = loadRatings(userId)
    model = loadModel()

    val numIterations = 12
    val numLatentFactors = 35
    val numArticles = ratings.groupBy(_.article).map(a => a._1).collect()
    val regularization = 0.1
    val numPredictions = 10

    val recommendations = recommendArticlesForNewUsers(ratings, numIterations, numLatentFactors, regularization, model, numPredictions)
    BatchRecommender.storeRecommendations(ss, recommendations, true)
  }

  def recommendArticlesForNewUsers(ratings: RDD[Rating], numIterations: Int, numLatentFactors: Int, regularization: Double, model: ALSModel, number: Int): RDD[(Long, Array[Long])] = {
    // Initialize User Factors
    var userFactors: RDD[(Long, Array[Double])] = null
    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = BatchRecommender.alsStep(ratings, numLatentFactors, regularization, model.articleFactors, true)
    }
    var newModel = ALSModel(userFactors, model.articleFactors)
    // Recommend Articles
    BatchRecommender.recommendArticles(number, newModel, ratings)
  }
}
