package com.revature

import org.apache.spark.sql.SparkSession
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.auth.BasicAWSCredentials
import org.apache.spark.SparkContext
import org.apache.spark.sql.Row
import org.apache.spark.sql.functions._
import org.apache.spark.sql.functions
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.DataFrame
import scala.io.Source
import java.nio.file.{Paths, Files}
import java.io.PrintWriter

object Runner {
  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder()
      .appName("q1_Population_TechAd")
      // .master("local[4]")
      .getOrCreate()

    // Reference: https://sparkbyexamples.com/spark/spark-read-text-file-from-s3/#s3-dependency
    val key = System.getenv(("AWSAccessKeyId"))
    val secret = System.getenv(("AWSSecretKey"))

    spark.sparkContext.hadoopConfiguration.set("fs.s3.access.key", key)
    spark.sparkContext.hadoopConfiguration.set("fs.s3.secret.key", secret)
    spark.sparkContext.hadoopConfiguration
      .set("fs.s3.endpoint", "s3.amazonaws.com")

    import spark.implicits._
    spark.sparkContext.setLogLevel("WARN")

    // Tech ads proportional to population- Where do we see relatively fewer tech ads proportional to population?

    // Read in a Census CSV gathered from https://data.census.gov/cedsci/table?q=dp05&g=0100000US.04000.001&y=2019&tid=ACSDT1Y2019.B01003&moe=false&hidePreview=true
    // This CSV was of 2019 1 year estimate for population in every state

    val censusData = spark.read
      .format("csv")
      .option("header", "true")
      .load("ACSDT1Y2019.B01003_data_with_overlays_2021-02-23T105100.csv")

    // Created a State Code list for easier joining with additional warc data.
    val rawStateList = Seq(
      ("AL", "Alabama"),
      ("AK", "Alaska"),
      ("AZ", "Arizona"),
      ("AR", "Arkansas"),
      ("CA", "California"),
      ("CO", "Colorado"),
      ("CT", "Connecticut"),
      ("DE", "Delaware"),
      ("DC", "District of Columbia"),
      ("FL", "Florida"),
      ("GA", "Georgia"),
      ("HI", "Hawaii"),
      ("ID", "Idaho"),
      ("IL", "Illinois"),
      ("IN", "Indiana"),
      ("IA", "Iowa"),
      ("KS", "Kansas"),
      ("KY", "Kentucky"),
      ("LA", "Louisiana"),
      ("ME", "Maine"),
      ("MD", "Maryland"),
      ("MA", "Massachusetts"),
      ("MI", "Michigan"),
      ("MN", "Minnesota"),
      ("MS", "Mississippi"),
      ("MO", "Missouri"),
      ("MT", "Montana"),
      ("NE", "Nebraska"),
      ("NV", "Nevada"),
      ("NH", "New Hampshire"),
      ("NJ", "New Jersey"),
      ("NM", "New Mexico"),
      ("NY", "New York"),
      ("NC", "North Carolina"),
      ("ND", "North Dakota"),
      ("OH", "Ohio"),
      ("OK", "Oklahoma"),
      ("OR", "Oregon"),
      ("PA", "Pennsylvania"),
      ("RI", "Rhode Island"),
      ("SC", "South Carolina"),
      ("SD", "South Dakota"),
      ("TN", "Tennessee"),
      ("TX", "Texas"),
      ("UT", "Utah"),
      ("VT", "Vermont"),
      ("VA", "Virginia"),
      ("WA", "Washington"),
      ("WV", "West Virginia"),
      ("WI", "Wisconsin"),
      ("WY", "Wyoming")
    )

    val stateList = rawStateList.toDF("State Code", "State Name")

    // Combined the two dataFrames to get state codes assocaited with area name.

    val combinedCensusData =
      censusData.join(stateList, $"Geographic Area Name" === $"State Name")

//Get a years worth of 2020 segment paths to spark.read for data
    //Read a whole years worth of data - Jan-Dec 2020
    // val ob1 = Source
    //   .fromFile("csv/part-00000-ba7d968d-cce3-4697-bc0c-037e17afb098-c000.csv")
    //   .getLines()
    //   .map("s3://commoncrawl/" + _)
    //   .mkString("\n")
    // val ob2 = Source
    //   .fromFile("csv/part-00001-ba7d968d-cce3-4697-bc0c-037e17afb098-c000.csv")
    //   .getLines()
    //   .map("s3://commoncrawl/" + _)
    //   .mkString("\n")
    // val ob3 = Source
    //   .fromFile("csv/part-00002-ba7d968d-cce3-4697-bc0c-037e17afb098-c000.csv")
    //   .getLines()
    //   .map("s3://commoncrawl/" + _)
    //   .mkString("\n")

    // new PrintWriter("yearCsv"){write(ob1+ob2+ob3);close()}

    //Dataframe to store results.
    var storedDF = Seq
      .empty[(String, String, Int, Int)]
      .toDF(
        "State Code",
        "Geographic Area Name",
        "Tech Job Total",
        "Population Estimate Total"
      )

      //read each line, extract the data, filter for tech job ads, add to storedDF
    val bufferedSource = Source.fromFile("yearCsv.csv")
    for (line <- bufferedSource.getLines()) {
      val cc = spark.read
        .option("lineSep", "WARC/1.0")
        .text(line)
        .as[String]
        .map((str) => { str.substring(str.indexOf("\n") + 1) })
        .toDF("cut WET")

      val cuttingCrawl = cc
        .withColumn("_tmp", split($"cut WET", "\r\n\r\n"))
        .select(
          $"_tmp".getItem(0).as("WARC Header"),
          $"_tmp".getItem(1).as("Plain Text")
        )

      //find job/career/employment in urls
      val techJob = cuttingCrawl
        .filter(
          $"WARC Header" rlike ".*WARC-Target-URI:.*career.*" or ($"WARC Header" rlike ".*WARC-Target-URI:.*/job.*") or ($"WARC Header" rlike ".*WARC-Target-URI:.*employment.*")
        )
        //filter for tech ads
        .filter(
          $"Plain Text" rlike ".*Frontend.*" or ($"Plain Text" rlike ".*Backendend.*") or ($"Plain Text" rlike ".*Fullstack.*")
            or ($"Plain Text" rlike ".*Cybersecurity.*") or ($"Plain Text" rlike ".*Software.*") or ($"Plain Text" rlike ".*Computer.*")
        )

      // Turning Dataframe into RDD in order to get Key-Value pairs of occurrences of State Codes
      val sqlCrawl = techJob
        .select($"Plain Text")
        .as[String]
        .flatMap(line => line.split(" "))
        .rdd

      val rddCrawl = sqlCrawl
        .map(word => (word, 1))
        .filter({ case (key, value) => key.length < 3 })
        .reduceByKey(_ + _)
        .toDF("State Code", "Tech Job Total")

      // Join earlier combinedCensusData Dataframe to rddCrawl Dataframe in order to determine
      val combinedCrawl = rddCrawl
        .join(combinedCensusData, ("State Code"))
        .select(
          $"State Code",
          $"Geographic Area Name",
          $"Tech Job Total",
          $"Population Estimate Total"
        )

      storedDF = storedDF.union(combinedCrawl)
    }
    //combine and print results from a years worth of common crawl data
    storedDF
      .groupBy($"State Code", $"Population Estimate Total")
      .sum("Tech Job Total")
      .withColumn(
        "Tech Ads Proportional to Population",
        round(($"sum(Tech Job Total)" / $"Population Estimate Total" * 100), 8)
      )
      .show(102, false)
  }
}
