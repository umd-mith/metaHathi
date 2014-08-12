package org.mith.metaHathi.utils

case class Column(val idx: Int, val name: String)

case class ChangedRecord(val url: String, val changes: List[Change])

case class Change(val url: String, val field: String, val old: String, val nw: String)