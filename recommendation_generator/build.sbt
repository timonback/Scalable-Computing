name := "recommendation_generator"

version := "1.0"

scalaVersion := "2.11.8"

val sparkVersion = "2.1.0"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-mllib" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % "2.1.0",
  "org.scalaj" %% "scalaj-http" % "2.3.0",
  "org.mongodb.spark" %% "mongo-spark-connector" % "2.0.0"
)
