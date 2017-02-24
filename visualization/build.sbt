name := "newsForYou-visualization"
dockerRepository := Some("timonback")


version := "1.0"

lazy val `visualization` = (project in file("."))
  .enablePlugins(PlayScala)
  .enablePlugins(DockerPlugin)

scalaVersion := "2.11.1"

unmanagedResourceDirectories in Test <+=  baseDirectory ( _ /"target/web/public/test" )

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"

resolvers += "Spark Packages Repo" at "https://dl.bintray.com/spark-packages/maven"
//phantom dsl
resolvers ++= Seq(
  "Typesafe repository releases" at "http://repo.typesafe.com/typesafe/releases/",
  "Typesafe repository snapshots" at "http://repo.typesafe.com/typesafe/snapshots/",
  "Sonatype repo"                    at "https://oss.sonatype.org/content/groups/scala-tools/",
  "Sonatype releases"                at "https://oss.sonatype.org/content/repositories/releases",
  "Sonatype snapshots"               at "https://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype staging"                 at "http://oss.sonatype.org/content/repositories/staging",
  "Java.net Maven2 Repository"       at "http://download.java.net/maven/2/",
  "Twitter Repository"               at "http://maven.twttr.com",
  Resolver.bintrayRepo("websudos", "oss-releases")
)

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  //"org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % "test",
  "org.reactivemongo" %% "play2-reactivemongo" % "0.12.1",
  "com.adrianhurt" %% "play-bootstrap" % "1.1-P25-B3"
)

routesGenerator := InjectedRoutesGenerator

fork in run := true
