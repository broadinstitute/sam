package org.broadinstitute.dsde.workbench.sam

package object util {
  /**
    * Takes a list of pairs and returns a map grouped by the first element with values of lists of the second
    * @param list pairs to group by first element
    * @tparam X type of first element in pair
    * @tparam Y type of second element in pair
    * @return map grouped by first element
    */
  def groupByFirstInPair[X, Y](list: Iterable[(X, Y)]): Map[X, Iterable[Y]] = {
    list.groupBy(_._1).map { case (key, pairs) => key -> pairs.map(_._2) }
  }
}
