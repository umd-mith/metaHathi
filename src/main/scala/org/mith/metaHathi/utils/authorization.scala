package org.mith.metaHathi.utils

import org.apache.http.client.methods.{HttpPost, HttpGet}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils

import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeTokenRequest, GoogleTokenResponse}
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson.JacksonFactory

import scalaz._, Scalaz._
import argonaut._, Argonaut._


object Authorization {

	val client = HttpClientBuilder.create().build

  // Google constants
  val GOOGLE_PLUS_PEOPLE_URL = "https://www.googleapis.com/plus/v1/people/me?fields=aboutMe%2Ccover%2FcoverPhoto%2CdisplayName%2Cdomain%2Cemails%2Clanguage%2Cname&access_token="
  val CLIENT_ID: String = "1003201408818-s9p0415vo7jbeoa14c8hvsoibu4gok75.apps.googleusercontent.com"
  val CLIENT_SECRET = "qN35MS0umrhZwMdzqEzI9SQE"
  val APPLICATION_NAME = "Hathi Metadata Enhancer "
  val JSON_FACTORY = new JacksonFactory()
  val TRANSPORT = new NetHttpTransport()

  // Handling for Google JSON response
  case class User(name: String, email: String)
 
  implicit def UserDecodeJson: DecodeJson[User] =
    DecodeJson(c => for {
      name <- (c --\ "displayName").as[String]
      email <- (c --\ "emails").downArray.downField("value").as[String]
    } yield User(name, email))

  // Request user data from Google
  def google(authCode: String): Option[User] = {
    val tokenResponse: GoogleTokenResponse =
        new GoogleAuthorizationCodeTokenRequest(
            TRANSPORT, JSON_FACTORY, CLIENT_ID, CLIENT_SECRET, authCode, "postmessage"
        ).execute
    val url:String = GOOGLE_PLUS_PEOPLE_URL + tokenResponse.getAccessToken
    val request = new HttpGet(url)
    val response = client.execute(request)
    val userInfo = EntityUtils.toString(response.getEntity())
    
    Parse.decodeOption[User](userInfo)
    
  }


}