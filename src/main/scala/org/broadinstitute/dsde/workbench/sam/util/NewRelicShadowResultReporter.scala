package org.broadinstitute.dsde.workbench.sam.util

import cats.data.NonEmptyList
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
      _ <- newRelicMetrics.incrementCounterIO(s"${daoName}/${methodCallInfo.functionName}/$matchString")
      perfImprovement = (realTimedResult.time - shadowTimedResult.time).toMillis
      _ <- if (matchResult.matches) newRelicMetrics.gauge(s"${daoName}/${methodCallInfo.functionName}/perf", perfImprovement.toFloat)
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
      case col: Traversable[_] => s"[${col.size} items]"
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
        traversablesContainSameElements(realTraversable, shadowTraversable)

      case (Right(realNEL: NonEmptyList[_]), Right(shadowNEL: NonEmptyList[_])) =>
        traversablesContainSameElements(realNEL.toList, shadowNEL.toList)

      case (Right(realCaseClass: Product), Right(shadowCaseClass: Product)) =>
        caseClassesMatch(realCaseClass, shadowCaseClass)

      case (Right(realValue), Right(shadowValue)) => createMatchResult(realValue, shadowValue, "values unequal")

      case (_, _) =>
        // either real or shadow failed but not both
        MatchResult(false, Seq(s"real and shadow are not comparable: real [$realResult], shadow [$shadowResult]"))
    }
  }

  /**
    * Case classes may contain traversable elements and since a match does not care about order, this function
    * iterates through each field of the case class and calls resultsMatch recursively.
    * @param realCaseClass
    * @param shadowCaseClass
    * @return
    */
  private def caseClassesMatch(realCaseClass: Product, shadowCaseClass: Product): MatchResult = {
    // I don't think a class mismatch is possible, but just in case let's check
    val classMatch = createMatchResult(realCaseClass.getClass, shadowCaseClass.getClass, "class mismatch")
    val matchResults = realCaseClass.productIterator
      .zip(shadowCaseClass.productIterator)
      .map {
        case (realPart, shadowPart) =>
          resultsMatch(Right(realPart), Right(shadowPart))
      }
      .toSeq
    aggregateMatchResults(matchResults :+ classMatch)
  }

  /**
    * Check that 2 traversables have the same elements, not necessarily the same order. Each distinct element must
    * occur the same number of times in each traversable.
    *
    * @param realTraversable
    * @param shadowTraversable
    * @return
    */
  private def traversablesContainSameElements(realTraversable: Traversable[Any], shadowTraversable: Traversable[Any]): MatchResult = {
    if (realTraversable.size > 100) {
    // this is too big to do a full comparison, just match on size
    createMatchResult(realTraversable.size, shadowTraversable.size, "unequal size")
  } else {
      // one implementation would be to sort both taversables and compare pairwise but we don't have a sort order

      // this implementation groups by each distinct element to produce a map keyed by element with values of the occurrence count.
      // make sure both maps have the same size then check that each real element has a match in shadow and that the
      // number of occurrences for the shadow match is the same as real
      val realDistinctElementCountsByElement = realTraversable.groupBy(x => x).map { case (key, values) => (key, values.size) }
      val shadowDistinctElementCountsByElement = shadowTraversable.groupBy(x => x).map { case (key, values) => (key, values.size) }
      val sizeMatch = createMatchResult(
        realDistinctElementCountsByElement.size,
        shadowDistinctElementCountsByElement.size,
        s"unequal distinct item count, [${realDistinctElementCountsByElement.mkString(",")}] vs [${shadowDistinctElementCountsByElement.mkString(",")}]"
      )
      val elementsMatch = realDistinctElementCountsByElement.keySet.map { realElement =>
        shadowDistinctElementCountsByElement.keySet.find(shadowElement => resultsMatch(Right(realElement), Right(shadowElement)).matches) match {
          case None => MatchResult(false, Seq(s"cannot find match for [$realElement] in [$shadowTraversable]"))
          case Some(shadowMatch) =>
            val realOccurrences = realDistinctElementCountsByElement(realElement)
            val shadowOccurrences = shadowDistinctElementCountsByElement(shadowMatch)
            createMatchResult(realOccurrences, shadowOccurrences, s"unequal occurrences of [$realElement]")
        }
      }
      aggregateMatchResults(elementsMatch.toSeq :+ sizeMatch)
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
