import Project.Initialize
import Util._
import Dependencies._
import Licensed._
import Scope.ThisScope
import Scripted._
import StringUtilities.normalize

// ThisBuild settings take lower precedence,
// but can be shared across the multi projects.
def buildLevelSettings: Seq[Setting[_]] = Seq(
  organization in ThisBuild := "org.scala-sbt",
  version in ThisBuild := "0.13.9-SNAPSHOT"
)

def commonSettings: Seq[Setting[_]] = Seq(
  scalaVersion := scala210,
  publishArtifact in packageDoc := false,
  publishMavenStyle := false,
  componentID := None,
  resolvers += Resolver.typesafeIvyRepo("releases"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  concurrentRestrictions in Global += Util.testExclusiveRestriction,
  testOptions += Tests.Argument(TestFrameworks.ScalaCheck, "-w", "1"),
  javacOptions in compile ++= Seq("-target", "6", "-source", "6", "-Xlint", "-Xlint:-serial"),
  incOptions := incOptions.value.withNameHashing(true)
)

// These settings allow us to create compiler-bridge_2.10, compiler-bridge_2.10.5,
// and compiler-bridge_2.11.7
def crossBuildingSettings: Seq[Setting[_]] = Seq(
  crossScalaVersions := Seq("2.10.4", scala210, "2.11.7"),
  crossVersion := {
    scalaVersion.value match {
      case "2.10.4" => CrossVersion.binary
      case _        => CrossVersion.full
    }
  }
)

def minimalSettings: Seq[Setting[_]] =
  commonSettings ++ customCommands ++
  publishPomSettings ++ Release.javaVersionCheckSettings

def baseSettings: Seq[Setting[_]] =
  minimalSettings ++ Seq(projectComponent) ++ baseScalacOptions ++ Licensed.settings ++ Formatting.settings

def testedBaseSettings: Seq[Setting[_]] =
  baseSettings ++ testDependencies

lazy val sbtRoot: Project = (project in file(".")).
  aggregate(nonRoots: _*).
  settings(
    buildLevelSettings,
    minimalSettings,
    rootSettings,
    crossBuildingSettings,
    publish := {},
    publishLocal := {}
  )

/* ** subproject declarations ** */

// defines Java structures used across Scala versions, such as the API structures and relationships extracted by
//   the analysis compiler phases and passed back to sbt.  The API structures are defined in a simple
//   format from which Java sources are generated by the datatype generator Projproject
lazy val interfaceProj = (project in file("interface")).
  settings(
    minimalSettings,
    javaOnlySettings,
    crossBuildingSettings,
    name := "Interface",
    projectComponent,
    exportJars := true,
    componentID := Some("xsbti"),
    watchSources <++= apiDefinitions,
    resourceGenerators in Compile <+= (version, resourceManaged, streams, compile in Compile) map generateVersionFile,
    apiDefinitions <<= baseDirectory map { base => (base / "definition") :: (base / "other") :: (base / "type") :: Nil },
    sourceGenerators in Compile <+= (cacheDirectory,
      apiDefinitions,
      fullClasspath in Compile in datatypeProj,
      sourceManaged in Compile,
      mainClass in datatypeProj in Compile,
      runner,
      streams) map generateAPICached
  )

// defines operations on the API of a source, including determining whether it has changed and converting it to a string
//   and discovery of Projclasses and annotations
lazy val apiProj = (project in compilePath / "api").
  dependsOn(interfaceProj).
  settings(
    testedBaseSettings,
    crossBuildingSettings,
    name := "API"
  )

/* **** Utilities **** */

// generates immutable or mutable Java data types according to a simple input format
lazy val datatypeProj = (project in utilPath / "datatype").
  dependsOn(ioProj).
  settings(
    baseSettings,
    name := "Datatype Generator"
  )


lazy val controlProj = (project in utilPath / "control").
  settings(
    baseSettings,
    crossBuildingSettings,
    name := "Control",
    crossScalaVersions := Seq(scala210, scala211)
  )

// The API for forking, combining, and doing I/O with system processes
lazy val processProj = (project in utilPath / "process").
  dependsOn(ioProj % "test->test").
  settings(
    baseSettings,
    crossBuildingSettings,
    name := "Process",
    libraryDependencies ++= scalaXml.value,
    publishArtifact in Test := true
  )

// Path, IO (formerly FileUtilities), NameFilter and other I/O utility classes
lazy val ioProj = (project in utilPath / "io").
  dependsOn(controlProj).
  settings(
    testedBaseSettings,
    crossBuildingSettings,
    name := "IO",
    libraryDependencies += scalaCompiler.value % Test,
    crossScalaVersions := Seq(scala210, scala211)
  )

// logging
lazy val logProj = (project in utilPath / "log").
  dependsOn(interfaceProj, processProj).
  settings(
    testedBaseSettings,
    crossBuildingSettings,
    name := "Logging",
    libraryDependencies += jline,
    publishArtifact in Test := true
  )

// Compiler-side interface to compiler that is compiled against the compiler being used either in advance or on the fly.
//   Includes API and Analyzer phases that extract source API and relationships.
lazy val compilerBridgeProj = (project in compilePath / "bridge").
  dependsOn(interfaceProj % "compile;test->test", ioProj % "test->test", logProj % "test->test", /*launchProj % "test->test",*/ apiProj % "test->test").
  settings(
    baseSettings,
    crossBuildingSettings,
    libraryDependencies += scalaCompiler.value % "provided",
    name := "Compiler bridge",
    exportJars := true,
    // we need to fork because in unit tests we set usejavacp = true which means
    // we are expecting all of our dependencies to be on classpath so Scala compiler
    // can use them while constructing its own classpath for compilation
    fork in Test := true,
    // needed because we fork tests and tests are ran in parallel so we have multiple Scala
    // compiler instances that are memory hungry
    javaOptions in Test += "-Xmx1G",
    artifact in (Compile, packageSrc) := {
      Artifact(srcID).copy(configurations = Compile :: Nil).extra("e:component" -> srcID)
    },
    commands += publishBridgeSource
  )

lazy val publishAll = TaskKey[Unit]("publish-all")

lazy val publishBridgeBinary = Command.command("publishBridgeBinary") { state =>
  "+ publishLocal" ::
    state
}

lazy val publishBridgeSource = Command.command("publishBridgeSource") { state =>
  "set crossVersion in compilerBridgeProj := CrossVersion.Disabled" ::
    "set publishArtifact in (Compile, packageBin) in compilerBridgeProj := false" ::
    "compilerBridgeProj/publishLocal" ::
    state
}

lazy val publishBridge = Command.command("publishBridge") { state =>
  "publishBridgeBinary" ::
    "publishBridgeSource" ::
    state
}

lazy val myProvided = config("provided") intransitive

def allProjects = Seq(interfaceProj, apiProj,
  controlProj, processProj, ioProj, logProj,
  compilerBridgeProj)

def projectsWithMyProvided = allProjects.map(p => p.copy(configurations = (p.configurations.filter(_ != Provided)) :+ myProvided))
lazy val nonRoots = projectsWithMyProvided.map(p => LocalProject(p.id))

def rootSettings = fullDocSettings ++
  Util.publishPomSettings ++ otherRootSettings ++ Formatting.sbtFilesSettings

def otherRootSettings = Seq(
  publishAll := {
    val _ = (publishLocal).all(ScopeFilter(inAnyProject)).value
  },
  aggregate in bintrayRelease := false
)

lazy val docProjects: ScopeFilter = ScopeFilter(
  inAnyProject,
  inConfigurations(Compile)
)
def fullDocSettings = Util.baseScalacOptions ++ Docs.settings ++ Seq(
  scalacOptions += "-Ymacro-no-expand" // for doc
)

/* Nested Projproject paths */
def utilPath    = file("util")
def compilePath = file("compile")


lazy val safeUnitTests = taskKey[Unit]("Known working tests (for both 2.10 and 2.11)")
lazy val safeProjects: ScopeFilter = ScopeFilter(
  inProjects(logProj),
  inConfigurations(Test)
)
lazy val otherUnitTests = taskKey[Unit]("Unit test other projects")
lazy val otherProjects: ScopeFilter = ScopeFilter(
  inProjects(interfaceProj, apiProj, controlProj,
    ioProj,
    datatypeProj),
  inConfigurations(Test)
)

def customCommands: Seq[Setting[_]] = Seq(
  commands += Command.command("setupBuildScala211") { state =>
    s"""set scalaVersion in ThisBuild := "$scala211" """ ::
      state
  },
  // This is invoked by Travis
  commands += Command.command("checkBuildScala211") { state =>
    s"++ $scala211" ::
      // First compile everything before attempting to test
      "all compile test:compile" ::
      // Now run known working tests.
      safeUnitTests.key.label ::
      state
  },
  safeUnitTests := {
    test.all(safeProjects).value
  },
  otherUnitTests := {
    test.all(otherProjects)
  },
  commands += Command.command("release-sbt-local") { state =>
    "clean" ::
    "allPrecompiled/clean" ::
    "allPrecompiled/compile" ::
    "allPrecompiled/publishLocal" ::
    "so compile" ::
    "so publishLocal" ::
    "reload" ::
    state
  },
  /** There are several complications with sbt's build.
   * First is the fact that interface project is a Java-only project
   * that uses source generator from datatype subproject in Scala 2.10.4,
   * which is depended on by Scala 2.8.2, Scala 2.9.2, and Scala 2.9.3 precompiled project. 
   *
   * Second is the fact that sbt project (currently using Scala 2.10.4) depends on
   * the precompiled projects (that uses Scala 2.8.2 etc.)
   * 
   * Finally, there's the fact that all subprojects are released with crossPaths
   * turned off for the sbt's Scala version 2.10.4, but some of them are also
   * cross published against 2.11.1 with crossPaths turned on.
   *
   * Because of the way ++ (and its improved version wow) is implemented
   * precompiled compiler briges are handled outside of doge aggregation on root.
   * `so compile` handles 2.10.x/2.11.x cross building. 
   */
  commands += Command.command("release-sbt") { state =>
    // TODO - Any sort of validation
    "clean" ::
    "allPrecompiled/clean" ::
      "allPrecompiled/compile" ::
      "allPrecompiled/publishSigned" ::
      "conscript-configs" ::
      "so compile" ::
      "so publishSigned" ::
      "bundledLauncherProj/publishLauncher" ::
      state
  },
  // stamp-version doesn't work with ++ or "so".
  commands += Command.command("release-nightly") { state =>
    "stamp-version" ::
      "clean" ::
      "allPrecompiled/clean" ::
      "allPrecompiled/compile" ::
      "allPrecompiled/publish" ::
      "compile" ::
      "publish" ::
      "bintrayRelease" ::
      state
  },
  commands += publishBridgeBinary,
  commands += publishBridgeSource,
  commands += publishBridge
)
