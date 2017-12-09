import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy):(String => MergeStrategy) = {
    case PathList("org", "joda", "time", xs @ _*) => MergeStrategy.first
    case x => oldStrategy(x)
  }
}
