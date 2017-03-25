package recommender

import breeze.linalg._
import breeze.optimize.linear.PowerMethod.BDM
import com.mongodb.spark.MongoSpark
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry, _}
import org.apache.spark.sql.{DataFrame, SparkSession,SaveMode}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector, Matrices, Vector}


case class ALSModel (userFactors: RDD[(Long,Array[Double])], articleFactors: RDD[(Long,Array[Double])])
case class Rating (user:Long, article:Long, rating:Double)

object BatchRecommender extends App{
  var sc : SparkContext = _

  override
  def main(args: Array[String]) = {
    val sparkAddress = sys.env.get("SPARK_ADDRESS").getOrElse("localhost")
    val sparkPort = sys.env.get("SPARK_PORT").getOrElse("7077")

    val dbAddress = sys.env.get("MONGO_ADDRESS").getOrElse("localhost")
    val dbPort = sys.env.get("MONGO_PORT").getOrElse("27017").toLong
    val dbKeySpace = sys.env.get("MONGO_KEYSPACE").getOrElse("newsForYou")

    val useDummyDataOpt = sys.env.get("USE_DUMMY_DATA")

    var sparkUrl = "spark://"+sparkAddress+":"+sparkPort

    val mongoUrl = "mongodb://"+dbAddress+":"+dbPort+"/"+dbKeySpace

    println("Spark expected at: " + sparkUrl)
    println("Mongo expected at: " + mongoUrl)


    val ss = SparkSession
      .builder()
      .master(sparkUrl)
      .appName("recommender")
      .config("spark.mongodb.input.uri", mongoUrl+".ratings")
      .config("spark.mongodb.output.uri", mongoUrl+".recommendations")
      .getOrCreate()
    sc = ss.sparkContext
    sc.setLogLevel("ERROR")

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
      ratingsRDD = temp.map(row => Rating(row.getLong(0), row.getLong(1), row.getDouble(2)))
    } else {
      // Load Random Rating Data
      println("Generating dummy rating data")
      var ratings : Array[Rating] = Array()
      for( user <- 0 to 300-1){
        val r2 = scala.util.Random
        if(r2.nextInt(100) >= 80) {
        for( article <- 0 to 100-1){
          val r = scala.util.Random
          if(r.nextInt(100) >= 98) {
            ratings +:= Rating(user,article,r.nextInt(10)*.1)
          }
        }
        }
      }

      ratingsRDD = sc.parallelize(ratings)
    }

    // Learn Model
    val numIterations = 12
    val numLatentFactors = 35
    val numArticles = ratingsRDD.groupBy(_.article).map(a=>a._1).collect()
    val regularization = 0.1
    val numPredictions = 10
    val model : ALSModel = learnModel(ratingsRDD,numIterations,numLatentFactors,numArticles,regularization)

    // Generate recommendations
    val recommendations = recommendArticles(numPredictions, model,ratingsRDD)

    // Store recommendations
    storeRecommendations(ss,recommendations,false)

    // Store model in DB
    storeFactorsInDB(model.articleFactors,ss,mongoUrl)

    sc.stop()
  }

  def learnModel(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int, numArticles : Array[Long], regularization: Double): ALSModel = {
    // Initialize User and Article Factors
    var userFactors: RDD[(Long, Array[Double])] = null
    var articleFactors: RDD[(Long, Array[Double])] = initialize(numArticles, numLatentFactors)

    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = alsStep(ratings, numLatentFactors, regularization, articleFactors, false)
      articleFactors = alsStep(ratings,  numLatentFactors,  regularization, userFactors, true)
    }

    ALSModel(userFactors, articleFactors)
  }

  def alsStep(ratings: RDD[Rating],  numLatentFactors : Int, regularization: Double, factors:RDD[(Long, Array[Double])], firstStage:Boolean) : RDD[(Long, Array[Double])] = {
    var ratingsBy : RDD[(Long, Rating)] = null
    if(firstStage){
      ratingsBy = ratings.keyBy(_.user)
    }else{
      ratingsBy = ratings.keyBy(_.article)
    }
    val ratingsWithFactors = factors.join(ratingsBy)

    val sumsSelfPerUser = dotSelfTransposeSelf(ratingsWithFactors, !firstStage)
    var right = dotSelfTransposeRatings(ratingsWithFactors, !firstStage)

    val toloop : RDD[(Long,( Array[Array[Double]], Array[Double] ))] = sumsSelfPerUser.join(right)

    var identMatrix : Array[Array[Double]] = identity(numLatentFactors, regularization)

    toloop.map(a=> (a._1, inverse(add(identMatrix,a._2._1)).multiply(toVector(a._2._2)).values ))
  }

  def toVector(array: Array[Double]) : DenseVector = {
    new DenseVector(array)
  }

  def add(a1: Array[Array[Double]], a2: Array[Array[Double]]) : DenseMatrix = {
    var numRows  = a1.length
    var numCols  = a1.length

    var a11 : Array[Double] = a1.reduce(_++_)
    var a22 : Array[Double] = a2.reduce(_++_)

    var values : Array[Double] = Array()

    for(i <- 0 to a11.length-1){
      values +:= a11(i) + a22(i)
    }

    new DenseMatrix(numRows,numCols,values)
  }

  def dotSelfTransposeRatings(factors: RDD[(Long,(Array[Double],Rating) )],firstStage:Boolean) : RDD[(Long,Array[Double])] = {

    var individualDotProducts : RDD[(Long,(Long,Array[Double]))] = null
    if(firstStage){
      individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => b * a._2._2.rating })) }).keyBy(_._1)
    }else{
      individualDotProducts = factors.map({ a => (a._2._2.article, a._2._1.map({ b => b * a._2._2.rating })) }).keyBy(_._1)
    }

    individualDotProducts.reduceByKey((a,b) => (a._1, (a._2, b._2)
      .zipped.map(_ + _)))
      .map(a=>a._2)
  }

  def storeRecommendations(ss: SparkSession,recommendations : RDD[(Long,Array[Long])],append:Boolean) = {
    val df : DataFrame =  ss.createDataFrame( recommendations )
    val lpDF = df.withColumnRenamed("_1", "userid").withColumnRenamed("_2", "recommendations")
    lpDF.printSchema()
    var a = MongoSpark.write(lpDF).option("collection", "recommendations")
    if(append){
      a = a.mode(SaveMode.Append)
    }else{
      a = a.mode(SaveMode.Overwrite)
    }
    a.save()
  }

  def dotSelfTransposeSelf(factors : RDD[(Long,(Array[Double],Rating) )], firstStage:Boolean) : RDD[(Long,Array[Array[Double]])] = {
    var individualDotProducts :RDD[(Long,(Long,Array[Array[Double]]))]= null
    if(firstStage){
      individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => a._2._1.map({ c => c * b }) })) }).keyBy(_._1)
    }else{
      individualDotProducts = factors.map({ a => (a._2._2.article, a._2._1.map({ b => a._2._1.map({ c => c * b }) })) }).keyBy(_._1)
    }

    individualDotProducts.reduceByKey((a,b) => (a._1, (a._2, b._2)
      .zipped.map((c: Array[Double], d: Array[Double]) => (c, d)
      .zipped.map(_ + _)) ))
      .map(a=>a._2)
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

  def initialize( numArticles : Array[Long], numLatentFactors : Int) : RDD[(Long, Array[Double])] = {
    var result: Array[(Long, Array[Double])] = Array()
    (numArticles).foreach( x => {
      var array : Array[Double] = Array()
      for (y <- 0 until numLatentFactors) {
        val r = scala.util.Random
        array +:= r.nextInt(10)*.1
      }
      result +:= (x,array)
    })
    sc.parallelize(result)
  }

  def recommendArticles(number: Int, model: ALSModel,ratings : RDD[Rating]) : RDD[(Long,Array[Long])] = {
    var a = vectorsToBlockMatrix(model.userFactors)
    var b = vectorsToBlockMatrix(model.articleFactors).transpose

    var c = a.multiply(b)

    // Remove already rated
    val ratingsMatrix : BlockMatrix = new CoordinateMatrix(ratings.map(a=> MatrixEntry(a.user,a.article,Double.PositiveInfinity)),c.numRows(),c.numCols()).toBlockMatrix()

    c = c.subtract(ratingsMatrix)

    var lengths : RDD[Array[Double]] = c.toIndexedRowMatrix().rows.map(a=> a.vector.toArray)

    lengths.map(a=>getLargestN(a.zipWithIndex.map(a=>(a._1,a._2.toLong)),number)).zipWithIndex().map(a=>(a._2,a._1)).filter(a=>a._2(0) >= 0)
  }

  def getLargestN(array: Array[(Double,Long)],number:Int) : Array[Long] = {
    var buffer : Array[(Double,Long)] = Array()
    (0 until number).foreach(a => buffer +:= (0.0,-1L))

    array.foreach(a => buffer = genBuffer(buffer,a) )
    buffer.map(a=>a._2)
  }

  def genBuffer(buffer : Array[(Double,Long)],a:(Double,Long)): Array[(Double,Long)] = {
    var newBuffer = buffer
    for(i <- 0 until buffer.length){
      if((buffer.length-1 == i && buffer(i)._1 < a._1) || (buffer.length-1 != i && buffer(i)._1 < a._1 && buffer(i+1)._1 >= a._1)) {
        newBuffer.update(i,(a._1,a._2))
      }
    }
    newBuffer
  }

  def vectorsToBlockMatrix(array : RDD[(Long,Array[Double])]) : BlockMatrix = {
    val entries  = sc.parallelize(array.map(a=> a._2.zipWithIndex.map(b=> MatrixEntry(a._1,b._2,b._1))).reduce(_++_))

    val rows = array.keyBy(_._1).map(_._1).max()+1
    val cols = array.collect()(0)._2.length

    val coordMat : CoordinateMatrix = new CoordinateMatrix(entries,rows,cols)
    coordMat.toBlockMatrix()
  }

  def storeFactorsInDB(factors:RDD[(Long,Array[Double])],ss: SparkSession,mongoUrl:String): Unit = {
    ss.conf.set("spark.mongodb.output.uri", mongoUrl+".articleFactors")
    val sc = ss.sparkContext

    val df : DataFrame =  ss.createDataFrame( factors )
    val lpDF = df.withColumnRenamed("_1", "articleId").withColumnRenamed("_2", "latentFactors")
    lpDF.printSchema()
    var a = MongoSpark.write(lpDF).option("collection", "articleFactors")
    a = a.mode(SaveMode.Overwrite)

    a.save()
  }

}
