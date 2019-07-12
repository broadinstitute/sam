package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.newrelic.NewRelicMetrics
import spray.json.DefaultJsonProtocol._

case class MatchResult(matches: Boolean, mismatchReasons: Seq[String])

class NewRelicShadowResultReporter(val daoName: String, val newRelicMetrics: NewRelicMetrics) extends ShadowResultReporter with LazyLogging {
  private case class MismatchLogMessage(daoName: String, functionName: String, parameterNames: Array[String], parameterValues: Array[String], realResult: String, shadowResult: String, matchResult: MatchResult)
  private implicit val MatchResultFormat = jsonFormat2(MatchResult)
  private implicit val MismatchLogMessageFormat = jsonFormat7(MismatchLogMessage)

  /**
    * Called upon completion (not necessarily successful) of both real and shadow implementation.
    *
    * @param methodCallInfo
    * @param realTimedResult
    * @param shadowTimedResult
    * @tparam T
    * @return
    */
  override def reportResult[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): IO[Unit] = {
    for {
      matchResult <- IO(resultsMatch(realTimedResult.result, shadowTimedResult.result))
      matchString = if(matchResult.matches) "match" else "mismatch"
      _ <- newRelicMetrics.incrementCounterIO(s"${daoName}_${methodCallInfo.functionName}_$matchString")
      perfImprovement = (realTimedResult.time - shadowTimedResult.time).toMillis
      _ <- if (matchResult.matches) newRelicMetrics.gauge(s"${daoName}_${methodCallInfo.functionName}_perf", perfImprovement.toFloat)
           else logMismatch(methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T], matchResult)
    } yield ()
  }

  def logMismatch[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T], matchResult: MatchResult): IO[Unit] = IO {
    logger.info(createLogMessage(methodCallInfo, realTimedResult, shadowTimedResult, matchResult))
  }

  def createLogMessage[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T], matchResult: MatchResult): String = {
    import spray.json._
    val maxLogMessageSize = 1 << 19 // 1 MB
    val logMessage = MismatchLogMessage(
      daoName,
      methodCallInfo.functionName,
      methodCallInfo.parameterNames,
      methodCallInfo.parameterValues.map(_.toString),
      makeString(realTimedResult.result),
      makeString(shadowTimedResult.result),
      matchResult
    ).toJson.compactPrint

    val sizedLogMessage = if (logMessage.size > maxLogMessageSize) {
      logMessage.take(maxLogMessageSize) + "<truncated>"
    } else {
      logMessage
    }
    sizedLogMessage
  }

  def makeString(value: Any): String = {
    value match {
      case col: Traversable[_] => col.mkString(",")
      case _ => value.toString
    }
  }

  /**
    * Checks that both realResult and shadowResult match. Match is not strictly an equality check which makes this
    * complicated. In the case of collections order it not important. Type T can be a hierarchy of case classes
    * containing collections at any level or T can itself be a collection. This function unpacks collections and
    * case classes and recursively calls resultsMatch on each element.
    *
    * Failures are also handled special. In the case of WorkbenchExceptionWithErrorReport, status codes must be the same,
    * otherwise so long as both fail that is ok
    *
    * @param realResult
    * @param shadowResult
    * @tparam T
    * @return
    */
  def resultsMatch[T](realResult: Either[Throwable, T], shadowResult: Either[Throwable, T]): MatchResult = {
    (realResult, shadowResult) match {
      case (Left(realThrowable: WorkbenchExceptionWithErrorReport), Left(shadowThrowable: WorkbenchExceptionWithErrorReport)) =>
        // if they both failed with WorkbenchExceptionWithErrorReport make sure both have same status code
        createMatchResult(realThrowable.errorReport.statusCode, shadowThrowable.errorReport.statusCode, "unequal error report status codes")

      case (Left(realThrowable: WorkbenchExceptionWithErrorReport), Left(shadowThrowable)) =>
        // the real failure has a status code but the shadow does not
        MatchResult(false, Seq(s"real contained status code [${realThrowable.errorReport.statusCode}], shadow threw [${shadowThrowable.getClass.getName}] message [${shadowThrowable.getMessage}]"))

      case (Left(_), Left(_)) =>
        // both just plain old failures, that they both failed is enough
        MatchResult(true, Seq.empty)

      case (Right(realTraversable: Traversable[_]), Right(shadowTraversable: Traversable[_])) =>
        // both traversables, we don't care about order, we care that each item in real matches an item in shadow, that
        // there are the same number of occurrences, and both real and shadow have the same number of distinct items
        val real = realTraversable.groupBy(x => x).map { case (key, values) => (key, values.size) }
        val shadow = shadowTraversable.groupBy(x => x).map { case (key, values) => (key, values.size) }
        val sizeMatch = createMatchResult(real.size, shadow.size, s"unequal distinct item count, [${real.mkString(",")}] vs [${shadow.mkString(",")}]")
        val itemsMatch = real.keySet.map { realValue =>
          shadow.keySet.find(shadowValue => resultsMatch(Right(realValue), Right(shadowValue)).matches) match {
            case None => MatchResult(false, Seq(s"cannot find match for [$realValue] in [$shadowTraversable]"))
            case Some(shadowMatch) => createMatchResult(real(realValue), shadow(shadowMatch), s"unequal occurrences of [$realValue]")
          }
        }
        aggregateMatchResults(itemsMatch.toSeq :+ sizeMatch)

      case (Right(realCaseClass: Product), Right(shadowCaseClass: Product)) =>
        // both are case classes, iterate through all case class fields and recursively call resultsMatch
        // I don't think a class mismatch is possible, but just in case let's check
        val classMatch = createMatchResult(realCaseClass.getClass, shadowCaseClass.getClass, "class mismatch")
        val matchResults = realCaseClass.productIterator.zip(shadowCaseClass.productIterator).map { case (realPart, shadowPart) =>
          resultsMatch(Right(realPart), Right(shadowPart))
        }.toSeq
        aggregateMatchResults(matchResults :+ classMatch)

      case (Right(realValue), Right(shadowValue)) => createMatchResult(realValue, shadowValue, "values unequal")

      case (_, _) =>
        // either real or shadow failed but not both
        MatchResult(false, Seq(s"real and shadow are not comparable: real [$realResult], shadow [$shadowResult]"))
    }
  }

  // checkName is passed by-name to avoid unnecessary toString and string concatenation when items match
  private def createMatchResult[T](real: T, shadow: T, checkName: => String): MatchResult = {
    val matches = real == shadow
    val reason = if (matches) {
      Seq.empty
    } else {
      Seq(s"$checkName: real [$real], shadow [$shadow]")
    }
    MatchResult(matches, reason)
  }

  private def aggregateMatchResults(matchResults: Seq[MatchResult]): MatchResult = {
    val mismatchReasons = matchResults.collect {
      case r if !r.matches => r.mismatchReasons
    }.flatten
    MatchResult(mismatchReasons.isEmpty, mismatchReasons)
  }
}
