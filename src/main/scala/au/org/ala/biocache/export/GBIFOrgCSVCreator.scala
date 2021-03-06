package au.org.ala.biocache.export

import au.org.ala.biocache.Config
import au.com.bytecode.opencsv.CSVWriter
import scala.io.Source
import java.io.FileWriter
import java.io.File
import util.parsing.json.JSON
import au.org.ala.biocache.load.SimpleLoader
import au.org.ala.biocache.util.OptionParser
import au.org.ala.biocache.cmd.Tool

/**
 * This utility generates a CSV listing of data resources to supply to GBIF.
 */
object GBIFOrgCSVCreator extends Tool {

  def cmd = "gbif-csv"
  def desc = "Generates a CSV to allow GBIF to import resources"

  def main(args: Array[String]): Unit = {

    var resourceUids = ""
    var fileName = ""

    val parser = new OptionParser(help) {
      arg("data-resource-uid", "Comma separated list of data resources or all", {
        v: String => resourceUids = v
      })
      arg("file-name", "The name of the file to create", {
        v: String => fileName = v
      })
    }

    if (parser.parse(args)) {
      val creator = new GBIFOrgCSVCreator
      if ("all".equals(resourceUids)) {
        //get a list of data resource uids from the index.
        //val uids = Config.indexDAO.getDistinctValues("*:*","data_resource_uid",200);
        val uids = creator.getDataResourceUids
        creator.create(fileName, uids)
      } else {
        creator.create(fileName, resourceUids.split(",").toList)
      }
      //shutdown the index so that we can exit naturally
      Config.indexDAO.shutdown
    }
  }
}

/**
 * Creates a CSV file required for GBIF
 */
class GBIFOrgCSVCreator {

  val technicalContactEmail = Config.technicalContact

  def create(fileName:String,dataResources:Seq[String]){
    println("Creating CSV for " + dataResources)
    val outWriter = new FileWriter(new File(fileName))
    val writer = new CSVWriter(outWriter, ',', '"')
    writer.writeNext(Array("organisationName","organisationDescription","organisationContactType", "organisationContactEmail","organisationNodeKey","resourceName","resourceDescription","resourceHomePageURL","resourceContactType","resourceContactEmail","dwcaAccessPointURL"))
    //if it has a data provider use it as the organisation name otherwise use the data resources name?
    val simpleLoader = new SimpleLoader
    dataResources.foreach( dr => {
      val map = simpleLoader.getDataResourceDetailsAsMap(dr)
      println(dr)
      //TO DO only add it if it has a publicly available DwCA (but waiting for a change to the collectory)
      //val archiveAvail = map("publicArchiveAvailable") //at the moment this still needs to be configured in the collectory
      val (organisationName:String, organisationDescription:String) = {
        val singleInst = getSingleInstitution(map)
        if(!singleInst.isEmpty){

          val instmap = simpleLoader.getInstitutionDetailsAsMap(singleInst.get)
          val desc = instmap("pubDescription")
          (instmap.getOrElse("name",""),if(desc == null) "" else desc)
          //(singleInst.getOrElse(""), instmap.getOrElse("pubDescription",""))

        } else if(map.contains("provider")){
          val provider = map("provider").asInstanceOf[Map[String,AnyRef]]
          //get the data provider details
          val providerUid = provider("uid").asInstanceOf[String]
          val dpmap = simpleLoader.getDataProviderDetailsAsMap(providerUid)
          val desc = dpmap("pubDescription")
          (dpmap("name"),if(desc == null) "" else desc)
         // (dpmap.getOrElse("name",""), dpmap.getOrElse("pubDescription",""))
        } else {
          (map.getOrElse("name",""), map.getOrElse("pubDescription",""))
        }
      }
      val name = map("name")
      val description = map("pubDescription")
      val url = map("websiteUrl")

      val accessPointURL = map("publicArchiveUrl")
      writer.writeNext(Array(organisationName, organisationDescription, "Technical",Config.technicalContact, "au", name, description,url,"Technical",Config.technicalContact, accessPointURL))
    })
    writer.flush
    writer.close
  }

  def getSingleInstitution(metadata:Map[String,String]) : Option[String] = {
    val linkedConsumers = metadata.get("linkedRecordConsumers").getOrElse(List()).asInstanceOf[List[Map[String, String]]]
    linkedConsumers.foreach(x => {
      val uid = x.get("uid")
      //println("LINKED CONSUMER UID: " + uid)
      if(!uid.isEmpty && uid.get.startsWith("in"))
        return uid
    })
    None
  }

  def getDataResourceUids : Seq[String] = {
    val url = Config.biocacheServiceUrl + "/occurrences/search?q=*:*&facets=data_resource_uid&pageSize=0&flimit=10000"
    val jsonString = Source.fromURL(url).getLines.mkString
    val json = JSON.parseFull(jsonString).get.asInstanceOf[Map[String, String]]
    val results = json.get("facetResults").get.asInstanceOf[List[Map[String, String]]].head.get("fieldResult").get.asInstanceOf[List[Map[String, String]]]
    results.map(facet => {
      val uid = facet.get("label").get
      println(uid)
      uid
    })
  }
}