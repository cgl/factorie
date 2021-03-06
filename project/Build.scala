import sbt._
import Keys._
import sbtassembly.Plugin._
import sbtjflex.SbtJFlexPlugin._
import AssemblyKeys._

object FactorieBuild extends Build {
  import Dependencies._

  lazy val overrideSettings = {
    lazy val publishSetting = publishTo <<= (version) {
      version: String =>
        def repo(name: String) = name at "https://dev-iesl.cs.umass.edu/nexus/content/repositories/" + name
      val isSnapshot = version.trim.endsWith("SNAPSHOT")
      val repoName = if(isSnapshot) "snapshots" else "releases"
      Some(repo(repoName))
    }

    lazy val credentialsSetting = credentials += {
      Seq("build.publish.user", "build.publish.password").map(k => Option(System.getProperty(k))) match {
        case Seq(Some(user), Some(pass)) =>
          Credentials("Sonatype Nexus Repository Manager", "iesl.cs.umass.edu", user, pass)
        case _ =>
          Credentials(Path.userHome / ".ivy2" / ".credentials")
      }
    }
  }

  val NoNLP = config("no-nlp-resources") extend(Runtime)
  val WithNLP = config("with-nlp-resources") extend(Runtime)

  val scalaMajorVersion = "2.11"
  val scalaMinorVersion = "7"

  lazy val factorie = Project("factorie", file(".")).
    configs(NoNLP, WithNLP).
    settings(jflexSettings ++ Seq(
      organization := s"cc.factorie_$scalaMajorVersion",
      version := "1.2-SNAPSHOT",
      scalaVersion := s"$scalaMajorVersion.$scalaMinorVersion",
      // no verbose deprecation warnings, octal escapes in jflex file are too many
      scalacOptions := Seq("-unchecked", "-encoding", "utf8"),
      resolvers ++= resolutionRepos,
      libraryDependencies ++= Seq(
        CompileDependencies.mongodb,
        CompileDependencies.colt,
        CompileDependencies.compiler,
        CompileDependencies.junit,
        CompileDependencies.acompress,
        CompileDependencies.acommonslang,
        CompileDependencies.snappy,
        CompileDependencies.bliki,
        CompileDependencies.json4s,
        TestDependencies.scalatest
      ),
      unmanagedSourceDirectories in Compile <+= (sourceDirectory in jflex),
      sourceGenerators in Compile <+= generate in jflex
    ):_*).
    settings(inConfig(NoNLP)(
      Classpaths.configSettings ++ Defaults.defaultSettings ++ baseAssemblySettings ++ jflexSettings ++ Seq(
      test in assembly := {},
      target in assembly <<= target,
      assemblyDirectory in assembly := cacheDirectory.value / "assembly-no-nlp-resources",
      jarName in assembly := "%s_%s-%s-%s" format (name.value, scalaMajorVersion, version.value, "jar-with-dependencies.jar")
    )): _*).
    settings(inConfig(WithNLP)(
      Classpaths.configSettings ++ Defaults.defaultSettings ++ baseAssemblySettings ++ jflexSettings ++ Seq(
      test in assembly := {},
      target in assembly <<= target,
      assemblyDirectory in assembly := cacheDirectory.value / "assembly-with-nlp-resources",
      jarName in assembly := "%s_%s-%s-%s" format (name.value, scalaMajorVersion, version.value, "nlp-jar-with-dependencies.jar"),
      libraryDependencies ++= Seq(Resources.nlpresources)
    )): _*)
}

object Dependencies {
  val resolutionRepos = Seq(
    "Scala tools" at "https://oss.sonatype.org/content/groups/scala-tools",
    "OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
    "UMass Releases" at "https://dev-iesl.cs.umass.edu/nexus/content/repositories/public",
    "UMass Snapshots" at "https://dev-iesl.cs.umass.edu/nexus/content/repositories/public-snapshots"
  )

  object CompileDependencies {
    val mongodb  = "org.mongodb" % "mongo-java-driver" % "2.11.1"
    val colt = "org.jblas" % "jblas" % "1.2.3"
    val compiler = "org.scala-lang" % "scala-compiler" % "2.11.2"
    val junit = "junit" % "junit" % "4.10"
    val acompress = "org.apache.commons" % "commons-compress" % "1.8"
    val acommonslang = "commons-lang" % "commons-lang" % "2.6"
    val snappy = "org.xerial.snappy" % "snappy-java" % "1.1.1.3"
    val bliki = "info.bliki.wiki" % "bliki-core" % "3.0.19"
    val json4s = "org.json4s" %% "json4s-jackson" % "3.2.9"
  }

  object TestDependencies {
    val scalatest = "org.scalatest" % "scalatest_2.11" % "2.2.2" % Test
  }

  object Resources {
    // This may be brittle, but intransitive() avoids creating a circular dependency.
    val nlpresources = "cc.factorie.app.nlp" % "all-models" % "1.2-SNAPSHOT" % "with-nlp-resources" intransitive()
  }
}
