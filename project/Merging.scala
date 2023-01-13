import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy): (String => MergeStrategy) = {
    case PathList("org", "joda", "time", "base", "BaseDateTime.class") => MergeStrategy.first
    case PathList("io", "sundr", _ @_*) => MergeStrategy.first
    case PathList("javax", "activation", _ @_*) => MergeStrategy.first
    case PathList("javax", "xml", _ @_*) => MergeStrategy.first
    case PathList("google", "protobuf", _ @_*) => MergeStrategy.first
    case x if x.endsWith("/ModuleUtil.class") => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.first
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case PathList("META-INF", "kotlin-result.kotlin_module") => MergeStrategy.first
    case PathList("mozilla", "public-suffix-list.txt") => MergeStrategy.first
    case "module-info.class" =>
      MergeStrategy.discard
    case x => oldStrategy(x)
  }
}
