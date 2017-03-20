package recommender

import breeze.linalg._
import breeze.optimize.linear.PowerMethod.BDM
import com.mongodb.spark.MongoSpark
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry, _}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector, Matrices, Vector}


case class ALSModel (userFactors: RDD[(Int,Array[Double])], articleFactors: RDD[(Int,Array[Double])])
case class Rating (user:Int, article:Int, rating:Double)

object Recommender extends App{
  var sc : SparkContext = _

  override
  def main(args: Array[String]) = {
    val sparkAddress = sys.env.get("SPARK_ADDRESS").getOrElse("localhost")
    val sparkPort = sys.env.get("SPARK_PORT").getOrElse("7077")

    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toInt
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")

    val useDummyDataOpt = sys.env.get("USE_DUMMY_DATA")

    var sparkUrl = "spark://"+sparkAddress+":"+sparkPort

    // TEMP
    //sparkUrl = "local"

    val mongoUrl = "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace

    println("Spark expected at: " + sparkUrl)
    println("Mongo expected at: " + mongoUrl)


    val ss = SparkSession
      .builder()
      .master(sparkUrl)
      .appName("recommender")
      .config("spark.mongodb.input.uri", mongoUrl+".likes")
      .config("spark.mongodb.output.uri", mongoUrl+".recommendations")
      .getOrCreate()
    sc = ss.sparkContext


    var jarFileEnv = sys.env.get("SPARK_JAR").getOrElse("")
    println("Add jar file(s) to spark: " + jarFileEnv)
    for(jarFile <- jarFileEnv.split(",")) {
      sc.addJar(jarFile)
    }
   

    var ratingsRDD : RDD[Rating] =  null
    if(useDummyDataOpt.isEmpty) { 
      // Or load from db
      println("Loading rating data from DB")
      var temp = MongoSpark.load(sc).toDF.rdd
      ratingsRDD = temp.map(row => Rating(row.getInt(0), row.getInt(1), row.getDouble(2)))
    } else {
      // Load Random Rating Data
      println("Generating dummy rating data")
      var ratings : Array[Rating] = Array()
      for( user <- 0 to 200-1){
        for( article <- 0 to 100-1){
          val r = scala.util.Random
          if(r.nextInt(100) >= 50) {
            ratings +:= Rating(user,article,r.nextInt(1000)*.1)
          }
        }
      }
      ratingsRDD = sc.parallelize(ratings)
    }

    // Learn Model
    val numIterations = 4
    val numLatentFactors = 5
    val articleIndices = ratingsRDD.groupBy(_.article).map(a=>a._1).collect()
    val regularization = 0.01
    val numPredictions = 10
    val model : ALSModel = learnModel(ratingsRDD,numIterations,numLatentFactors,articleIndices,regularization)

    // Generate recommendations
    val recommendations = recommendArticles(numPredictions, model,ratingsRDD)

    // Store recommendations
    storeRecommendations(ss,recommendations)

    // Generate recommendations for anonymous user
    var newRatingsRDD = ratingsRDD
    var newRecommendations = recommendArticlesForNewUsers(newRatingsRDD, numIterations , numLatentFactors, regularization, model, numPredictions)

    // Store new recommendations
    storeRecommendations(ss,newRecommendations)

    sc.stop()
  }

  def learnModel(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int,articleIndices : Array[Int], regularization: Double): ALSModel = {
    // Initialize User and Article Factors
    var userFactors: RDD[(Int, Array[Double])] = null
    var articleFactors: RDD[(Int, Array[Double])] = initialize(articleIndices, numLatentFactors)

    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = alsStep(ratings, numLatentFactors, regularization, articleFactors, true)
      articleFactors = alsStep(ratings,  numLatentFactors,  regularization, userFactors, false)
    }

    ALSModel(userFactors, articleFactors)
  }

  def alsStep(ratings: RDD[Rating],  numLatentFactors : Int, regularization: Double, factors:RDD[(Int, Array[Double])], firstStage:Boolean) : RDD[(Int, Array[Double])] = {
    var ratingsBy : RDD[(Int, Rating)] = null
    if(firstStage){
      ratingsBy = ratings.keyBy(_.user)
    }else{
      ratingsBy = ratings.keyBy(_.article)
    }
    val ratingsWithFactors = factors.join(ratingsBy)

    val sumsSelfPerUser = dotSelfTransposeSelf(ratingsWithFactors, firstStage)
    var right = dotSelfTransposeRatings(ratingsWithFactors, firstStage)

    val toloop : RDD[(Int,( Array[Array[Double]], Array[Double] ))] = sumsSelfPerUser.join(right)

    var identMatrix : Array[Array[Double]] = identity(numLatentFactors, regularization)

    toloop.map(a=> (a._1, inverse(add(identMatrix,a._2._1)).multiply(toVector(a._2._2)).values ))
  }

  def toVector(array: Array[Double]) : DenseVector = {
    new DenseVector(array)
  }

  def add(a1: Array[Array[Double]], a2: Array[Array[Double]]) : DenseMatrix = {
    var numRows : Int = a1.length
    var numCols : Int = a1.length

    var a11 : Array[Double] = a1.reduce(_++_)
    var a22 : Array[Double] = a2.reduce(_++_)

    var values : Array[Double] = Array()

    for(i <- 0 to a11.length-1){
      values +:= a11(i) + a22(i)
    }

    new DenseMatrix(numRows,numCols,values)
  }

  def dotSelfTransposeRatings(factors: RDD[(Int,(Array[Double],Rating) )],firstStage:Boolean) : RDD[(Int,Array[Double])] = {

    var individualDotProducts : RDD[(Int,(Int,Array[Double]))] = null
    if(firstStage){
      individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => b * a._2._2.rating })) }).keyBy(_._1)
    }else{
      individualDotProducts = factors.map({ a => (a._2._2.article, a._2._1.map({ b => b * a._2._2.rating })) }).keyBy(_._1)
    }

    val summedDotProducts = individualDotProducts.reduceByKey((a,b) => (a._1, (a._2, b._2)
      .zipped.map(_ + _)))
      .map(a=>a._2)

    summedDotProducts
  }

  def storeRecommendations(ss: SparkSession,recommendations : RDD[(Int,Array[Int])]) = {
    val df : DataFrame =  ss.createDataFrame( recommendations )
    val lpDF = df.withColumnRenamed("_1", "userid").withColumnRenamed("_2", "recommendations")
    lpDF.printSchema()
    MongoSpark.write(lpDF).option("collection", "recommendations").mode("overwrite").save()
  }

  def dotSelfTransposeSelf(factors : RDD[(Int,(Array[Double],Rating) )], firstStage:Boolean) : RDD[(Int,Array[Array[Double]])] = {
    var individualDotProducts :RDD[(Int,(Int,Array[Array[Double]]))]= null
    if(firstStage){
      individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => a._2._1.map({ c => c * b }) })) }).keyBy(_._1)
    }else{
      individualDotProducts = factors.map({ a => (a._2._2.article, a._2._1.map({ b => a._2._1.map({ c => c * b }) })) }).keyBy(_._1)
    }
    var summedDotProducts = individualDotProducts.reduceByKey((a,b) => (a._1, (a._2, b._2)
      .zipped.map((c: Array[Double], d: Array[Double]) => (c, d)
      .zipped.map(_ + _)) ))
      .map(a=>a._2)

    summedDotProducts
  }

  def identity(lf : Int,r : Double) : Array[Array[Double]] = {
    var entries : Array[Array[Double]] = Array()

    for(i <- 0 to lf-1){
      var row : Array[Double] = Array()
      for(j <- 0 to lf-1){
        if(i==j){
          row +:= r
        }else{
          row +:= 0.0
        }
      }
      entries +:= row
    }
    entries
  }

  def inverse(mat: DenseMatrix): DenseMatrix = {
    var dm  = new breeze.linalg.DenseMatrix[Double](mat.numRows,mat.numCols,mat.values)
    dm = inv(dm)
    new DenseMatrix(dm.rows,dm.cols,dm.data)
  }

  def initialize( indexes : Array[Int], numLatentFactors : Int) : RDD[(Int, Array[Double])] = {
    var result: Array[(Int, Array[Double])] = Array()
    indexes.foreach( x => {
      var array : Array[Double] = Array()
      for (y <- 0 until numLatentFactors) {
        val r = scala.util.Random
        array +:= r.nextInt(1000)*.1
      }
      result +:= (x,array)
    })
    sc.parallelize(result)
  }

  def recommendArticles(number: Int, model: ALSModel,ratings : RDD[Rating]) : RDD[(Int,Array[Int])] = {
    var a = vectorsToBlockMatrix(model.userFactors)
    var b = vectorsToBlockMatrix(model.articleFactors).transpose

    var c = a.multiply(b)
    // Remove already rated
    val ratingsMatrix : BlockMatrix = new CoordinateMatrix(ratings.map(a=> MatrixEntry(a.user,a.article,Double.PositiveInfinity)),c.numRows(),c.numCols()).toBlockMatrix()
    c.subtract(ratingsMatrix)

    var lengths : RDD[Array[Double]] = c.toIndexedRowMatrix().rows.map(a=> a.vector.toArray)

    lengths.map(a=>getLargestN(a.zipWithIndex,number)).zipWithIndex().map(a=>(a._2.toInt,a._1))
  }

  def recommendArticlesForNewUsers(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int, regularization: Double, model: ALSModel,number :Int): RDD[(Int, Array[Int])] = {
    // Implements ALS fold-in to compute user factors and recommendations for new user ratings.

    // Initialize User Factors
    var userFactors: RDD[(Int, Array[Double])] = null
    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = alsStep(ratings, numLatentFactors, regularization, model.articleFactors, true)
    }
    var newModel = ALSModel(userFactors, model.articleFactors)
    // Recommend Articles
    recommendArticles(number, newModel,ratings)
  }

  def getLargestN(array: Array[(Double,Int)],number:Int) : Array[Int] = {
    var buffer : Array[(Double,Int)] = Array()
    (0 until number).foreach(a => buffer +:= (0.0,0))
    array.foreach(a=> buffer = buffer.map(b=> if(b._1 < a._1) {(a._1,a._2)}else{(b._1,b._2)}) )
    buffer.map(a=>a._2)
  }

  def vectorsToBlockMatrix(array : RDD[(Int,Array[Double])]) : BlockMatrix = {
    val entries = sc.parallelize(array.zipWithIndex.map({case (a:(Int,Array[Double]),x:Long) => a._2.zipWithIndex.map({ case (e:Double,y:Int) => MatrixEntry(x,y,e)})}).reduce(_++_))
    val coordMat : CoordinateMatrix = new CoordinateMatrix(entries)
    coordMat.toBlockMatrix()
  }

}
