/*KMeansAnalyzer: an app. that performs document or section similarity searches starting off CartesianPairs

Following parameters need to be filled in the resources/kmeansAnalyzer.conf file:
    numTextFeatures: Number of text features to keep in hashingTF
    addNGramFeatures: Boolean flag to indicate whether to add n-gram features
    nGramGranularity: granularity of a rolling n-gram
    inputBillsFile: Bill input file, one JSON per line
    outputMainFile: 
*/

import com.typesafe.config._

import org.apache.spark.{SparkConf, SparkContext, SparkFiles}
import org.apache.spark.SparkContext._
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions._

import org.apache.spark.ml.feature.{HashingTF, IDF}
import org.apache.spark.ml.feature.{RegexTokenizer, Tokenizer}
import org.apache.spark.ml.feature.NGram
import org.apache.spark.ml.feature.StopWordsRemover

//import org.apache.spark.ml.tuning.{ParamGridBuilder, CrossValidator}

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._

import scala.collection.mutable.WrappedArray

import org.apache.spark.mllib.linalg.{DenseVector, SparseVector, Vector, Vectors}

import java.io._

import org.apache.spark.ml.clustering.KMeans
import org.apache.spark.mllib.linalg.Vectors


object KMeansAnalyzer {

  def converted(row: scala.collection.Seq[Any]) : Tuple2[String,SparseVector] = { 
    val ret = row.asInstanceOf[WrappedArray[Any]]
    val first = ret(0).asInstanceOf[String]
    val second = ret(1).asInstanceOf[Vector]
    Tuple2(first,second.toSparse)
  }

  //get type of var utility 
  def manOf[T: Manifest](t: T): Manifest[T] = manifest[T]

  def main(args: Array[String]) {

    println(s"\nExample submit command: spark-submit  --class KMeansAnalyzer --master yarn-client --num-executors 40 --executor-cores 3 --executor-memory 10g target/scala-2.10/BillAnalysis-assembly-1.0.jar\n")

    val t0 = System.nanoTime()

    val params = ConfigFactory.load("kmeansAnalyzer")
    run(params)

    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")
  }


  def customNPartitions(directory: File) : Int = {
      var len = 0.0
      val all: Array[File] = directory.listFiles()
      for (f <- all) {
        if (f.isFile())
            len = len + f.length()
        else
            len = len + customNPartitions(f)
      }
      //353 GB worked with 7000 partitions
      val npartitions = (7000.*(len/350000000000.)).toInt
      npartitions  
  }

  def appendFeature(a: WrappedArray[String], b: WrappedArray[String]) : WrappedArray[String] = {
     a ++ b
  }   

  def run(params: Config) {

    val conf = new SparkConf().setAppName("KMeansAnalyzer")
      .set("spark.dynamicAllocation.enabled","true")
      .set("spark.shuffle.service.enabled","true")

    val spark = new SparkContext(conf)
    val sqlContext = new org.apache.spark.sql.SQLContext(spark)
    import sqlContext.implicits._
    
    val vv: String = params.getString("kmeansAnalyzer.docVersion") //like "Enacted"
    val input = sqlContext.read.json(params.getString("kmeansAnalyzer.inputBillsFile")).filter($"docversion" === vv)
    val npartitions = (400*(input.count()/100000)).toInt

    val bills = input.repartition(Math.max(npartitions,200),col("primary_key"),col("content")) //.filter("docversion == Introduced")
    bills.explain

    def cleaner_udf = udf((s: String) => s.replaceAll("(\\d|,|:|;|\\?|!)", ""))
    val cleaned_df = bills.withColumn("cleaned",cleaner_udf(col("content"))).drop("content")

    //tokenizer = Tokenizer(inputCol="text", outputCol="words")
    var tokenizer = new RegexTokenizer().setInputCol("cleaned").setOutputCol("words").setPattern("\\W")
    val tokenized_df = tokenizer.transform(cleaned_df)

    //remove stopwords 
    var remover = new StopWordsRemover().setInputCol("words").setOutputCol("filtered")
    var prefeaturized_df = remover.transform(tokenized_df).drop("words")

    //if (params.getBoolean("kmeansAnalyzer.addNGramFeatures")) {
    //
    //   val ngram = new NGram().setN(params.getInt("kmeansAnalyzer.nGramGranularity")).setInputCol("filtered").setOutputCol("ngram")
    //   val ngram_df = ngram.transform(prefeaturized_df)
    //
    //   def appendFeature_udf = udf(appendFeature _)
    //   prefeaturized_df = ngram_df.withColumn("combined", appendFeature_udf(col("filtered"),col("ngram"))).drop("filtered").drop("ngram").drop("cleaned")
    //} else {
    prefeaturized_df = prefeaturized_df.select(col("primary_key"),col("filtered").alias("combined"))
    //}

    //hashing
    var hashingTF = new HashingTF().setInputCol("combined").setOutputCol("rawFeatures").setNumFeatures(params.getInt("kmeansAnalyzer.numTextFeatures"))
    val featurized_df = hashingTF.transform(prefeaturized_df)

    var idf = new IDF().setInputCol("rawFeatures").setOutputCol("features")
    //val Array(train, cv) = featurized_df.randomSplit(Array(0.7, 0.3))
    var idfModel = idf.fit(featurized_df)
    val rescaled_df = idfModel.transform(featurized_df).drop("rawFeatures")
    rescaled_df.printSchema()


    // Trains a k-means model
    /*
     setDefault(
    k -> 2,
    maxIter -> 20,
    initMode -> MLlibKMeans.K_MEANS_PARALLEL,
    initSteps -> 5,
    tol -> 1e-4)
    */
    val kval: Int = 150
    val kmeans = new KMeans().setK(kval).setMaxIter(40).setFeaturesCol("features").setPredictionCol("prediction")
    val model = kmeans.fit(rescaled_df)

    val clusters_df = model.transform(rescaled_df)

    // Shows the result
    clusters_df.show()
    clusters_df.printSchema()

    //println("Final Centers: ")
    //val bla = model.clusterCenters
    //for (b <- bla.take(10)) {
    //  println(b)
    //}

    val WSSSE = model.computeCost(rescaled_df)
    println("Within Set Sum of Squared Errors = " + WSSSE)
    //model.explainParams()

    val explained = model.extractParamMap()
    println(explained)

    //val hashed_bills = rescaled_df.select("primary_key","rawFeatures") //.rdd.map(row => converted(row.toSeq))
    //hashed_bills.printSchema()

    //FIXME save the dataframe with predicted labels if you need
    clusters_df.select("primary_key","prediction").write.format("parquet").save(params.getString("kmeansAnalyzer.outputMainFile"))

    spark.stop()
   }
}
