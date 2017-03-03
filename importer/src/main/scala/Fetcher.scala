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

    // val response: HttpResponse[String] = Http("https://api.nytimes.com/svc/archive/v1/2016/1.json").param("api-key", "6abaec279f9d4c4dad5459690c7b4563").asString
    val response: HttpResponse[String] = Http("https://newsapi.org/v1/articles").param("source", "techcrunch").param("apikey", "919447fbe85f4ad09e7ee0b868efdbd4").asString

    val rdd: RDD[String] = spark.sparkContext.makeRDD(response.body :: Nil)

    val df: DataFrame = spark.read.json(rdd)

    val articles: DataFrame = df.select(org.apache.spark.sql.functions.explode(df.col("articles")).as("a"))

    // df.printSchema()

    MongoSpark.write(articles).option("collection", "articles").mode("overwrite").save()
  }
}
