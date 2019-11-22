package org.broadinstitute.dsde.workbench.sam.util

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, ValueObject, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.newrelic.NewRelicMetrics
import org.scalatest.{FlatSpec, Matchers}
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._

class NewRelicShadowResultReporterSpec extends FlatSpec with Matchers with MockitoSugar {

  private def createResultReporter = {
    val newRelicMetrics = mock[NewRelicMetrics]
    when(newRelicMetrics.gauge(any[String], any[Float])).thenReturn(IO.unit)
    when(newRelicMetrics.incrementCounterIO(any[String], any[Int])).thenReturn(IO.unit)
    new NewRelicShadowResultReporter("test", newRelicMetrics)
  }

  // match cases
  "NewRelicShadowResultReporter" should "match complex case class structure" in {
    val reporter = createResultReporter
    val probe = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    // copy probe to make sure not just testing reference equality
    val result = reporter.resultsMatch(Right(probe), Right(probe.copy()))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match primitive" in {
    val reporter = createResultReporter
    val probe = false
    val result = reporter.resultsMatch(Right(probe), Right(probe))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match collection" in {
    val reporter = createResultReporter
    val probe = Seq(3,2,5,6,2)
    val result = reporter.resultsMatch(Right(probe), Right(probe))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match collection independent of order" in {
    val reporter = createResultReporter
    val real = Seq(3,2,6,2,5,2)
    val shadow = Seq(3,2,5,6,2,2)
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match embedded collection independent of order" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfasdf", Vector(TestInnerCaseClass(MyValueObject("ppp"), 99), TestInnerCaseClass(MyValueObject("qqq"), 88)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match status codes" in {
    val reporter = createResultReporter
    val probe = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.ImATeapot, "")(ErrorReportSource("")))
    val result = reporter.resultsMatch(Left(probe), Left(probe))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match both failed but with any exception" in {
    val reporter = createResultReporter
    val real = new IllegalArgumentException()
    val shadow = new RuntimeException()
    val result = reporter.resultsMatch(Left(real), Left(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match dates that are close - shadow after" in {
    val reporter = createResultReporter
    val real = new Date()
    val shadow = new Date(real.getTime + 200000)
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  it should "match dates that are close - shadow before" in {
    val reporter = createResultReporter
    val real = new Date()
    val shadow = new Date(real.getTime - 200000)
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (true)
    }
  }

  //mismatch cases

  it should "detect mismatch in value object in collection in complex case class structure" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("cannot find match for [TestInnerCaseClass(qq,88)] in [List(TestInnerCaseClass(qqq,88), TestInnerCaseClass(ppp,99))]")
    }
  }

  it should "detect mismatch in case class in complex case class structure" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)), Some(TestInnerCaseClass(MyValueObject("oooo"), 77)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)), Some(TestInnerCaseClass(MyValueObject("ooo"), 77)))
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("values unequal: real [oooo], shadow [ooo]")
    }
  }

  it should "detect mismatch in primitive" in {
    val reporter = createResultReporter
    val real = 1
    val shadow = 2
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("values unequal: real [1], shadow [2]")
    }
  }

  it should "detect mismatch in scalar in complex case class structure" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfsdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("values unequal: real [asdfsdf], shadow [asdfasdf]")
    }
  }

  it should "detect multiple mismatch in complex case class structure" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfsdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 97)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("values unequal: real [asdfsdf], shadow [asdfasdf]", "cannot find match for [TestInnerCaseClass(ppp,97)] in [List(TestInnerCaseClass(qqq,88), TestInnerCaseClass(ppp,99))]")
    }
  }

  it should "detect mismatch in status codes" in {
    val reporter = createResultReporter
    val real = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.ImATeapot, "")(ErrorReportSource("")))
    val shadow = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.OK, "")(ErrorReportSource("")))
    val result = reporter.resultsMatch(Left(real), Left(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq(s"unequal error report status codes: real [Some(418 I'm a teapot)], shadow [Some(200 OK)]")
    }
  }

  it should "detect mismatch in dates" in {
    val reporter = createResultReporter
    val real = new Date()
    val shadow = new Date(real.getTime - 2000000)
    val result = reporter.resultsMatch(Right(real), Right(shadow))

    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons.exists(_.startsWith("dates more than 10 minutes apart")) should be (true)
    }
  }

  it should "detect missing status code in shadow" in {
    val reporter = createResultReporter
    val real = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.ImATeapot, "")(ErrorReportSource("")))
    val shadow = new RuntimeException("wrong")
    val result = reporter.resultsMatch(Left(real), Left(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq(s"real contained status code [Some(418 I'm a teapot)], shadow threw [java.lang.RuntimeException] message [wrong]")
    }
  }

  it should "detect mismatch in element occurrences" in {
    val reporter = createResultReporter
    val real = Seq(3,2,6,2,5,2,2,6)
    val shadow = Seq(3,2,5,6,2,2,5,3)
    val result = reporter.resultsMatch(Right(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("unequal occurrences of [2]: real [4], shadow [3]", "unequal occurrences of [5]: real [1], shadow [2]", "unequal occurrences of [3]: real [1], shadow [2]", "unequal occurrences of [6]: real [2], shadow [1]")
    }
  }

  it should "detect exception only in shadow" in {
    val reporter = createResultReporter
    val real = 1
    val shadow = new Exception("foo")
    val result = reporter.resultsMatch(Right(real), Left(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("real and shadow are not comparable: real [Right(1)], shadow [Left(java.lang.Exception: foo)]")
    }
  }

  it should "detect exception only in real" in {
    val reporter = createResultReporter
    val real = new Exception("foo")
    val shadow = 1
    val result = reporter.resultsMatch(Left(real), Right(shadow))
    withClue(result.mismatchReasons) {
      result.matches should be (false)
      result.mismatchReasons should contain theSameElementsAs Seq("real and shadow are not comparable: real [Left(java.lang.Exception: foo)], shadow [Right(1)]")
    }
  }

  it should "report match" in {
    val reporter = createResultReporter
    reporter.reportResult(MethodCallInfo("fxn", Array("param"), Array("arg")), TimedResult(Right(27), 100 seconds), TimedResult(Right(27), 10 seconds)).unsafeRunSync()

    verify(reporter.newRelicMetrics).gauge(s"${reporter.daoName}/fxn/perf", 90000f)
    verify(reporter.newRelicMetrics).incrementCounterIO(s"${reporter.daoName}/fxn/match")
  }

  it should "report mismatch" in {
    val reporter = createResultReporter
    reporter.reportResult(MethodCallInfo("fxn", Array("param"), Array("arg")), TimedResult(Right(20), 100 seconds), TimedResult(Right(27), 10 seconds)).unsafeRunSync()

    verify(reporter.newRelicMetrics).incrementCounterIO(s"${reporter.daoName}/fxn/mismatch")
  }

  it should "report complex mismatch" in {
    val reporter = createResultReporter
    val real = TestCaseClass("asdfsdf", Vector(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 97)))
    val shadow = TestCaseClass("asdfasdf", Seq(TestInnerCaseClass(MyValueObject("qqq"), 88), TestInnerCaseClass(MyValueObject("ppp"), 99)))
    reporter.reportResult(MethodCallInfo("fxn", Array("param"), Array("arg")), TimedResult(Right(real), 100 seconds), TimedResult(Right(shadow), 10 seconds)).unsafeRunSync()

    verify(reporter.newRelicMetrics).incrementCounterIO(s"${reporter.daoName}/fxn/mismatch")
  }

  it should "truncate too long message" in {
    val tooLog = Seq.fill(10000000)("a").mkString
    val reporter = createResultReporter
    val message = reporter.createLogMessage(MethodCallInfo("fxn", Array("param"), Array("arg")), TimedResult(Right(20), 100 seconds), TimedResult(Right(27), 10 seconds), MatchResult(false, Seq(tooLog)))
    message should endWith ("<truncated>")
  }
}

case class MyValueObject(value: String) extends ValueObject
case class TestInnerCaseClass(splat: MyValueObject, wombat: Int)
case class TestCaseClass(foo: String, bars: Seq[TestInnerCaseClass], single: Option[TestInnerCaseClass] = None)