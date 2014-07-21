package org.mith.metaHathi

import org.mith.metaHathi.utils.OpenRefineImporter
import edu.umd.mith.hathi.{Collection, Htid}

import scala.collection.JavaConversions._
import collection.mutable.ConcurrentMap

import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.nio.charset.StandardCharsets

import org.scalatra._
import scalate.ScalateSupport
import org.scalatra.ScalatraServlet
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig, SizeConstraintExceededException}

import scalaz._, Scalaz._
import argonaut._, Argonaut._

import org.openid4java.consumer._
import org.openid4java.discovery._
import org.openid4java.message.ax._
import org.openid4java.message._

import org.apache.http._

case class AuthUser(email: String, firstName: String, lastName: String)

class HathiImport extends MetaHathiStack with ScalateSupport with FileUploadSupport {

  private val APP_URL = "http://localhost:8081"
  private val OPENREFINE_HOST = "127.0.0.1"

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
        val myCol = new Collection(base, base)

        val JsonPat = """(.*)?\.([^,]*)?\.(json)$""".r

        val importer = new OpenRefineImporter(OPENREFINE_HOST)

        val files = List.fromArray(base.listFiles)

        val data : List[Json] = files.map ( file =>
          file.getName match {
            case JsonPat(bib, id, ext) =>
              val htid = new Htid(bib, id)
              val metadata = myCol.volumeMetadata(htid).getOrElse(halt(500, "500"))               
              MetadataWrangler.volumeToJson(metadata)              
            case _ => jEmptyString
          }
        )

        importer.sendData(data, List("__anonymous__", "volume"))

        redirect("/edit")
      
    }

  }

  get("/edit") {

    contentType = "text/html"

    sessionAuth.get(session.getId) match {
        case Some(user) => 
          val person = "%s %s".format(user.firstName, user.lastName) 
          ssp("/edit", "person" -> person)
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