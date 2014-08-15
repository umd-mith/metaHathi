package org.mith.metaHathi.utils

import scala.concurrent._
import ExecutionContext.Implicits.global

import scalaz._, Scalaz._
import argonaut._, Argonaut._

import java.io.File
import java.io.ByteArrayInputStream
import java.util.ArrayList

// Using apache http utils directly instead of dispatch because 
// multipart file upload is currently not supported.
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.client.methods.{HttpPost, HttpGet}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils

import java.util.zip.{ZipFile, ZipEntry}

object HTTPUtils {

  private def encode(params:Map[String, String]) : String = {
    val p = params.view map {
      case (k, v) => k + "=" + v
    } mkString ("", "&", "")
    "?" + p
  }

  def createPostRequest(refineHost:String, command:String, params:Map[String, String] = null) : HttpPost = {

    val paramStr = if (params != null) {
      encode(params)
    } else ""

    new HttpPost("http://%s/%s%s".format(refineHost, command, paramStr))
  }

  def createGetRequest(refineHost:String, command:String, params:Map[String, String] = null) : HttpGet = {

    val paramStr = if (params != null) {
      encode(params)
    } else ""

    new HttpGet("http://%s/%s%s".format(refineHost, command, paramStr))
  }

}

class OpenRefineClient(refineHost:String) {

  val client = HttpClientBuilder.create().build

  def getAllProjectMetadataForUser(user:String): Map[String, String] = {

    val request = HTTPUtils.createGetRequest(refineHost, "command/core/get-all-project-metadata")
    val response = client.execute(request)

    val metaJson: Json = Parse.parseOption(EntityUtils.toString(response.getEntity())).getOrElse(jEmptyObject) 
    val projects = (metaJson.hcursor --\ "projects")
    
    projects.focus.get.obj.get.fieldSet.filter{ case p =>      
      (projects --\ p --\ "name").focus.get.as[String].value.get == user
    }.map{case p =>
        val date = (projects --\ p --\ "modified").focus.get.as[String].value.get
        
        val rs = new com.github.nscala_time.time.RichString(date)
        // The time is parsed as GMT, so we need to re-add the zone time diff
        // Should use the library to do so properly, but for now we just add the missing 4 hours
        (p, rs.toDateTime.plusHours(4).toString("MM/dd/y 'at' hh:mma"))
        // I may also want to add some sorting by date here. Not urgent as OpenRefine seems to
        // return them in chronological order anyway.
      }.toMap

  }

}

class OpenRefineProjectClient(refineHost:String, projectId:String, refineData:String = "/tmp/refine"){

  implicit def ColumnDecodeJson: DecodeJson[Column] =
    DecodeJson(c => for {
      idx <- (c --\ "cellIndex").as[Int]
      name <- (c --\ "originalName").as[String]
    } yield Column(idx, name))

  val client = HttpClientBuilder.create().build    

  def close() = {
    client.close()
  }

  def getAllChanges(): Map[String, List[Change]] = {
    getHistory().flatMap( h => getChangesForOperation(h) ).groupBy(_.url)
  }

  // Get history of operations (ignoring future ones)
  // command/core/get-history
  // return list of past operation ids
  def getHistory(): List[String] = {

    val request = HTTPUtils.createGetRequest(refineHost, "command/core/get-history", Map("project" -> projectId))
    val response = client.execute(request)

    val histJson: Json = Parse.parseOption(EntityUtils.toString(response.getEntity())).getOrElse(jEmptyObject) 
    val past: List[Json] = (histJson.hcursor --\ "past").focus.get.arrayOrEmpty
    
    past.map( p => p.fieldOrZero("id").toString )

  }


  // get field for row (generic)
  // If the field is key, look up rows until found
  def getFieldforRow(row:String, field: String, key: Boolean = false): Json = {
    // First, find out the column index of record - id
    val idx: Int = getModels().find(_.name == field).get.idx
    val request = HTTPUtils.createGetRequest(
      refineHost, "command/core/get-rows", 
      Map("project" -> projectId,
        "start" -> row,
        "limit" -> "1"
      )
    )
    val response = client.execute(request)
    val rowJson: Json = Parse.parseOption(EntityUtils.toString(response.getEntity())).getOrElse(jEmptyObject)

    (((rowJson.hcursor --\ "rows" \\).any --\ "cells" =\ idx).any --\ "v").focus.getOrElse(
      // Recursing to first row with non-null field
      getFieldforRow((row.toInt - 1).toString, field, key)
    ) // NB cannot convert to string here because of recursion.

  }


  // command/core/get-models
  // it would be good to store the results of this somewhere to avoid 
  // multiple HTTP requests (at least two per change, see getChangesForOperation)
  def getModels() : List[Column] = {
    val request = HTTPUtils.createGetRequest(refineHost, "command/core/get-models", 
      Map("project" -> projectId))
    val response = client.execute(request)

    val modelJson: Json = Parse.parseOption(EntityUtils.toString(response.getEntity()))
      .getOrElse(jEmptyObject) 
    
    (modelJson.hcursor --\ "columnModel" --\ "columns").focus.get.arrayOrEmpty.map(
      c => c.as[Column].value.get
    )

  }

  // get changes for operation id
  // this goes to the refineData folder, unzips the right project file, reads change data for operation id
  def getChangesForOperation(opId: String) = {

    def getZipEntryInputStream(zipFile: ZipFile)(entry: ZipEntry) = zipFile.getInputStream(entry)
    val ChangeData = """(?s).*row=(\d+)\ncell=(\d+)\nold=(\{.*?\}\n)new=(\{.*?\}\n).*?""".r

    val zipFile = new ZipFile("%s/%s.project/history/%s.change.zip".format(refineData, projectId, opId))
    val zis = getZipEntryInputStream(zipFile)(new ZipEntry("change.txt"))
    // Convert InputStrem to String, assuming the file won't be huge.
    val changesRaw = scala.io.Source.fromInputStream(zis).mkString
    zipFile.close()

    val changes: List[Change] = changesRaw.split("/ec/").toList.map( c => c match {
      case ChangeData(row, cell, old, nw) =>
        val pold : String = Parse.parseWith(old, _.field("v").getOrElse(jEmptyString).as[String].value.get, 
          err => err)
        val pnew : String = Parse.parseWith(nw, _.field("v").getOrElse(jEmptyString).as[String].value.get, 
          err => err)
        Some(Change(
          getFieldforRow(row, "record - url", true).as[String].value.get,
          getModels().find(_.idx == cell.toInt).get.name,
          pold,
          pnew
        ))
      case _ => None
    }).flatten

    changes

  }
}



class OpenRefineImporter(refineHost:String, userEmail: String) {
  // The user's email is used to distinguish projects in an OR instance used by multiple users

  val client = HttpClientBuilder.create().build    

  def createImportingJob() : String = {
    // Create job      

    val request = HTTPUtils.createPostRequest(refineHost, "command/core/create-importing-job")
    request.addHeader("Content-Type", "application/json")

    val response = client.execute(request)
    val jobJson = EntityUtils.toString(response.getEntity())
    
    Parse.parseWith(jobJson, _.field("jobID").getOrElse(jEmptyString).toString, err => err)
    
  }

  def checkStatus (jobId:String) = {
    // Check job status

    val request = HTTPUtils.createPostRequest(refineHost, "command/core/get-importing-job-status", 
      Map("jobID" -> jobId)) 

    val response = client.execute(request)
    EntityUtils.toString(response.getEntity())
  }

  def sendData(data:List[Json], path:List[String]) = {
    import org.apache.http.entity.ContentType
    import java.nio.charset.StandardCharsets

    // Set importer and send data

    val jobId = createImportingJob()

    val params = Map("controller" -> "core/default-importing-controller",
                     "jobID" -> jobId,
                     "subCommand" -> "load-raw-data")

    val request = HTTPUtils.createPostRequest(refineHost, "command/core/importing-controller", params)

    val entity = MultipartEntityBuilder.create()

    // val out = new java.io.PrintWriter(new java.io.FileWriter("/tmp/full.json"))

    for ( d <- data ) {
      // out.println(d.toString)
      val dataStream = new ByteArrayInputStream(d.toString.getBytes(StandardCharsets.UTF_8))
      entity.addBinaryBody("f", dataStream, ContentType APPLICATION_JSON, "f")
    }

    request.setEntity(entity.build())
    client.execute(request)

    // Finalize the import
    val fin = finalize(jobId, path)

    // In order to return the id of the finalized project, we must wait on OpenRefine to
    // complete the import. So we return a Future of the project id.
    // Future {

      // Before proceeding, make sure the project creation is complete (NB it doens't guarantee that the import is done)
      // This could be handled more natively, perhaps with another Future.
      if (Parse.parseWith(fin, _.field("message").getOrElse(jEmptyString).as[String].value.get, 
        err => err) == "done" ) {

        // Check status until a project ID appears (which is introduced together with state : created-project)
        def getAsyncProjectId() : (String, String) = {
          val status: Json = Parse.parseOption(checkStatus(jobId)).get  
          val cursor = status.hcursor
          val pid = (cursor --\ "job" --\ "config" --\ "projectID")
          val value = pid.focus.getOrElse( getAsyncProjectId() ).toString
          client.close()
          (value, userEmail)
        }
        
        getAsyncProjectId()

      }
      else None
    // }

  }

  def finalize (jobId:String, path:List[String]) : String = {
    import org.apache.http.NameValuePair
    import org.apache.http.client.entity.UrlEncodedFormEntity
    import org.apache.http.message.BasicNameValuePair

    // choose field, set options, complete project creation

    val options = 
      Json(
        "recordPath" := path,
        "limit" := -1,
        "trimStrings" := jFalse,
        "guessCellValueTypes" := jFalse,
        "storeEmptyStrings" := jTrue,
        "includeFileSources" := jFalse,
        "projectName" := userEmail
      )

    val params = 
      Map(
        "controller" -> "core/default-importing-controller",
        "jobID" -> jobId,
        "subCommand" -> "create-project"
      )

    val request = HTTPUtils.createPostRequest(refineHost, "command/core/importing-controller", params)

    val nameValuePairs = new ArrayList[NameValuePair](1)
    nameValuePairs.add(new BasicNameValuePair("format", "text/json"))
    nameValuePairs.add(new BasicNameValuePair("options", options.toString))
    request.setEntity(new UrlEncodedFormEntity(nameValuePairs))

    val response = client.execute(request)   
    EntityUtils.toString(response.getEntity())

  }

}