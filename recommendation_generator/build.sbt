name := "newsforyou-recommendator"
dockerRepository := Some("timonback")

version := "latest"

lazy val `importer` = (project in file("."))
.enablePlugins(DockerPlugin)
.enablePlugins(JavaAppPackaging)

scalaVersion := "2.11.7"

val sparkVersion = "2.1.0"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-mllib" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % "2.1.0",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.mongodb.spark" %% "mongo-spark-connector" % "2.0.0",
  "org.scalanlp" %% "breeze" % "0.13",
  "org.scalanlp" %% "breeze-natives" % "0.13",
  "org.apache.spark" % "spark-streaming-kafka_2.10" % "1.6.2",
  "org.apache.kafka" % "kafka_2.10" % "0.8.2.2",
  "org.apache.kafka" % "kafka-clients" % "0.8.2.2"
)
