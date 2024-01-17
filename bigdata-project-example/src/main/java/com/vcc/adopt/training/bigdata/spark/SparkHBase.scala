package com.vcc.adopt.training.bigdata.spark

import com.vcc.adopt.config.ConfigPropertiesLoader
import com.vcc.adopt.utils.hbase.HBaseConnectionFactory
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark.sql.types.{IntegerType, LongType, StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.functions._
import java.util


object SparkHBase {
  val spark: SparkSession = SparkSession.builder().getOrCreate()
  private val personInfoLogPath = ConfigPropertiesLoader.getYamlConfig.getProperty("personInfoLogPath")

  private def createDataFrameAndPutToHDFS(): Unit = {
    val data = Seq(
      Row(1, "Alice", 25),
      Row(2, "Bob", 30),
      Row(3, "Charlie", 22)
    )

    val schema = StructType(Seq(
      StructField("personId", LongType, nullable = true),
      StructField("name", StringType, nullable = true),
      StructField("age", IntegerType, nullable = true)
    ))

    val df = spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
    df.show()
    df.write
      .mode("overwrite")  // nếu file này đã tồn tại trước đó, sẽ ghi đè
      .parquet(personInfoLogPath)
  }

  private def readHDFSAndPutToHBase(): Unit = {
    var df = spark.read.parquet(personInfoLogPath)
    df = df
      .withColumn("country", lit("US"))
      .repartition(5)  // chia dataframe thành 5 phân vùng, mỗi phân vùng sẽ được chạy trên một worker (nếu không chia mặc định là 200)

    val batchPutSize = 100
    df.foreachPartition((rows: Iterator[Row]) => {
        val hbaseConnection = HBaseConnectionFactory.createConnection()
        try{
          val table = hbaseConnection.getTable(TableName.valueOf("person", "person_info"))
          val puts = new util.ArrayList[Put]()
          for (row <- rows) {
            val personId = row.getAs[Long]("personId")
            val name = row.getAs[String]("name")
            val age = row.getAs[Int]("age")
            val country = row.getAs[String]("country")

            val put = new Put(Bytes.toBytes(personId))
            put.addColumn(Bytes.toBytes("cf"), Bytes.toBytes("name"), Bytes.toBytes(name))
            put.addColumn(Bytes.toBytes("cf"), Bytes.toBytes("age"), Bytes.toBytes(age))
            put.addColumn(Bytes.toBytes("cf"), Bytes.toBytes("country"), Bytes.toBytes(country))
            puts.add(put)
            if (puts.size > batchPutSize) {
              table.put(puts)
              puts.clear()
            }
          }
          if (puts.size() > 0){
            table.put(puts)
          }
        }finally {
          hbaseConnection.close()
        }
      })
  }


  def main(args: Array[String]): Unit = {
    createDataFrameAndPutToHDFS()
    readHDFSAndPutToHBase()
  }
}