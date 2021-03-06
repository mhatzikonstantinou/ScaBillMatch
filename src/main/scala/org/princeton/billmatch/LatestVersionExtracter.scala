package org.princeton.billmatch

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object LatestVersionExtracter {

  val monthNameToNumber = Map(
    "January"   -> "01",
    "February"  -> "02",
    "March"     -> "03",
    "April"     -> "04",
    "May"       -> "05",
    "June"      -> "06",
    "July"      -> "07",
    "August"    -> "08",
    "September" -> "09",
    "October"   -> "10",
    "November"  -> "11",
    "December"  -> "12"
  )

  def zeroPrefixed(s: String) : String = {
     val l = s.length
     val result = l match {
        case 1 => "0"+s
        case other => s
     }
     result
  }

  def rtrim(s: String) = s.replaceAll(",$", "")

  def getTimestampString(s: String) : String = {
    try { 
      Array(monthNameToNumber(s.split(" ")(0)),zeroPrefixed(rtrim(s.split(" ")(1))),s.split(" ")(2)).mkString("-")
    } catch {
      case _ : Throwable => "12-31-1900"
    }
  }

  def getTimestampString_udf = udf(getTimestampString _)
  def comb = udf((s1: String, s2: String) => s1+"_"+s2)
  def getPK = udf((filePath: String, version: String) => Array(filePath.split("/")(1),filePath.split("/")(2),filePath.split("/")(3)).mkString("_"))
  def customPK = udf((primary_key: String) => primary_key.split("_").slice(0,3).mkString("_"))
  def getTimestamp = to_timestamp(col("timestamp_string"), "MM-dd-yyyy")

  def getLatest(sequence: Seq[String]) : String = {
   if (sequence.length == 1) {return "Introduced" }
   else if (sequence.contains("Enacted")) { return "Enacted" }
   else if (sequence.contains("Enrolled")) {return "Enrolled"}
   else if (sequence.contains("Adopted")) {return "Adopted"}
   else if (sequence.contains("Substituted")) {return "Substituted"}
   else if (sequence.contains("Amended")) {return "Amended"}
   else if (sequence.contains("Reintroduced")) {return "Reintroduced"}
   else {return sequence.last}
  }

  def children(colnames: List[String], df: DataFrame) : Array[org.apache.spark.sql.Column] = {
    colnames.map(x => child(x,df)).reduce(_ ++ _)
  }

  def child(colname: String, df: DataFrame) : Array[org.apache.spark.sql.Column] = {
    val parent = df.schema.fields.filter(_.name == colname).head
    val fields = parent.dataType match {
      case x: StructType => x.fields
      case _ => Array.empty[StructField]
    }
    fields.map(x => col(s"$colname.${x.name}"))
  }

  def main (args: Array[String]) {

    val t0 = System.nanoTime()
    val spark = SparkSession
      .builder()
      .appName("LatestVersionExtracter")
      .config("spark.shuffle.service.enabled","true")
      .getOrCreate()

    import spark.implicits._

    val data = spark.read.json("/user/alexeys/metadata").select("filePath","versionDate","version").as[Metadata]
    val data_w_timestamps = data.withColumn("timestamp_string",getTimestampString_udf(col("versionDate"))).withColumn("timestamp", getTimestamp)
    val ready_to_join = data_w_timestamps.withColumn("primary_key_to_remove",getPK($"filePath",$"version")).select("primary_key_to_remove","version","timestamp").as[(String,String,java.sql.Timestamp)]
    val results = ready_to_join.groupByKey(_._1).mapGroups((id,iterator)=>(id,iterator.toList.sortWith(_._3.getTime < _._3.getTime).map(_._2))).map{case (x,y) => (x,getLatest(y))}.withColumn("combined",comb(col("_1"),col("_2"))).as[(String,String,String)]

    //get raw data in the current format
    val raw = spark.read.json("file:///scratch/network/alexeys/bills/lexs/bills_combined_50_p*.json").withColumn("customPK",customPK(col("primary_key"))).as[RawFormat]

    var output = raw.joinWith(results,raw.col("primary_key") === results.col("combined")).toDF()
    output = output.select(children(List("_1","_2"), output): _*).select(col("content"),col("docid"),col("length"),col("primary_key"),col("state"),col("year"),col("customPK"),col("_2").alias("docversion")).dropDuplicates("customPK").drop("customPK")

    //for (d <- results.take(10)) {
    //    println(d)
    //}
    output.write.json("/user/alexeys/bills_combined_raw_with_latest_50p1p2") 
    val t1 = System.nanoTime()
    println("Elapsed time: " + (t1 - t0)/1000000000 + "s")

  }
} 
case class Metadata(filePath: String,versionDate: String, version: String)
case class RawFormat(content: String, docid: String, docversion: String, length: Long, primary_key: String, state: Long, year: Long, customPK: String)
