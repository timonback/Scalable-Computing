import com.mongodb.spark.MongoSpark
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}

import scalaj.http.{Http, HttpResponse}

object Fetcher {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .master("local")
      .appName("fetcher")
      .config("spark.mongodb.input.uri", "mongodb://172.17.0.2/test.myCollection")
      .config("spark.mongodb.output.uri", "mongodb://172.17.0.2/test.myCollection")
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
