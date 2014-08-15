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

import org.openid4java.consumer._
import org.openid4java.discovery._
import org.openid4java.message.ax._
import org.openid4java.message._

import org.apache.http._

import com.typesafe.config.ConfigFactory

case class AuthUser(email: String, firstName: String, lastName: String)

class HathiImport(system:ActorSystem) extends MetaHathiStack with ScalateSupport with FileUploadSupport with FutureSupport{

  private val conf = ConfigFactory.load

  private val APP_URL = conf.getString("app.url")
  private val RECORDS_PATH = conf.getString("app.data")
  private val OPENREFINE_HOST = conf.getString("openrefine.host")
  private val OPENREFINE_DATA = conf.getString("openrefine.data")

  protected implicit def executor: ExecutionContext = system.dispatcher

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(3*1024*1024)))

  var sessionAuth: ConcurrentMap[String, AuthUser] = new ConcurrentHashMap[String, AuthUser]()
  val manager = new ConsumerManager

  get("/") {    

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
      case None => { 

        ssp("/login")

      }
      case Some(user) => {
        val person = "%s %s".format(user.firstName, user.lastName) 

        val orClient = new OpenRefineClient(OPENREFINE_HOST)
        val projects = orClient.getAllProjectMetadataForUser(user.email)

        val importing = new File("/tmp" + "/metahathi/"+user.email.replace("@", "_")).exists

        // val importing = Option(session.getAttribute("importing")) getOrElse false

        ssp("/index", "person" -> person, "projects" -> projects, "importing" -> importing)
      }
    }

  }

  get("/process") { 

    contentType = "text/html"   

    sessionAuth.get(session.getId) match {
      case None => ssp("/login")
      case Some(user) => 

        // Creating a directory and storing a file with the user's email
        // to determine whether the project is still importing or not.
        // This is not very safe, arguably, and should be fixed        
        val locksPath = "/tmp" + "/metahathi"
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
            new File("/tmp"+"/metahathi/"+userEmail.replace("@", "_")).delete()
        }

        redirect("/")

    }

  }

  get("/edit/:proj") {

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
        case Some(user) => 
          val person = "%s %s".format(user.firstName, user.lastName) 
          ssp("/edit", "person" -> person, "project" -> params("proj"))
        case None => ssp("/login") 
    }
  }

  get("/review/:proj") {

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
        case Some(user) => 
          val person = "%s %s".format(user.firstName, user.lastName) 

          val orClient = new OpenRefineProjectClient(OPENREFINE_HOST, params("proj"))   
          val changes = orClient.getAllChanges

          ssp("/review", "person" -> person, "project" -> params("proj"), "changes" -> changes)
        case None => ssp("/login") 
    }
  }

  get("/logout") {
    sessionAuth.get(session.getId) match {
        case Some(user) => sessionAuth -= session.getId
        case None => 
    }
    session.invalidate()
    redirect("/")
  }

  
  get("/login/google") {
      
    sessionAuth.get(session.getId) match {
      case None => {        
          val discoveries = manager.discover("https://www.google.com/accounts/o8/id")
          val discovered = manager.associate(discoveries)
          session.setAttribute("discovered", discovered)
          val authReq = manager.authenticate(discovered, APP_URL + "/login/google/authenticated")
          val fetch = FetchRequest.createFetchRequest()
          fetch.addAttribute("email", "http://schema.openid.net/contact/email",true)
          fetch.addAttribute("firstname", "http://axschema.org/namePerson/first", true)
          fetch.addAttribute("lastname", "http://axschema.org/namePerson/last", true)
          authReq.addExtension(fetch)
          redirect(authReq.getDestinationUrl(true))        
      }
      case Some(user) => redirect("/")
    }
  }

  get("/login/google/authenticated") {
      val openidResp = new ParameterList(request.getParameterMap())
      val discovered = session.getAttribute("discovered").asInstanceOf[DiscoveryInformation]
      val receivingURL = request.getRequestURL()
      val queryString = request.getQueryString()
      if (queryString != null && queryString.length() > 0)
          receivingURL.append("?").append(request.getQueryString())

      val verification = manager.verify(receivingURL.toString(), openidResp, discovered)
      val verified = verification.getVerifiedId()
      if (verified != null) {
        val authSuccess = verification.getAuthResponse().asInstanceOf[AuthSuccess]
        if (authSuccess.hasExtension(AxMessage.OPENID_NS_AX)){
          val fetchResp = authSuccess.getExtension(AxMessage.OPENID_NS_AX).asInstanceOf[FetchResponse]
          val emails = fetchResp.getAttributeValues("email")
          val email = emails.get(0).asInstanceOf[String]
          val firstName = fetchResp.getAttributeValue("firstname")
          val lastName = fetchResp.getAttributeValue("lastname")
          sessionAuth += (session.getId -> AuthUser(email, firstName, lastName))          
          redirect("/")
        }
      } else
        "not verified"        
  }

}