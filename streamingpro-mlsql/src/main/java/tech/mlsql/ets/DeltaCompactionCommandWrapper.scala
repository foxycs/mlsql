package tech.mlsql.ets

import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.delta.actions.CommitInfo
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.mlsql.session.MLSQLException
import org.apache.spark.sql.{DataFrame, SparkSession}
import streaming.common.{JSONTool, PathFun}
import streaming.dsl.mmlib.SQLAlg
import streaming.dsl.mmlib.algs.Functions
import streaming.dsl.mmlib.algs.param.{BaseParams, WowParams}

/**
  * 2019-06-06 WilliamZhu(allwefantasy@gmail.com)
  */
class DeltaCompactionCommandWrapper(override val uid: String) extends SQLAlg with Functions with WowParams {
  def this() = this(BaseParams.randomUID())


  override def batchPredict(df: DataFrame, path: String, params: Map[String, String]): DataFrame = train(df, path, params)

  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {
    val spark = df.sparkSession
    import spark.implicits._

    // !delta compact /data/table1 20 1 in background
    val command = JSONTool.parseJson[List[String]](params("parameters"))
    command match {
      case Seq("compact", dataPath, version, numFile, _*) =>
        val code =
          s"""
             |run command as DeltaCompactionCommand.`${dataPath}`
             |where compactVersion="${version}"
             |and compactRetryTimesForLock="10"
             |and compactNumFilePerDir="${numFile}"
             |and background="false"
             |;
      """.stripMargin

        val runInBackGround = command.last == "background"
        if (runInBackGround) {
          ScriptRunner.runAsync(
            code, Option(spark), false, false)
        } else {
          ScriptRunner.run(
            code, Option(spark), true, false)
        }

        val msg = if (runInBackGround) s"Compact ${path} in background" else s"Compact ${path} successfully"
        spark.createDataset[String](Seq(msg)).toDF("value")

      case Seq("history", dataPath, _*) =>
        val deltaLog = DeltaLog.forTable(spark, PathFun(path).add(dataPath).toPath)
        val history = deltaLog.history.getHistory(Option(1000))
        spark.createDataset[CommitInfo](history).toDF()

      case Seq("help", _ *) =>
        spark.createDataset[String](Seq(
          """
            |!delta compact [tablePath] [compactVersion] [compactNumFilePerDir] [in background];
            |
            |`in background` is optional, and the other parameters is required.
            |
            |!delta history [tablePath];
          """.stripMargin)).toDF("value")
      case _ => throw new MLSQLException(
        """
          |please use `!delta help;` to get the usage.
        """.stripMargin)
    }


  }


  override def skipPathPrefix: Boolean = false

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = {
    throw new MLSQLException(s"${getClass.getName} not support register ")
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    throw new MLSQLException(s"${getClass.getName} not support register ")
  }


}

