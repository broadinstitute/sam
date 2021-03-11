import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy):(String => MergeStrategy) = {
    case PathList("org", "joda", "time", "base", "BaseDateTime.class") => MergeStrategy.first
    case PathList("io", "sundr", _ @ _*) => MergeStrategy.first
    case PathList("google", "protobuf", _ @ _*) => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.first
    case "module-info.class" =>
      MergeStrategy.discard // JDK 11 does not use the file module-info.class so it is safe to discard the file.
    case x => oldStrategy(x)
  }
}
