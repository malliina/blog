import java.nio.file.Path
import complete.DefaultParsers.spaceDelimited

import play.sbt.PlayImport

val scala212 = "2.12.10"
val scala213 = "2.13.1"

val npm = taskKey[NPM]("NPM interface")
val npmBuild = taskKey[Unit]("npm run build")
val npmKillNode = taskKey[Unit]("Kills node with force")
val frontendDirectory = settingKey[File]("frontend base dir")
frontendDirectory in ThisBuild := baseDirectory.value / "frontend"
val cleanSite = taskKey[Unit]("Empties the target site dir")
val cleanDocs = taskKey[Unit]("Empties the target docs dir")
val siteDir = settingKey[File]("Site target dir")
val docsDir = settingKey[File]("Docs target dir")
val prepDirs = taskKey[Unit]("Creates directories")
val writeManifest = inputKey[Path]("Writes the manifest file")
val build = taskKey[Unit]("Builds the site")
val deploy = inputKey[Unit]("Deploys the site")
val deployDraft = taskKey[Unit]("Deploys the draft site")
val deployProd = taskKey[Unit]("Deploys the prod site")

val code = project
  .in(file("code"))
  .enablePlugins(PlayScala)
  .settings(
    scalaVersion := scala212,
    libraryDependencies ++= Seq(
      PlayImport.ws,
      "org.scalameta" %% "munit" % "0.7.1" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

val docs = project
  .in(file("mdoc"))
  .enablePlugins(MdocPlugin)
  .dependsOn(code, code % "compile->test")
  .settings(
    organization := "com.malliina",
    scalaVersion := scala212,
    crossScalaVersions -= scala213,
    skip.in(publish) := true,
    mdocVariables := Map("NAME" -> name.value, "VERSION" -> version.value),
    mdocOut := (baseDirectory in ThisBuild).value / "target" / "docs"
  )

val content = project
  .in(file("content"))
  .settings(
    crossScalaVersions := scala213 :: scala212 :: Nil,
    scalaVersion := scala212,
    libraryDependencies ++= Seq(
      "com.malliina" %% "primitives" % "1.13.0",
      "com.lihaoyi" %% "scalatags" % "0.8.5",
      "com.typesafe" % "config" % "1.4.0",
      "com.vladsch.flexmark" % "flexmark" % "0.40.34", // mdoc uses 0.40.34,
      "org.slf4j" % "slf4j-api" % "1.7.25",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "ch.qos.logback" % "logback-core" % "1.2.3"
    ),
    npm := new NPM(
      (frontendDirectory in ThisBuild).value,
      target.value,
      streams.value.log
    ),
    npmBuild := npm.value.build(),
    watchSources := watchSources.value ++ Seq(
      WatchSource(
        (frontendDirectory in ThisBuild).value / "src",
        "*.ts" || "*.scss",
        HiddenFileFilter
      ),
      WatchSource((mdocIn in docs).value)
    ),
    siteDir := (baseDirectory in ThisBuild).value / "target" / "site",
    cleanSite := FileIO.deleteDirectory(siteDir.value),
    docsDir := (baseDirectory in ThisBuild).value / "target" / "docs",
    cleanDocs := FileIO.deleteDirectory(docsDir.value),
    prepDirs := {
      // Ownership problems if webpack generates these, apparently
      val assetsDir = siteDir.value / "assets"
      Seq(assetsDir / "css", assetsDir / "fonts").map(_.mkdirs())
    },
    npmKillNode := npm.value.stop(),
    writeManifest := {
      val args: Seq[String] = spaceDelimited("<arg>").parsed
      FileIO.writeJson(
        SiteManifest(siteDir.value.toPath, docsDir.value.toPath, local = args.contains("dev")),
        (target.value / "manifest.json").toPath
      )
    },
    run := Def.taskDyn {
      (run in Compile)
        .dependsOn((mdoc in docs).toTask(""), npmBuild)
        .dependsOn(prepDirs)
        .toTask(s" ${writeManifest.toTask(" dev").value}")
    }.value,
    build := Def.taskDyn {
      (run in Compile)
        .dependsOn((mdoc in docs).toTask(""), npmBuild)
        .dependsOn(prepDirs)
        .dependsOn(cleanSite, cleanDocs)
        .toTask(s" ${writeManifest.toTask(" prod").value}")
    }.value,
    deploy := {
      val args = spaceDelimited("<arg>").parsed
      NPM
        .runProcessSync(
          args.mkString(" "),
          (baseDirectory in ThisBuild).value,
          streams.value.log
        )
    },
    deploy := deploy.dependsOn(build).evaluated,
    deployProd := deploy.toTask(" netlify deploy --prod").value,
    deployDraft := deploy.toTask(" netlify deploy").value
  )

val blog = project.in(file(".")).aggregate(code, docs, content)

Global / onChangedBuildSource := ReloadOnSourceChanges
