import com.mongodb.spark.MongoSpark
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}

import scalaj.http.{Http, HttpResponse}

object Fetcher {
  def main(args: Array[String]): Unit = {
    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toInt
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")

    val spark = SparkSession
      .builder()
      .master("local")
      .appName("fetcher")
      .config("spark.mongodb.input.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".articles")
      .config("spark.mongodb.output.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".articles")
      .getOrCreate()

    val importApiKey = sys.env.get("IMPORT_API_KEY").getOrElse("6abaec279f9d4c4dad5459690c7b4563")

    val importStartYear = sys.env.get("IMPORT_START_YEAR").getOrElse("2017").toInt
    val importStartMonth = sys.env.get("IMPORT_START_MONTH").getOrElse("3").toInt

    var year = importStartYear
    var month = importStartMonth
    var httpResponse: HttpResponse[String] = null

    do {
      httpResponse = Http("https://api.nytimes.com/svc/archive/v1/" + year + "/" + month + ".json").param("api-key", importApiKey).asString

      val rdd: RDD[String] = spark.sparkContext.makeRDD(httpResponse.body :: Nil)

      val df: DataFrame = spark.read.json(rdd)
      val articles: DataFrame = df.select(org.apache.spark.sql.functions.explode(df.col("response.docs")).as("articles"))

      val count = articles.count().toInt
      val countStored = (count * 0.75).toInt
      val countStream = count - countStored
      val storedArticles = articles.limit(countStored)
      val streamArticles = articles.limit(countStream)

      MongoSpark.write(storedArticles).option("collection", "articles").mode("append").save()
      MongoSpark.write(streamArticles).option("collection", "stream").mode("append").save()

      // DEBUG
      // val articlesIn = MongoSpark.load(spark).select("articles")
      // println(articlesIn.select("articles.web_url").count() + " articles currently in collection.")

      month -= 1

      if (month == 0) {
        year -= 1
        month = 12
      }
    }
    while (httpResponse.code == 200)
  }
}
