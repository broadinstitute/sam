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
    case PathList("META-INF", "versions", "11", "module-info.class") => MergeStrategy.first
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case PathList("META-INF", "kotlin-result.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "kotlin-stdlib.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "kotlin-stdlib-common.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "okio.kotlin_module") => MergeStrategy.first
    case PathList("META-INF", "versions", "9", "OSGI-INF", "MANIFEST.MF") => MergeStrategy.first
    case PathList("META-INF", "license", "LICENSE.mvn-wrapper.txt") => MergeStrategy.first
    case PathList("mozilla", "public-suffix-list.txt") => MergeStrategy.first
    case "module-info.class" =>
      MergeStrategy.discard
    // for license/LICENSE.boringssl.txt merge error:
//        [error] Deduplicate found different file contents in the following:
//    [error]   Jar name = grpc-netty-shaded-1.69.0.jar, jar org = io.grpc, entry target = META-INF/license/LICENSE.boringssl.txt
//      [error]   Jar name = netty-incubator-codec-native-quic-0.0.70.Final.jar, jar org = io.netty.incubator, entry target = META-INF/license/LICENSE.boringssl.txt
//      [error]   Jar name = netty-codec-native-quic-4.2.1.Final.jar, jar org = io.netty, entry target = META-INF/license/LICENSE.boringssl.txt
//      [error]   Jar name = netty-tcnative-boringssl-static-2.0.70.Final.jar, jar org = io.netty, entry target = META-INF/license/LICENSE.boringssl.txt
    case "META-INF/license/LICENSE.boringssl.txt" => MergeStrategy.first
    case x if x.endsWith("arrow-git.properties") => MergeStrategy.concat
    case "logback.xml" => MergeStrategy.first
    case PathList("META-INF", "spring-configuration-metadata.json") => MergeStrategy.discard // don't need no stinkin' spring
    // Jackson 2 and Jackson 3 coexist functionally, but fail in assembly on this:
    case "META-INF/FastDoubleParser-LICENSE" => MergeStrategy.first

    case x => oldStrategy(x)
  }
}
