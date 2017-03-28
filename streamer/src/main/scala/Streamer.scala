import java.util

import com.mongodb.spark.MongoSpark
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}

object Streamer {
  def main(args: Array[String]): Unit = {
    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toInt
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")

    val topic = "ratings"
    val kafkaAddress = sys.env.get("KAFKA_ADDRESS").getOrElse("localhost")
    val kafkaPort = sys.env.get("KAFKA_PORT").getOrElse("9092").toInt

    val spark = SparkSession
      .builder()
      .master("local")
      .appName("streamer")
      .config("spark.mongodb.input.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".stream")
      .config("spark.mongodb.output.uri", "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace+".stream")
      .getOrCreate()

    val ssc = new StreamingContext(spark.sparkContext, Seconds(1))

    val articles = MongoSpark.load(spark) // .select("articles")

    // println(articles.select("articles.web_url").count() + " articles currently in collection.")

    val props = new util.HashMap[String, Object]()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAddress+":"+kafkaPort)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")

    articles.foreachPartition {
      partition =>
        val producer = new KafkaProducer[String, String](props)

        partition.foreach {
          article =>
            val rnd = scala.util.Random
            val message = new ProducerRecord[String, String](topic, null, rnd.nextInt(255) + "," + rnd.nextInt(255) + "," + rnd.nextFloat)
            producer.send(message)
        }
    }
  }
}
