import sbt._

object SwaggerCodegen {

  def genSwaggerClientCode(base: File): Seq[File] = {
    val generatedBase = base / "generated"

    val swaggerCmd = "java -jar ./client/lib/swagger-codegen-cli.jar generate " +
      s"-l java --input-spec ./core/src/main/resources/swagger/api-docs.yaml --output ${generatedBase.getAbsolutePath}"

    swaggerCmd !

    // The above creates files ${base}/generated/src/main/java/...
    // SBT expects sources in ${base}/java/...
    // Do some shuffling around.

    IO.copyDirectory(generatedBase / "src" / "main" / "java", base / "java")
    IO.delete(generatedBase)

    listFilesRecursively(base)
  }

  def listFilesRecursively(dir: File): Seq[File] = {
    val list = IO.listFiles(dir)
    list.filter(_.isFile) ++ list.filter(_.isDirectory).flatMap(listFilesRecursively)
  }

}