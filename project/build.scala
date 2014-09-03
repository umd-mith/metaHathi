import sbt._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._
import com.earldouglas.xsbtwebplugin.PluginKeys._
import com.earldouglas.xsbtwebplugin.WebPlugin._

object MetaHathiBuild extends Build {
  val Organization = "org.mith"
  val Name = "metaHathi"
  val Version = "0.1.0"
  val ScalaVersion = "2.10.3"
  val ScalatraVersion = "2.2.2"

  lazy val project = Project (
    "metaHathi",
    file("."),
    settings = Defaults.defaultSettings ++ ScalatraPlugin.scalatraWithJRebel ++ scalateSettings ++ Seq(
      port in container.Configuration := 8081,
      organization := Organization,
      name := Name,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      "Index Data" at "http://maven.indexdata.com/",
      Classpaths.typesafeReleases
      ),
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % "2.3.4",
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-scalate" % ScalatraVersion,
        "org.scalatra" %% "scalatra-specs2" % ScalatraVersion % "test",
        "ch.qos.logback" % "logback-classic" % "1.0.6" % "runtime",
        "org.eclipse.jetty" % "jetty-webapp" % "8.1.8.v20121106" % "container", 
        "org.apache.httpcomponents" % "httpmime" % "4.3.1",
        "org.scalaz" %% "scalaz-core" % "7.0.6",
        "io.argonaut" %% "argonaut" % "6.0.4",
        "com.github.nscala-time" % "nscala-time_2.9.1" % "1.2.0",
        "com.typesafe" % "config" % "1.2.1",
        "com.google.apis" % "google-api-services-oauth2" % "v2-rev59-1.17.0-rc",
        "com.google.apis" % "google-api-services-plus" % "v1-rev115-1.17.0-rc",
        "com.google.http-client" % "google-http-client-jackson" % "1.19.0",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
      ),
      scalateTemplateConfig in Compile <<= (sourceDirectory in Compile){ base =>
        Seq(
          TemplateConfig(
            base / "webapp" / "WEB-INF" / "templates",
            Seq.empty,  /* default imports should be added here */
            Seq(
              Binding("context", "_root_.org.scalatra.scalate.ScalatraRenderContext", importMembers = true, isImplicit = true)
            ),  /* add extra bindings here */
            Some("templates")
          )
        )
      }
    )
  ).dependsOn(
    ProjectRef(
      uri("git://github.com/umd-mith/hathi.git#eacc2944b584ecf911085e556060f008c6587dce"),
      "hathi-core"
    )
  )
}
