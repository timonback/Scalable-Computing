import java.util

import com.mongodb.spark.MongoSpark
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.{Seconds, StreamingContext}

object Streamer {
  def main(args: Array[String]): Unit = {
    val dbAddress = sys.env.getOrElse("MONGO_ADDRESS", "localhost")
    val dbPort = sys.env.getOrElse("MONGO_PORT", 27017)
    val dbKeySpace = sys.env.getOrElse("MONGO_KEYSPACE", "newsForYou")

    val kafkaAddress = sys.env.getOrElse("KAFKA_ADDRESS", "localhost")
    val kafkaPort = sys.env.getOrElse("KAFKA_PORT", 9092)
    val topic = sys.env.getOrElse("KAFKA_TOPIC", "ratings")

    val spark = SparkSession
      .builder()
      .master("local")
      .appName("streamer")
      .config("spark.mongodb.input.uri", "mongodb://" + dbAddress + ":" + dbPort + "/" + dbKeySpace + ".stream")
      .config("spark.mongodb.output.uri", "mongodb://" + dbAddress + ":" + dbPort + "/" + dbKeySpace + ".stream")
      .getOrCreate()

    val ssc = new StreamingContext(spark.sparkContext, Seconds(1))

    val articles = MongoSpark.load(spark).select("id")

    // println(articles.select("articles.web_url").count() + " articles currently in collection.")

    val props = new util.HashMap[String, Object]()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAddress + ":" + kafkaPort)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
      "org.apache.kafka.common.serialization.StringSerializer")

    println("Starting stream...")

    articles.foreachPartition {
      partition =>
        val producer = new KafkaProducer[String, String](props)

        partition.foreach {
          article =>
            val rnd = scala.util.Random
            val message = new ProducerRecord[String, String](topic, null, article.getInt(0) + "," + rnd.nextInt(255) + "," + rnd.nextFloat)
            producer.send(message)
            println("Sending" + article.getInt(0))
        }
    }

    println("Finished stream")
  }
}
