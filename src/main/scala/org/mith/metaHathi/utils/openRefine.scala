package org.mith.metaHathi.utils

import scalaz._, Scalaz._
import argonaut._, Argonaut._

import java.io.File
import java.io.ByteArrayInputStream
import java.util.ArrayList

import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.client.methods.HttpPost

import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils


class OpenRefineImporter(refineHost:String, port:Int=3333) {

  val client = HttpClientBuilder.create().build  

  def createRequest(command:String, params:Map[String, String] = null) : HttpPost = {

    val paramStr = if (params != null) {
      val p = params.view map {
        case (k, v) => k + "=" + v
      } mkString ("", "&", "")
      "?" + p
    } else ""

    new HttpPost("http://" + refineHost + ":" + port.toString + "/" + command + paramStr)
  }

  def createImportingJob() : String = {
    // Create job      

    val request = createRequest("command/core/create-importing-job")
    request.addHeader("Content-Type", "application/json")

    val response = client.execute(request)
    val jobJson = EntityUtils.toString(response.getEntity())
    
    Parse.parseWith(jobJson, _.field("jobID").getOrElse("0").toString, msg => msg)
    
  }

  def checkStatus (jobId:String) = {
    // Check job status

    val request = createRequest("command/core/get-importing-job-status", Map("jobID" -> jobId)) 

    val response = client.execute(request)
    EntityUtils.toString(response.getEntity())
  }

  def sendData(data:List[Json]) = {
    import org.apache.http.entity.ContentType
    import java.nio.charset.StandardCharsets

    // Set importer and send data

    val jobId = createImportingJob()

    val params = Map("controller" -> "core/default-importing-controller",
                     "jobID" -> jobId,
                     "subCommand" -> "load-raw-data")

    val request = createRequest("command/core/importing-controller", params)

    val entity = MultipartEntityBuilder.create()

    for ( d <- data ) {
      val dataStream = new ByteArrayInputStream(d.toString.getBytes(StandardCharsets.UTF_8))
      entity.addBinaryBody("f", dataStream, ContentType APPLICATION_JSON, "f")
    }

    request.setEntity(entity.build())
    client.execute(request)
    finalize(jobId)

  }

  def finalize (jobId:String) {
    import org.apache.http.NameValuePair
    import org.apache.http.client.entity.UrlEncodedFormEntity
    import org.apache.http.message.BasicNameValuePair

    // choose field, set options, complete project creation

    val options = 
      Json(
        "recordPath" := List("__anonymous__", "record"),
        "limit" := -1,
        "trimStrings" := jFalse,
        "guessCellValueTypes" := jFalse,
        "storeEmptyStrings" := jTrue,
        "includeFileSources" := jFalse
      )

    val params = 
      Map(
        "controller" -> "core/default-importing-controller",
        "jobID" -> jobId,
        "subCommand" -> "create-project"
      )

    val request = createRequest("command/core/importing-controller", params)

    val nameValuePairs = new ArrayList[NameValuePair](1)
    nameValuePairs.add(new BasicNameValuePair("format", "text/json"))
    nameValuePairs.add(new BasicNameValuePair("options", options.toString))
    request.setEntity(new UrlEncodedFormEntity(nameValuePairs))

    client.execute(request)

  }

}