package org.broadinstitute.dsde.workbench.sam

import scala.concurrent.duration._

package object util {
  val API_TIMING_DURATION_BUCKET: List[FiniteDuration] = List(10 millis, 100 millis, 500 millis, 1 second, 2 seconds, 5 seconds)

  /** Takes a list of pairs and returns a map grouped by the first element with values of lists of the second
    * @param list
    *   pairs to group by first element
    * @tparam X
    *   type of first element in pair
    * @tparam Y
    *   type of second element in pair
    * @return
    *   map grouped by first element
    */
  def groupByFirstInPair[X, Y](list: Iterable[(X, Y)]): Map[X, Iterable[Y]] =
    list.groupBy(_._1).map { case (key, pairs) => key -> pairs.map(_._2) }
}
