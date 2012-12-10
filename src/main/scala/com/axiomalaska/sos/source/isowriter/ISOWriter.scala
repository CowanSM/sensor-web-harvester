/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.axiomalaska.sos.source.isowriter

import com.axiomalaska.sos.source.StationQuery
import com.axiomalaska.sos.source.data.LocalStation
import java.io.File
import java.util.Calendar
import org.apache.log4j.Logger

trait ISOWriter {
  def writeISOFile(station: LocalStation)
}

class ISOWriterImpl(private val stationQuery: StationQuery,
    private val isoDirectory: String,
    private val logger: Logger = Logger.getRootLogger()) extends ISOWriter {

  private val templateFile = isoDirectory + "/iso_template.xml"
  private val currentDate = Calendar.getInstance
  
  def writeISOFile(station: LocalStation) {
    // do any initial info gathering, also can return T/F on whether or not
    // setup was successful
    if (initialSetup(station)) {
      // read in template
      val isoTemplate = readInTemplate
      isoTemplate match {
        case Some(isoTemplate) => {
            // collect necessary information...
            val stationName = getStationName(station)
            val stationID = getStationID(station)
            val geoPosition = getStationGeographicExtents(station)
            val tempPosition = getStationTemporalExtents(station)
            val stationAbstract = getStationAbstract(station)
            val sensorTagAndNames = getSensorTagsAndNames(station)
            // write the to the xml
            // write the extents
            var finishedXML = writeStationExtentsAndInfo(isoTemplate, stationName, stationID, stationAbstract, geoPosition, tempPosition)
            // write the keywords and contentinfo
            finishedXML = writeKeywordsContent(finishedXML.head, sensorTagAndNames)
            // write the xml to file
            val fileName = isoDirectory + "/" + station.getId + ".xml"
            scala.xml.XML.save(fileName, finishedXML.head)
            logger info "wrote iso file to " + fileName
        }
        case None => {
            // unable to load template
            logger error "Unable to load the template for station " + station.getId
        }
      }
    } else {
      logger error "Iso writing was aborted during initial setup"
    }
  }
  
  // The below should be overwritten in the subclasses /////////////////////////
  protected def initialSetup(station: LocalStation) : Boolean = { true }
  
  protected def getStationName(station: LocalStation) : String = {
    return station.getName
  }
  
  protected def getStationID(station: LocalStation) : String = {
    return station.getId
  }
  
  protected def getStationGeographicExtents(station: LocalStation) : (Double,Double) = {
    return (station.getLocation.getLatitude, station.getLocation.getLongitude)
  }
  
  protected def getStationTemporalExtents(station: LocalStation) : (Calendar,Calendar) = {
    return (currentDate, currentDate)
  }
  
  protected def getStationAbstract(station: LocalStation) : String = {
    return station.getDescription
  }
  
  protected def getSensorTagsAndNames(station: LocalStation) : List[(String,String)] = {
    val retval = for (i <- 0 until station.getSensors.size) yield {
      val slist = for (j <- 0 until station.getSensors.get(i).getPhenomena.size) yield {
        val tag = station.getSensors.get(i).getPhenomena.get(j).getId
        val name = station.getSensors.get(i).getPhenomena.get(j).getName
        (tag,name)
      }
      slist.toList
    }
    retval.toList.flatten
  }
  //////////////////////////////////////////////////////////////////////////////
  
  private def readInTemplate() : Option[scala.xml.Elem] = {
    try {
      Some(scala.xml.XML.loadFile(new File(templateFile)))
    } catch{
      case ex: Exception => {
          logger error "Unable to load file into xml: " + isoDirectory + "/iso_template.xml\n" + ex.toString
          None
      }
    }
  }
  
  private def writeStationExtentsAndInfo(xml: scala.xml.Elem, stName: String, stId: String,
                                         stAbstract: String, geoPos: (Double,Double),
                                         temporalPos: (Calendar,Calendar)) : Seq[scala.xml.Node] = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case lon: scala.xml.Elem if lon.label.toLowerCase.contains("longitude") => writeGCODecimal(lon, geoPos._2)
        case lat: scala.xml.Elem if lat.label.toLowerCase.contains("latitude") => writeGCODecimal(lat, geoPos._1)
        case pos: scala.xml.Elem if pos.label.toLowerCase.equals("ex_temporalextent") => writeGMLPositions(pos, temporalPos._1, temporalPos._2)
        case dt: scala.xml.Elem if dt.label.toLowerCase.equals("ci_date") => writeGCODateTime(dt, currentDate)
        case d: scala.xml.Elem if d.label.toLowerCase.equals("datestamp") => writeGCODate(d, currentDate)
        case fi: scala.xml.Elem if fi.label.toLowerCase.equals("fileidentifier") => writeGCOCharacterString(fi, stId)
        case title: scala.xml.Elem if title.label.toLowerCase.equals("title") => writeGCOCharacterString(title, stName)
        case abst: scala.xml.Elem if abst.label.toLowerCase.equals("abstract") => writeGCOCharacterString(abst, stAbstract)
        case auth: scala.xml.Elem if auth.label.toLowerCase.equals("authority") => auth   // authority shares a named node that we don't want to write to, skip it
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform xml
  }
  
  private def writeKeywordsContent(xml: scala.xml.Node, tandN: List[(String,String)]) : Seq[scala.xml.Node] = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case kywrds: scala.xml.Elem if kywrds.label.toLowerCase.equals("md_keywords") => {
            var elem = kywrds
            tandN foreach (t => elem = addKeyword(elem, t._2))
            elem
        }
        case cvrg: scala.xml.Elem if cvrg.label.toLowerCase.equals("mi_coveragedescription") => {
            var elem = cvrg
            tandN foreach (tn => elem = addDimension(elem, tn._1, tn._2))
            elem
        }
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      } 
    } transform xml
  }
  
  private def addKeyword(node: scala.xml.Elem, sKeyword: String) : scala.xml.Elem = {
    val newChild = <gmd:keyword>
        <gco:CharacterString>{sKeyword}</gco:CharacterString>
      </gmd:keyword>
    scala.xml.Elem(node.prefix, node.label, node.attributes, node.scope, node.child ++ newChild : _*)
  }
  
  private def addDimension(node:scala.xml.Elem, tag: String, desc: String) : scala.xml.Elem = {
    val newChild = <gmd:dimension>
        <gmd:MD_Band>
          <gmd:sequenceIdentifier>
            <gco:MemberName>
              <gco:aName>
                <gco:CharacterString>{tag}</gco:CharacterString>
              </gco:aName>
            </gco:MemberName>
          </gmd:sequenceIdentifier>
          <gmd:descriptor>
            <gco:CharacterString>{desc}</gco:CharacterString>
          </gmd:descriptor>
        </gmd:MD_Band>
      </gmd:dimension>
    scala.xml.Elem(node.prefix, node.label, node.attributes, node.scope, node.child ++ newChild : _*)
  }
  
  private def writeGCODecimal(elem: scala.xml.Elem, decimal: Double) : scala.xml.Node = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case dec: scala.xml.Node if dec.label.toLowerCase.equals("decimal") =>
          <gco:Decimal>{decimal.toString}</gco:Decimal>
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform elem head
  }
  
  private def writeGMLPositions(elem: scala.xml.Elem, datetimeStart: Calendar, datetimeEnd: Calendar) : scala.xml.Node = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case start: scala.xml.Node if start.label.toLowerCase.equals("beginposition") && datetimeStart != null =>
          <gml:beginPosition>{formatDateTime(datetimeStart)}</gml:beginPosition>
        case end: scala.xml.Node if end.label.toLowerCase.equals("endposition") && datetimeEnd != null =>
          <gml:endPosition>{formatDateTime(datetimeEnd)}</gml:endPosition>
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform elem head
  }
  
  private def writeGCODateTime(elem: scala.xml.Elem, datetime: Calendar) : scala.xml.Node = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case dec: scala.xml.Node if dec.label.toLowerCase.equals("datetime") =>
          <gco:DateTime>{formatDateTime(datetime)}</gco:DateTime>
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform elem head
  }
  
  private def writeGCODate(elem: scala.xml.Elem, datetime: Calendar) : scala.xml.Node = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case dec: scala.xml.Node if dec.label.toLowerCase.equals("date") =>
          <gco:Date>{formatDate(datetime)}</gco:Date>
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform elem head
  }
  
  private def writeGCOCharacterString(elem: scala.xml.Elem, cstring: String) : scala.xml.Node = {
    new scala.xml.transform.RewriteRule {
      override def transform(n: scala.xml.Node): scala.xml.Node = n match {
        case dec: scala.xml.Node if dec.label.toLowerCase.equals("characterstring") =>
          <gco:CharacterString>{cstring}</gco:CharacterString>
        case elem: scala.xml.Elem => elem copy(child = elem.child flatMap (this transform))
        case other => other
      }
    } transform elem head
  }
  
  private def formatDateTime(datetime: Calendar) : String = datetime.get(Calendar.YEAR) + "-" +
    (if(datetime.get(Calendar.MONTH)+1 < 10) "0" else "") + (datetime.get(Calendar.MONTH)+1) + "-" +
      (if(datetime.get(Calendar.DAY_OF_MONTH) < 10) "0" else "") + datetime.get(Calendar.DAY_OF_MONTH) + "T" +
      (if(datetime.get(Calendar.HOUR_OF_DAY) < 10) "0" else "") +datetime.get(Calendar.HOUR_OF_DAY) + ":" +
      (if(datetime.get(Calendar.MINUTE) < 10) "0" else "") + datetime.get(Calendar.MINUTE) + ":" +
      (if(datetime.get(Calendar.SECOND) < 10) "0" else "") + datetime.get(Calendar.SECOND) + "." +
      (if(datetime.get(Calendar.MILLISECOND) < 100) (if(datetime.get(Calendar.MILLISECOND) < 10) "00" else "0") else "") +
      datetime.get(Calendar.MILLISECOND) + "Z"
  
  private def formatDate(date: Calendar) : String = date.get(Calendar.YEAR) + "-" +
    (if(date.get(Calendar.MONTH)+1 < 10) "0" else "") + (date.get(Calendar.MONTH) + 1) + "-" +
    (if(date.get(Calendar.DAY_OF_MONTH) < 10) "0" else "") + date.get(Calendar.DAY_OF_MONTH)
}