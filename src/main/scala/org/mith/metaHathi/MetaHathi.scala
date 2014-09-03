package org.mith.metaHathi

import org.mith.metaHathi.utils._
import edu.umd.mith.hathi.{Collection, Htid}

import scala.collection.JavaConversions._
import collection.mutable.ConcurrentMap

import scala.concurrent._
import ExecutionContext.Implicits.global

import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.nio.charset.StandardCharsets

import org.scalatra._
import scalate.ScalateSupport
import org.scalatra.ScalatraServlet
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig, SizeConstraintExceededException}

import scalaz._, Scalaz._
import argonaut._, Argonaut._

import _root_.akka.actor.{Actor, ActorRef, ActorSystem}

import org.apache.http._

import com.typesafe.config.ConfigFactory

case class AuthUser(email: String, name: String)

class HathiImport(system:ActorSystem) extends MetaHathiStack with ScalateSupport with FileUploadSupport with FutureSupport{

  private val conf = ConfigFactory.load

  private val APP_URL = conf.getString("app.url")
  private val RECORDS_PATH = conf.getString("app.data")
  private val OPENREFINE_HOST = conf.getString("openrefine.host")
  private val OPENREFINE_DATA = conf.getString("openrefine.data")

  protected implicit def executor: ExecutionContext = system.dispatcher

  var sessionAuth: ConcurrentMap[String, AuthUser] = new ConcurrentHashMap[String, AuthUser]()

  get("/") {    

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
      case None => { 

        redirect("/login")

      }
      case Some(user) => {
        val person = user.name

        val orClient = new OpenRefineClient(OPENREFINE_HOST)
        val projects = orClient.getAllProjectMetadataForUser(user.email)

        val importing = new File(OPENREFINE_DATA+"/"+user.email.replace("@", "_")).exists

        ssp("/index", "person" -> person, "projects" -> projects, "importing" -> importing)
      }
    }

  }

  get("/login") {
    contentType = "text/html"

    ssp("/login")

  }

  get("/process") { 

    contentType = "text/html"   

    sessionAuth.get(session.getId) match {
      case None => ssp("/login")
      case Some(user) => 

        // Creating a directory and storing a file with the user's email
        // to determine whether the project is still importing or not.
        // This is not very safe, arguably, and should be fixed        
        
        val locksPath = OPENREFINE_DATA
        new File(locksPath).mkdir()

        val lock = new File(locksPath+"/"+user.email.replace("@", "_"))
        lock.createNewFile()
        lock.deleteOnExit()

        // Now collect files from filesystem

        val base = new File(RECORDS_PATH)
        val myCol = new Collection(base, base)

        val JsonPat = """(.*)?\.([^,]*)?\.(json)$""".r

        val importer = new OpenRefineImporter(OPENREFINE_HOST, user.email)

        val files = List.fromArray(base.listFiles)

        // Now parse files and send to OpenRefine
        // This can take a while (minutes for thousands of entries)
        // so use a future

        val project : Future[_] = future {

          val data : List[Json] = files.map ( file =>
            file.getName match {
              case JsonPat(bib, id, ext) =>
                val htid = new Htid(bib, id)
                val metadata = myCol.volumeMetadata(htid).getOrElse(halt(500, "500"))               
                MetadataWrangler.recordToJson(metadata)              
              case _ => jEmptyString
            }
          )

          importer.sendData(data, List("_", "record"))

        }

        
        // Remove lock file when the project is created (future is completed)
        // NB it should probably delete it also onFailure
        project onSuccess {
          case (pid:String, userEmail:String) => 
            new File(OPENREFINE_DATA+"/"+userEmail.replace("@", "_")).delete()
        }

        redirect("/")

    }

  }

  get("/edit/:proj") {

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
        case Some(user) => 
          val person = user.name
          ssp("/edit", "person" -> person, "project" -> params("proj"))
        case None => ssp("/login") 
    }
  }

  get("/review/:proj") {

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
        case Some(user) => 
          val person = user.name

          val orClient = new OpenRefineProjectClient(OPENREFINE_HOST, params("proj"), OPENREFINE_DATA)   
          val changes = orClient.getAllChanges

          ssp("/review", "person" -> person, "project" -> params("proj"), "changes" -> changes)
        case None => ssp("/login") 
    }
  }

  get("/logout") {
    sessionAuth.get(session.getId) match {
        case Some(user) => 
          // Remove lock files on logout
          new File(OPENREFINE_DATA+"/"+user.email.replace("@", "_")).delete()
          sessionAuth -= session.getId
        case None => 
    }
    session.invalidate()
    redirect("/")
  }

  post("/login/google") {
    val authCode: String = params.getOrElse("authCode", halt(400))
    val guser = Authorization.google(authCode).getOrElse(redirect("/not-authorized"))
    sessionAuth += (session.getId -> AuthUser(guser.email, guser.name))     
    redirect("/")
  }

  get("/not-authorized") {
    "not authorized!"
  }

}