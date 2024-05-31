import sbtassembly.{MergeStrategy, PathList}

object Merging {
  def customMergeStrategy(oldStrategy: (String) => MergeStrategy): (String => MergeStrategy) = {
    case PathList("org", "joda", "time", "base", "BaseDateTime.class") => MergeStrategy.first
    case PathList("io", "sundr", _ @_*) => MergeStrategy.first
    case PathList("javax", "activation", _ @_*) => MergeStrategy.first
    case PathList("javax", "xml", _ @_*) => MergeStrategy.first
    case PathList("google", "protobuf", _ @_*) => MergeStrategy.first
    case PathList("org", "bouncycastle", _ @_*) => MergeStrategy.first
    case x if x.endsWith("/ModuleUtil.class") => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.first
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case PathList("META-INF", "kotlin-result.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "kotlin-stdlib.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "kotlin-stdlib-common.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "okio.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "OSGI-INF", "MANIFEST.MF") => MergeStrategy.first
    case PathList("mozilla", "public-suffix-list.txt") => MergeStrategy.first
    case "module-info.class" =>
      MergeStrategy.discard
    case x if x.endsWith("arrow-git.properties") => MergeStrategy.concat
    case "logback.xml" => MergeStrategy.first
    case PathList("META-INF", "spring-configuration-metadata.json") => MergeStrategy.discard // don't need no stinkin' spring
    case x => oldStrategy(x)
  }
}
