package recommender

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry, _}
import org.apache.spark.sql.SparkSession
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector, Vector}

case class ALSModel (userFactors: RDD[(Int,Array[Double])], articleFactors: RDD[(Int,Array[Double])])
case class Rating (user:Int, article:Int, rating:Double)

object Recommender extends App{
  var sc : SparkContext = _

  override
  def main(args: Array[String]) = {
    val ss = SparkSession
      .builder()
      .master("local")
      .appName("recommender")
      .config("spark.mongodb.input.uri", "mongodb://127.0.0.1:27017/recommender.likes")
      .config("spark.mongodb.output.uri", "mongodb://127.0.0.1:27017/recommender.recommendations")
      .getOrCreate()
    sc = ss.sparkContext

    // Load Rating Data
    var ratings : Array[Rating] = Array()
    for( user <- 0 to 20-1){
      for( article <- 0 to 10-1){
        val r = scala.util.Random
        if(r.nextInt(100) >= 90) {
          ratings +:= Rating(user,article,1.0)
        }
      }
    }
    val ratingsRDD = sc.parallelize(ratings)

    // Learn Model
    val model : ALSModel = learnModel(ratingsRDD,2,7,20,10,1.5)

    // Generate recommandations
    recommendArticles(10, model)

    // Generate recommendations for anonymous user
  //  recommendArticlesForNewUser(10,null, model)
  }

  def learnModel(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int,numUsers : Int,numArticles : Int, regularization: Double): ALSModel = {
    // Initialize User and Article Factors
    var userFactors: RDD[(Int, Array[Double])] = null
    var articleFactors: RDD[(Int, Array[Double])] = initialize(numArticles, numLatentFactors)

    // Learn Model
    for (i <- 0 until numIterations) {
      userFactors = alsStep(ratings, numIterations, numLatentFactors, regularization, articleFactors, true)
      articleFactors = alsStep(ratings, numIterations, numLatentFactors,  regularization, userFactors, false)
    }

    ALSModel(userFactors, articleFactors)
  }

    def alsStep(ratings: RDD[Rating], numIterations: Int, numLatentFactors : Int, regularization: Double, factors:RDD[(Int, Array[Double])], firstStage:Boolean) : RDD[(Int, Array[Double])] = {
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


  def inverse(matrix: DenseMatrix): DenseMatrix = {
    matrix // TODO
  }

  def initialize( number : Int, numLatentFactors : Int) : RDD[(Int, Array[Double])] = {
      var result: Array[(Int, Array[Double])] = Array()
      for (x <- 0 until number) {
        var array : Array[Double] = Array()
        for (y <- 0 until numLatentFactors) {
          val r = scala.util.Random
          array +:= r.nextInt(100)*.1
        }
        result +:= (x,array)
      }
      sc.parallelize(result)
  }

  def recommendArticles(number: Int, model: ALSModel) = {
    var a = vectorsToBlockMatrix(model.userFactors).transpose
    var b = vectorsToBlockMatrix(model.articleFactors)

    var c = a.multiply(b)

    var lengths = c.toIndexedRowMatrix().rows.map(a=> a.vector.toArray)

    lengths.foreach(a => a.foreach(println))
  }

  def recommendArticlesForNewUser(number: Int, userRatings: RDD[Rating], model: ALSModel): RDD[(Int, Array[Int])] = {
    null  // TODO
  }

  def vectorsToBlockMatrix(array : RDD[(Int,Array[Double])]) : BlockMatrix = {
    val entries = sc.parallelize(array.zipWithIndex.map({case (a:(Int,Array[Double]),x:Long) => a._2.zipWithIndex.map({ case (e:Double,y:Int) => MatrixEntry(x,y,e)})}).reduce(_++_))
    val coordMat : CoordinateMatrix = new CoordinateMatrix(entries)
    coordMat.toBlockMatrix()
  }

}