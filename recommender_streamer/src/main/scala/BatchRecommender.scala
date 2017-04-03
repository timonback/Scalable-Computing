package recommender

import breeze.linalg._
import com.mongodb.spark.MongoSpark
import com.mongodb.spark.sql.fieldTypes.ObjectId
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.distributed.{BlockMatrix, CoordinateMatrix, MatrixEntry}
import org.apache.spark.mllib.linalg.{DenseMatrix, DenseVector}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

case class ALSModel(userFactors: RDD[(Long, Seq[Double])], articleFactors: RDD[(Long, Seq[Double])])

case class Rating(user: Long, article: Long, rating: Double)

object BatchRecommender {
  var sc: SparkContext = _

  def alsStep(ratings: RDD[Rating], numLatentFactors: Int, regularization: Double, factors: RDD[(Long, Seq[Double])]): RDD[(Long, Seq[Double])] = {
    var ratingsBy: RDD[(Long, Rating)] = null
    
    ratingsBy = ratings.keyBy(_.article)
    
    val ratingsWithFactors = factors.join(ratingsBy)

    val sumsSelfPerUser = dotSelfTransposeSelf(ratingsWithFactors)
    var right = dotSelfTransposeRatings(ratingsWithFactors)

    val toloop: RDD[(Long, (Seq[Seq[Double]], Seq[Double]))] = sumsSelfPerUser.join(right)

    var identMatrix: Seq[Seq[Double]] = identity(numLatentFactors, regularization)

    toloop.map(a => (a._1, inverse(add(identMatrix, a._2._1)).multiply(toVector(a._2._2)).values))
  }

  def toVector(array: Seq[Double]): DenseVector = {
    new DenseVector(array.toArray)
  }

  def add(a1: Seq[Seq[Double]], a2: Seq[Seq[Double]]): DenseMatrix = {
    var numRows = a1.length
    var numCols = a1.length

    var a11: Seq[Double] = a1.reduce(_ ++ _)
    var a22: Seq[Double] = a2.reduce(_ ++ _)

    var values: Array[Double] = Array()

    for (i <- 0 to a11.length - 1) {
      values +:= a11(i) + a22(i)
    }

    new DenseMatrix(numRows, numCols, values)
  }

  def dotSelfTransposeRatings(factors: RDD[(Long, (Seq[Double], Rating))]): RDD[(Long, Seq[Double])] = {

    var individualDotProducts: RDD[(Long, (Long, Seq[Double]))] = null
    individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => b * a._2._2.rating })) }).keyBy(_._1)
 
    individualDotProducts.reduceByKey((a, b) => (a._1, (a._2, b._2)
      .zipped.map(_ + _)))
      .map(a => a._2)
  }

  def storeRecommendations(objId: ObjectId, ss: SparkSession, recommendations: RDD[(Long, Array[Long])]) = {
    val content = recommendations.first()
    val newDocs = Seq((objId, content._1, content._2))
    val df: DataFrame = ss.createDataFrame(newDocs)
    val lpDF = df.withColumnRenamed("_1", "_id").withColumnRenamed("_2", "userid").withColumnRenamed("_3", "recommendations")
    MongoSpark.write(lpDF).option("collection", "recommendations").mode(SaveMode.Append).save()
  }

  def dotSelfTransposeSelf(factors: RDD[(Long, (Seq[Double], Rating))]): RDD[(Long, Seq[Seq[Double]])] = {
    var individualDotProducts: RDD[(Long, (Long, Seq[Seq[Double]]))] = null
    
    individualDotProducts = factors.map({ a => (a._2._2.user, a._2._1.map({ b => a._2._1.map({ c => c * b }) })) }).keyBy(_._1)
    
    individualDotProducts.reduceByKey((a, b) => (a._1, (a._2, b._2)
      .zipped.map((c: Seq[Double], d: Seq[Double]) => (c, d)
      .zipped.map(_ + _))))
      .map(a => a._2)
  }

  def identity(lf: Int, r: Double): Seq[Seq[Double]] = {
    var entries: Seq[Seq[Double]] = Seq()

    for (i <- 0 to lf - 1) {
      var row: Seq[Double] = Seq()
      for (j <- 0 to lf - 1) {
        if (i == j) {
          row +:= r
        } else {
          row +:= 0.0
        }
      }
      entries +:= row
    }
    entries
  }

  def inverse(mat: DenseMatrix): DenseMatrix = {
    var dm = new breeze.linalg.DenseMatrix[Double](mat.numRows, mat.numCols, mat.values)
    dm = inv(dm)

    new DenseMatrix(dm.rows, dm.cols, dm.data)
  }

  def recommendArticles(number: Int, model: ALSModel, ratings: RDD[Rating]): RDD[(Long, Array[Long])] = {
    var a = vectorsToBlockMatrix(model.userFactors)
    var b = vectorsToBlockMatrix(model.articleFactors).transpose

    var c = a.multiply(b)

    // Remove already rated
    val ratingsMatrix: BlockMatrix = new CoordinateMatrix(ratings.map(a => MatrixEntry(a.user, a.article, Double.PositiveInfinity)), c.numRows(), c.numCols()).toBlockMatrix()

    c = c.subtract(ratingsMatrix)

    var lengths: RDD[Seq[Double]] = c.toIndexedRowMatrix().rows.map(a => a.vector.toArray)

    lengths.map(a => getLargestN(a.zipWithIndex.map(a => (a._1, a._2.toLong)), number)).zipWithIndex().map(a => (a._2, a._1)).filter(a => a._2.last >= 0)
  }

  def getLargestN(array: Seq[(Double, Long)], number: Int): Array[Long] = {
    var buffer: Array[(Double, Long)] = Array()
    (0 until number).foreach(a => buffer +:= (0.0, -1L))

    array.foreach(a => buffer = genBuffer(buffer, a))
    buffer.map(a => a._2)
  }

  def genBuffer(buffer: Array[(Double, Long)], a: (Double, Long)): Array[(Double, Long)] = {
    var newBuffer = buffer
    for (i <- 0 until buffer.length) {
      if ((buffer.length - 1 == i && buffer(i)._1 < a._1) || (buffer.length - 1 != i && buffer(i)._1 < a._1 && buffer(i + 1)._1 >= a._1)) {
        newBuffer.update(i, (a._1, a._2))
      }
    }
    newBuffer
  }

  def vectorsToBlockMatrix(array: RDD[(Long, Seq[Double])]): BlockMatrix = {
    val entries  = array.flatMap(a=> a._2.zipWithIndex.map(b=> MatrixEntry(a._1,b._2,b._1)))

    val rows = array.keyBy(_._1).map(_._1).max() + 1
    val cols = array.first()._2.length

    val coordMat: CoordinateMatrix = new CoordinateMatrix(entries, rows, cols)
    coordMat.toBlockMatrix()
  }

  def storeFactorsInDB(factors: RDD[(Long, Seq[Double])], ss: SparkSession, mongoUrl: String): Unit = {
    ss.conf.set("spark.mongodb.output.uri", mongoUrl + ".articleFactors")
    val sc = ss.sparkContext

    val df: DataFrame = ss.createDataFrame(factors)
    val lpDF = df.withColumnRenamed("_1", "articleId").withColumnRenamed("_2", "latentFactors")
    var a = MongoSpark.write(lpDF).option("collection", "articleFactors")
    a = a.mode(SaveMode.Overwrite)
    a.save()
  }
}
