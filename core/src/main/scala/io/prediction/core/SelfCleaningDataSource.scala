package io.prediction.core

import grizzled.slf4j.Logger
import io.prediction.annotation.DeveloperApi
import io.prediction.data.storage.{DataMap, Event,Storage}
import io.prediction.data.store.{Common, LEventStore, PEventStore}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

/** :: DeveloperApi ::
  * Base class of cleaned data source.
  *
  * A cleaned data source consists tools for cleaning events that happened earlier that
  * specified duration in seconds from train moment. Also it can remove duplicates and compress
  * properties(flat set/unset events to one)
  *
  */
@DeveloperApi
trait SelfCleaningDataSource {

  @transient lazy private val pEventsDb = Storage.getPEvents()
  @transient lazy private val lEventsDb = Storage.getLEvents()

  /** :: DeveloperApi ::
    * Current App name which events will be cleaned.
    *
    * @return App name
    */
  @DeveloperApi
  def appName: String

  /** :: DeveloperApi ::
    * Param list that used for cleanup.
    *
    * @return current event windows that will be used to clean up events.
    */
  @DeveloperApi
  def eventWindow: Option[EventWindow] = None

  @transient lazy val logger = Logger[this.type]

  /** :: DeveloperApi ::
    *
    * Returns RDD of events happend after duration in event window params.
    *
    * @return RDD[Event] most recent PEvents.
    */
  @DeveloperApi
  def getCleanedPEvents(sc: SparkContext): RDD[Event] = {
    val pEvents = PEventStore.find(appName)(sc)

    eventWindow
      .flatMap(_.duration)
      .map { duration =>
        val fd = Duration(duration)
        pEvents.filter(e =>
          e.eventTime.isAfter(DateTime.now().minus(fd.toMillis))
        )
      }.getOrElse(pEvents)
  }

  /** :: DeveloperApi ::
    *
    * Returns Iterator of events happend after duration in event window params.
    *
    * @return Iterator[Event] most recent LEvents.
    */
  @DeveloperApi
  def getCleanedLEvents(): Iterable[Event] = {

    val lEvents = LEventStore.find(appName)

    eventWindow
      .flatMap(_.duration)
      .map { duration =>
        val fd = Duration(duration)
        lEvents.filter(e =>
          e.eventTime.isAfter(DateTime.now().minus(fd.toMillis))
        )
      }.getOrElse(lEvents).toIterable
  }

  def compressPProperties(sc: SparkContext, rdd: RDD[Event]): RDD[Event] = {
    rdd.filter(isSetEvent)
      .groupBy(_.entityType)
      .flatMap { pair =>
        val (_, ls) = pair
        ls.groupBy(_.entityId).map { anotherpair =>
          val (_, anotherls) = anotherpair 
          compress(anotherls)
        }
      } ++ rdd.filter(!isSetEvent(_))
  }

  def compressLProperties(events: Iterable[Event]): Iterable[Event] = {
    events.filter(isSetEvent).toIterable
      .groupBy(_.entityType)
      .map { pair =>
        val (_, ls) = pair
        compress(ls)
      } ++ events.filter(!isSetEvent(_))
  }

  //TODO ensure most recent is preserved
  def removePDuplicates(sc: SparkContext, rdd: RDD[Event]): RDD[Event] = {
    rdd.map(x => 
      (recreateEvent(x, None, x.eventTime), (x.eventId, x.creationTime)))
      .groupByKey.map{case (x, y) => recreateEvent(x, y.head._1, y.head._2)}

  }

  def recreateEvent(x: Event, eventId: Option[String], creationTime: DateTime): Event = {
    Event(eventId = eventId, event = x.event, entityType = x.entityType, 
          entityId = x.entityId, targetEntityType = x.targetEntityType, 
          targetEntityId = x.targetEntityId, properties = x.properties, 
          eventTime = x.eventTime, tags = x.tags, prId= x.prId, 
          creationTime = creationTime)  
  }


  def removeLDuplicates(ls: Iterable[Event]): Iterable[Event] = {
    ls.toList.map(x => 
      (recreateEvent(x, None, x.eventTime), (x.eventId, x.creationTime)))
      .groupBy(_._1).mapValues( _.map( _._2 ) )
      .map(x => recreateEvent(x._1, x._2.head._1, x._2.head._2))

  }

  /** :: DeveloperApi ::
    *
    * Filters most recent, compress properties and removes duplicates of PEvents
    *
    * @return RDD[Event] most recent PEvents
    */
  @DeveloperApi
  def cleanPersistedPEvents(sc: SparkContext): RDD[Event] ={
    val result = cleanPEvents(sc)
    val originalEvents = PEventStore.find(appName)(sc)
    val newEvents = result subtract originalEvents
    val eventsToRemove = (originalEvents subtract result).map { case e =>
      e.eventId.getOrElse("")
    }
    
    wipePEvents(newEvents, eventsToRemove, sc)
    result
  }
  
   /**
    * Replace events in Event Store
    *
    */
  def wipePEvents(
    newEvents: RDD[Event],
    eventsToRemove: RDD[String],
    sc: SparkContext
  ): Unit = {
    val (appId, channelId) = Common.appNameToId(appName, None)
  
    newEvents.foreach(println(_))

    pEventsDb.write(newEvents, appId)(sc)

    eventsToRemove.foreach(println(_))
  
    removeEvents(eventsToRemove.collect.toSet, appId)
  }

  def removeEvents(eventsToRemove: Set[String], appId: Int){
    val listOfFuture: List[Future[Boolean]] = eventsToRemove.filter(x =>  x != "").toList.map { case eventId =>
        lEventsDb.futureDelete(eventId, appId)
    }

    val futureOfList: Future[List[Boolean]] = Future.sequence(listOfFuture)
    Await.result(futureOfList, scala.concurrent.duration.Duration(5, "minutes"))
  }


   /**
    * Replace events in Event Store
    *
    * @param events new events
    * @param appId delete all events of appId
    * @param channelId delete all events of channelId
    */
  def wipe(
    newEvents: Set[Event],
    eventsToRemove: Set[String]
  ): Unit = { 
    val (appId, channelId) = Common.appNameToId(appName, None)
   
    val listOfFutureNewEvents: List[Future[String]] = newEvents.toList.map { case event =>
        lEventsDb.futureInsert(recreateEvent(event, None, event.eventTime), appId)
    } 

    val futureOfListNewEvents: Future[List[String]] = Future.sequence(listOfFutureNewEvents)
    Await.result(futureOfListNewEvents, scala.concurrent.duration.Duration(5, "minutes")) 

    removeEvents(eventsToRemove, appId)
  }


  /** :: DeveloperApi ::
    *
    * Filters most recent, compress properties of PEvents
    */
  @DeveloperApi
  def cleanPEvents(sc: SparkContext): RDD[Event] = {
    val rdd = getCleanedPEvents(sc)
    eventWindow match {
      case Some(ew) =>
        var updated =
          if (ew.compressProperties) compressPProperties(sc, rdd) else rdd
        
        val deduped = if (ew.removeDuplicates) removePDuplicates(sc,updated) else updated
        deduped
      case None =>
        rdd
    }
  }

  /** :: DeveloperApi ::
    *
    * Filters most recent, compress properties and removes duplicates of LEvents
    *
    * @return Iterator[Event] most recent LEvents
    */
  @DeveloperApi
  def cleanPersistedLEvents: Iterable[Event] = {
    val result = cleanLEvents().toSet
    val originalEvents = LEventStore.find(appName).toSet
    val newEvents = result -- originalEvents
    val eventsToRemove = (originalEvents -- result).map { case e =>
      e.eventId.getOrElse("")
    }

    wipe(newEvents, eventsToRemove)

    result
  }

  /** :: DeveloperApi ::
    *
    * Filters most recent, compress properties of LEvents
    */
  @DeveloperApi
  def cleanLEvents(): Iterable[Event] = {
    val ls = getCleanedLEvents()
    eventWindow match {
      case Some(ew) =>
        var updated =
          if (ew.compressProperties) compressLProperties(ls) else ls
          val deduped = if (ew.removeDuplicates) removeLDuplicates(updated) else updated
        deduped
      case None =>
        ls
    }
  }


  private def isSetEvent(e: Event): Boolean = {
    e.event == "$set" || e.event == "$unset"
  }

  //TODO: fix bug, assumes temporal ordering ?
  private def compress(events: Iterable[Event]): Event = {
    events.find(_.event == "$set") match {

      case Some(first) =>
        events.reduce { (e1, e2) =>
          val props = e2.event match {
            case "$set" =>
              e1.properties.fields ++ e2.properties.fields
            case "$unset" =>
              e1.properties.fields
                .filterKeys(f => !e2.properties.fields.contains(f))
          }
          e1.copy(properties = DataMap(props))
        }

      case None =>
        events.reduce { (e1, e2) =>
          e1.copy(properties =
            DataMap(e1.properties.fields ++ e2.properties.fields)
          )
        }
    }
  }
}

case class EventWindow(
  duration: Option[String] = None,
  removeDuplicates: Boolean = false,
  compressProperties: Boolean = false
)