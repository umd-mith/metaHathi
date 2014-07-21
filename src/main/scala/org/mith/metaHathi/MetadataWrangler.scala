package org.mith.metaHathi

import scalaz._, Scalaz._
import argonaut._, Argonaut._

import edu.umd.mith.hathi._
import edu.umd.mith.hathi.RecordId

import java.net.URL

object MetadataWrangler {
     
  implicit def URLEncodeJson: EncodeJson[URL] = 
    jencode1((u: URL) => u.toString )

  implicit def UpdatedEncodeJson: EncodeJson[Updated] = 
    jencode1((u: Updated) => u.toString )

  implicit def HtidEncodeJson: EncodeJson[Htid] = 
    jencode1((h: Htid) => h.toString )

  implicit def RecordIdEncodeJson: EncodeJson[RecordId] = 
    jencode1((ri: RecordId) => (ri.id) )

  implicit def RecordEncodeJson: EncodeJson[RecordMetadata] = 
		EncodeJson((rm: RecordMetadata) =>
      Json("record" -> 
        (("id" := rm.id) ->: 
        ("url" := rm.url) ->:
        ("titles" := rm.titles) ->: 
        ("isbns" := rm.isbns) ->: 
        ("issns" := rm.issns) ->: 
        ("oclcs" := rm.oclcs) ->: 
        ("publishDates" := rm.publishDates) ->: 
        jEmptyObject)
      )
    )
      

  implicit def VolumeEncodeJson: EncodeJson[VolumeMetadata] = 
    EncodeJson((vm: VolumeMetadata) =>
      Json("volume" ->
        (("url" := vm.record) ->: 
        ("htid" := vm.htid) ->: 
        ("orig" := vm.orig) ->: 
        ("rights" := vm.rights) ->: 
        ("lastUpdate" := vm.lastUpdate) ->: 
        ("enumcron" := vm.enumcron) ->: 
        ("usRights" := vm.usRights) ->: 
        ("record" := vm.record.id) ->: 
        jEmptyObject)
      )
    )

  def volumeToJson(metadata : VolumeMetadata) = { 
    metadata.asJson 
  } 

  def recordToJson(metadata : VolumeMetadata) = {
    metadata.record.asJson
  }

}