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

case class AuthUser(email: String, firstName: String, lastName: String)

class HathiImport(system:ActorSystem) extends MetaHathiStack with ScalateSupport with FileUploadSupport with FutureSupport{

  private val APP_URL = "http://localhost:8081"
  // private val OPENREFINE_HOST = "127.0.0.1:3333"
  // private val APP_URL = "http://localhost:8080"
  private val OPENREFINE_HOST = "127.0.0.1:8080/openrefine"

  protected implicit def executor: ExecutionContext = system.dispatcher

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(3*1024*1024)))

  var sessionAuth: ConcurrentMap[String, AuthUser] = new ConcurrentHashMap[String, AuthUser]()
  val manager = new ConsumerManager

  get("/sandbox") {
    <html>

      <body>
      {
        sessionAuth.get(session.getId) match {
          case None => { }
          case Some(user) => {
            val person = "Hello %s %s".format(user.firstName, user.lastName) 
            <div><p>{person}</p><p><a href="/logout">logout</a></p></div>
          }
        }
        
      }
      </body>

    </html>
  }

  get("/") {    

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
      case None => { 

        ssp("/login")

      }
      case Some(user) => {
        val person = "%s %s".format(user.firstName, user.lastName) 
        ssp("/index", "person" -> person)
      }
    }

  }

  get("/process") { 

    contentType = "text/html"   

    sessionAuth.get(session.getId) match {
      case None => ssp("/login")
      case Some(user) => 
        
        val base = new File("/home/rviglian/Projects/htrc/hathi/output/results")
        // val base = new File("/home/rviglian/Desktop")
        val myCol = new Collection(base, base)

        val JsonPat = """(.*)?\.([^,]*)?\.(json)$""".r

        val importer = new OpenRefineImporter(OPENREFINE_HOST)

        val files = List.fromArray(base.listFiles)

        val data : List[Json] = files.map ( file =>
          file.getName match {
            case JsonPat(bib, id, ext) =>
              val htid = new Htid(bib, id)
              val metadata = myCol.volumeMetadata(htid).getOrElse(halt(500, "500"))               
              MetadataWrangler.recordToJson(metadata)              
            case _ => jEmptyString
          }
        )

        // importer.sendData returns a future, which is handled implicitly by FutureSupport
        // so we can use for instead of onSuccess. onSuccess causes redirect to be called out
        // of context and return an exception.

        for (pid <- importer.sendData(data, List("_", "record"))) yield {redirect("/edit/" + pid)}
      
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
          
          val orClient = new OpenRefineProjectClient(OPENREFINE_HOST, params("proj"))

          orClient.getHistory()

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