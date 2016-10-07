package com.metl.model

import com.metl.liftAuthenticator._

import com.metl.data._
import com.metl.metl2011._
import com.metl.auth._
import com.metl.utils._

import _root_.net.liftweb.util._
import Helpers._
import _root_.net.liftweb.common._
import _root_.net.liftweb.http._
import _root_.net.liftweb.http.rest._
import _root_.net.liftweb.http.provider._
import _root_.net.liftweb.sitemap._
import _root_.net.liftweb.sitemap.Loc._
import com.metl.snippet._
import com.metl.view._
import com.metl.h2._

import net.liftweb.util.Props
import com.mongodb._
import net.liftweb.mongodb._

import scala.xml._

object SecurityListener extends Logger {
  import java.util.Date
  case class SessionRecord(username:String,ipAddress:String,started:Long,lastActivity:Long)
  val ipAddresses = new scala.collection.mutable.HashMap[String,SessionRecord]()
  object SafeSessionIdentifier extends SessionVar[String](nextFuncName)
  object IPAddress extends SessionVar[Option[String]](None)
  def activeSessions = ipAddresses.values.toList
  protected def sessionId:String = {
    SafeSessionIdentifier.is
    //S.session.map(_.uniqueId).getOrElse("unknown")
  }
  protected def user:String = {
    Globals.currentUser.is
  }
  def login:Unit = {
    if (user != "forbidden"){
      val now = new Date().getTime
      ipAddresses.remove(sessionId).map(rec => {
        ipAddresses += ((sessionId,rec.copy(username = user,lastActivity = now)))
      }).getOrElse({
        ipAddresses += ((sessionId,SessionRecord(user,"unknown",now,now)))
      })
      S.session.foreach(_.addSessionCleanup((s) => {
        SecurityListener.cleanupSession(s)
      }))
      info("%s :: %s logged in, ipAddress %s, userAgent: %s".format(sessionId,user,IPAddress.is,UserAgent.is))
    }
  }
  def cleanupSession(in:LiftSession):Unit = {
    val thisSessionId = sessionId //in.uniqueId
    ipAddresses.remove(thisSessionId).foreach(rec => {
      if (rec.username != "forbidden"){
          info("%s :: %s user session expired, ipAddress %s, userAgent: %s".format(thisSessionId,rec.username,Some(rec.ipAddress),UserAgent.is))
      }
    })
  }
  def maintainIPAddress(r:Req):Unit = {
    try {
      val oldIPAddress = IPAddress.is
      val newIPAddress = r.remoteAddr
      IPAddress(Some(newIPAddress))
      if (!oldIPAddress.exists(_ == newIPAddress)){
        val now = new Date().getTime
        ipAddresses.remove(sessionId).map(rec => {
          ipAddresses += ((sessionId,rec.copy(ipAddress = newIPAddress,lastActivity = now)))
        }).getOrElse({
          ipAddresses += ((sessionId,SessionRecord(user,newIPAddress,now,now)))
        })
        if (user != "forbidden")
          info("%s :: %s changed IP address %s, userAgent: %s".format(sessionId,user,Some(newIPAddress),UserAgent.is))
      }
    } catch {
      case e:StateInStatelessException => {}
      case e:Exception => {}
    }
  }
}

import java.nio.file.attribute.UserPrincipal
import javax.security.auth._
case class MeTLPrincipal(name:String) extends UserPrincipal{
  override def getName = name
}
case class MeTLRolePrincipal(name:String) extends java.security.Principal{
  override def getName = name
}

object MeTLXConfiguration extends PropertyReader with Logger {
  protected var configs:Map[String,Tuple2[ServerConfiguration,RoomProvider]] = Map.empty[String,Tuple2[ServerConfiguration,RoomProvider]]
  var clientConfig:Option[ClientConfiguration] = None
  var configurationProvider:Option[ConfigurationProvider] = None
  val updateGlobalFunc = (c:Conversation) => {
    debug("serverSide updateGlobalFunc: %s".format(c))
    getRoom("global",c.server.name,GlobalRoom(c.server.name)) ! ServerToLocalMeTLStanza(MeTLCommand(c.server,c.author,new java.util.Date().getTime,"/UPDATE_CONVERSATION_DETAILS",List(c.jid.toString)))
  }
  def getRoomProvider(name:String,filePath:String) = {
    val idleTimeout:Option[Long] = (XML.load(filePath) \\ "caches" \\ "roomLifetime" \\ "@miliseconds").headOption.map(_.text.toLong)// Some(30L * 60L * 1000L)
    val safetiedIdleTimeout = Some(idleTimeout.getOrElse(30 * 60 * 1000L))
    trace("creating history caching room provider with timeout: %s".format(safetiedIdleTimeout))
    new HistoryCachingRoomProvider(name,safetiedIdleTimeout)
  }
  protected def ifConfigured(in:NodeSeq,elementName:String,action:NodeSeq=>Unit, permitMultipleValues:Boolean = false):Unit = {
    (in \\ elementName).theSeq match {
      case Nil => {}
      case element :: Nil => action(element)
      case elements if permitMultipleValues => elements.foreach(element => action(element))
      case _ => throw new Exception("too many %s elements in configuration file".format(elementName))
    }
  }
  protected def ifConfiguredFromGroup(in:NodeSeq,elementToAction:Map[String,NodeSeq=>Unit]):Unit = {
    var oneIsConfigured = false
    elementToAction.keys.foreach(element => {
      if ((in \\ element).theSeq != Nil){
        if (!oneIsConfigured){
          ifConfigured(in,element,(n:NodeSeq) => {
            debug("configuring: %s".format(element))
            oneIsConfigured = true
            elementToAction(element)(n)
          })
        } else {
          throw new Exception("too many elements in configuration file: %s".format(elementToAction))
        }
      }
    })
  }
  def setupStackAdaptorFromFile(filePath:String) = {
    val propFile = XML.load(filePath)
    ifConfigured(propFile,"stackAdaptor",(n:NodeSeq) => {
      for (
        enabled <- tryo((n \ "@enabled").text.toBoolean);
        if enabled;
        mongoHost <- tryo((n \ "@mongoHost").text);
        mongoPort <- tryo((n \ "@mongoPort").text.toInt);
        mongoDb <- tryo((n \ "@mongoDb").text)
      ) yield {  
        val mo = new MongoOptions
        mo.socketTimeout = 10000
        mo.socketKeepAlive = true
        val srvr = new ServerAddress(mongoHost,mongoPort)
        MongoDB.defineDb(DefaultMongoIdentifier, new Mongo(srvr, mo), mongoDb)
        //construct standingCache from DB
        com.metl.model.Reputation.populateStandingMap
        //construct LiftActors for topics with history from DB
        com.metl.model.TopicManager.preloadAllTopics
        //ensure that the default topic is available
        com.metl.model.Topic.getDefaultValue
      }
    })
  }

  def setupClientConfigFromFile(filePath:String) = {
    val propFile = XML.load(filePath)
    val configurationProviderNodes = propFile \\ "serverConfiguration" \\ "securityProvider"
    ifConfiguredFromGroup(configurationProviderNodes,Map(
      "stableKeyProvider" -> {(n:NodeSeq) => {
        configurationProvider = Some(new StableKeyConfigurationProvider())
      }},
      "stableKeyWithRemoteCheckerProvider" -> {(n:NodeSeq) => {
        for (
          lp <- (n \ "@localPort").headOption.map(_.text.toInt);
          ls <- (n \ "@localScheme").headOption.map(_.text);
          rbh <- (n \ "@remoteBackendHost").headOption.map(_.text);
          rbp <- (n \ "@remoteBackendPort").headOption.map(_.text.toInt)
        ) yield {
          configurationProvider = Some(new StableKeyWithRemoteCheckerConfigurationProvider(ls,lp,rbh,rbp))
        }
      }},
      "staticKeyProvider" -> {(n:NodeSeq) => {
        for (
          ep <- (n \ "@ejabberdPassword").headOption.map(_.text);
          yu = (n \ "@yawsUsername").headOption.map(_.text);
          yp <- (n \ "@yawsPassword").headOption.map(_.text)
        ) yield {
          val eu = (n \ "@ejabberdUsername").headOption.map(_.text)
          configurationProvider = Some(new StaticKeyConfigurationProvider(eu,ep,yu,yp))
        }
      }}
    ));
    ifConfigured(propFile,"clientConfig",(n:NodeSeq) => {
      clientConfig = for (
        xd <- (n \ "xmppDomain").headOption.map(_.text);
        iu <- (n \ "imageUrl").headOption.map(_.text);
        xuser = "";
        xpass = ""
      ) yield {
        ClientConfiguration(xd,xuser,xpass,iu)
      }
    })
  }
  def setupAuthorizersFromFile(filePath:String) = {
    val propFile = XML.load(filePath)
    val authorizationNodes = propFile \\ "serverConfiguration" \\ "groupsProvider"
    ifConfigured(authorizationNodes,"selfGroups",(n:NodeSeq) => {
      Globals.groupsProviders = new SelfGroupsProvider() :: Globals.groupsProviders
    },false)
    ifConfigured(authorizationNodes,"flatFileGroups",(n:NodeSeq) => {
      Globals.groupsProviders = GroupsProvider.createFlatFileGroups(n) ::: Globals.groupsProviders
    },true)
    info("configured groupsProviders: %s".format(Globals.groupsProviders))
  }
  def setupClientAdaptorsFromFile(filePath:String) = {
    xmppServer = (for (
      cc <- clientConfig;
      propFile = XML.load(filePath);
      clientAdaptors <- (propFile \\ "clientAdaptors").headOption;
      exs <- (clientAdaptors \ "embeddedXmppServer").headOption;
      keystorePath <- (exs \ "@keystorePath").headOption.map(_.text);
      keystorePassword <- (exs \ "@keystorePassword").headOption.map(_.text) 
    ) yield {
      val exs = new EmbeddedXmppServer(cc.xmppDomain,keystorePath,keystorePassword)
      exs.initialize
      LiftRules.unloadHooks.append( () => exs.shutdown)
      exs
    })
  }
  def setupCachesFromFile(filePath:String) = {
    import net.sf.ehcache.config.{MemoryUnit}
    import net.sf.ehcache.store.{MemoryStoreEvictionPolicy}
    for (
      scn <- (XML.load(filePath) \\ "caches" \\ "resourceCache").headOption;
      heapSize <- (scn \\ "@heapSize").headOption.map(_.text.toLowerCase.trim.toInt);
      heapUnits <- (scn \\ "@heapUnits").headOption.map(_.text.toLowerCase.trim match {
        case "bytes" => MemoryUnit.BYTES
        case "kilobytes" => MemoryUnit.KILOBYTES
        case "megabytes" => MemoryUnit.MEGABYTES
        case "gigabytes" => MemoryUnit.GIGABYTES
        case _ => MemoryUnit.MEGABYTES
      });
      evictionPolicy <- (scn \\ "@evictionPolicy").headOption.map(_.text.toLowerCase.trim match {
        case "clock" => MemoryStoreEvictionPolicy.CLOCK
        case "fifo" => MemoryStoreEvictionPolicy.FIFO
        case "lfu" => MemoryStoreEvictionPolicy.LFU
        case "lru" => MemoryStoreEvictionPolicy.LRU
        case _ => MemoryStoreEvictionPolicy.LRU
      })
    ) yield {
      val cacheConfig = CacheConfig(heapSize,heapUnits,evictionPolicy)
      info("setting up resourceCaches with config: %s".format(cacheConfig))
      ServerConfiguration.setServerConfMutator(sc => new ResourceCachingAdaptor(sc,cacheConfig))
    }
  }
  def setupServersFromFile(filePath:String) = {
    MeTL2011ServerConfiguration.initialize
    MeTL2015ServerConfiguration.initialize
    LocalH2ServerConfiguration.initialize
    setupCachesFromFile(filePath)
    ServerConfiguration.loadServerConfigsFromFile(
      path = filePath,
      onConversationDetailsUpdated = updateGlobalFunc,
      messageBusCredentailsFunc = () => {
        (for (
          cc <- configurationProvider;
          creds <- cc.getPasswords("metlxMessageBus_"+new java.util.Date().getTime.toString)
        ) yield {
          debug("vending msgBusCreds: %s".format(creds))
          (creds._1,creds._2)
        }).getOrElse(("",""))
      },
      conversationListenerCredentialsFunc = () => {
        (for (
          cc <- configurationProvider;
          creds <- cc.getPasswords("metlxConversationListener_"+new java.util.Date().getTime.toString)
        ) yield {
          debug("vending convCreds: %s".format(creds))
          (creds._1,creds._2)
        }).getOrElse(("",""))
      },
      httpCredentialsFunc = () => {
        (for (
          cc <- configurationProvider;
          creds <- cc.getPasswords("metlxHttp_"+new java.util.Date().getTime.toString)
        ) yield {
          debug("vending httpCreds: %s".format(creds))
          (creds._3,creds._4)
        }).getOrElse(("",""))
      }
    )
    val servers = ServerConfiguration.getServerConfigurations
    configs = Map(servers.map(c => (c.name,(c,getRoomProvider(c.name,filePath)))):_*)
  }
  var xmppServer:Option[EmbeddedXmppServer] = None
  def initializeSystem = {
    Globals
    /*
    Props.mode match {
      case Props.RunModes.Production => Globals.isDevMode = false
      case _ => Globals.isDevMode = true
    }
    */
    // Setup RESTful endpoints (these are in view/Endpoints.scala)
    LiftRules.statelessDispatchTable.prepend(SystemRestHelper)
    LiftRules.statelessDispatchTable.prepend(MeTLRestHelper)
    LiftRules.statelessDispatchTable.prepend(WebMeTLRestHelper)

    LiftRules.dispatch.append(MeTLStatefulRestHelper)
    LiftRules.dispatch.append(WebMeTLStatefulRestHelper)

    setupAuthorizersFromFile(Globals.configurationFileLocation)
    setupClientConfigFromFile(Globals.configurationFileLocation)
    setupServersFromFile(Globals.configurationFileLocation)
    configs.values.foreach(c => LiftRules.unloadHooks.append(c._1.shutdown _))
    configs.values.foreach(c => {
      getRoom("global",c._1.name,GlobalRoom(c._1.name),true)
      debug("%s is now ready for use (%s)".format(c._1.name,c._1.isReady))
    })
    //setupStackAdaptorFromFile(Globals.configurationFileLocation)
    setupClientAdaptorsFromFile(Globals.configurationFileLocation)

    S.addAnalyzer((req,timeTaken,_entries) => {
      req.foreach(r => SecurityListener.maintainIPAddress(r))
    })
    LiftRules.dispatch.append(new BrightSparkIntegrationDispatch)
    LiftRules.statelessDispatch.append(new BrightSparkIntegrationStatelessDispatch)
    info(configs)
  }
  def listRooms(configName:String):List[String] = configs(configName)._2.list
  def getRoom(jid:String,configName:String):MeTLRoom = getRoom(jid,configName,RoomMetaDataUtils.fromJid(jid),false)
  def getRoom(jid:String,configName:String,roomMetaData:RoomMetaData):MeTLRoom = getRoom(jid,configName,roomMetaData,false)
  def getRoom(jid:String,configName:String,roomMetaData:RoomMetaData,eternal:Boolean):MeTLRoom = {
    configs(configName)._2.get(jid,roomMetaData,eternal)
  }
}

class TransientLoopbackAdaptor(configName:String,onConversationDetailsUpdated:Conversation=>Unit) extends ServerConfiguration(configName,"no_host",onConversationDetailsUpdated){
  val serializer = new PassthroughSerializer
  val messageBusProvider = new LoopbackMessageBusProvider
  override def getMessageBus(d:MessageBusDefinition) = messageBusProvider.getMessageBus(d)
  override def getHistory(jid:String) = History.empty
  override def getConversationForSlide(slideJid:String):String = ""
  override def searchForConversation(query:String) = List.empty[Conversation]
  override def detailsOfConversation(jid:String) = Conversation.empty
  override def createConversation(title:String,author:String) = Conversation.empty
  override def deleteConversation(jid:String):Conversation = Conversation.empty
  override def renameConversation(jid:String,newTitle:String):Conversation = Conversation.empty
  override def changePermissions(jid:String,newPermissions:Permissions):Conversation = Conversation.empty
  override def updateSubjectOfConversation(jid:String,newSubject:String):Conversation = Conversation.empty
  override def addSlideAtIndexOfConversation(jid:String,index:Int):Conversation = Conversation.empty
  override def reorderSlidesOfConversation(jid:String,newSlides:List[Slide]):Conversation = Conversation.empty
  override def updateConversation(jid:String,conversation:Conversation):Conversation = Conversation.empty
  override def getImage(jid:String,identity:String) = MeTLImage.empty
  override def getResource(url:String) = Array.empty[Byte]
  override def postResource(jid:String,userProposedId:String,data:Array[Byte]):String = ""
  override def getResource(jid:String,identifier:String):Array[Byte] = Array.empty[Byte]
  override def insertResource(jid:String,data:Array[Byte]):String = ""
  override def upsertResource(jid:String,identifier:String,data:Array[Byte]):String = ""
}

case class CacheConfig(heapSize:Int,heapUnits:net.sf.ehcache.config.MemoryUnit,memoryEvictionPolicy:net.sf.ehcache.store.MemoryStoreEvictionPolicy)

class ManagedCache[A <: Object,B <: Object](name:String,creationFunc:A=>B,cacheConfig:CacheConfig) extends Logger {
  import net.sf.ehcache.{Cache,CacheManager,Element,Status,Ehcache}
  import net.sf.ehcache.loader.{CacheLoader}
  import net.sf.ehcache.config.{CacheConfiguration,MemoryUnit}
  import net.sf.ehcache.store.{MemoryStoreEvictionPolicy}
  import java.util.Collection
  import scala.collection.JavaConversions._
  protected val cm = CacheManager.getInstance()
  val cacheName = "%s_%s".format(name,nextFuncName)
  val cacheConfiguration = new CacheConfiguration().name(cacheName).maxBytesLocalHeap(cacheConfig.heapSize,cacheConfig.heapUnits).eternal(false).memoryStoreEvictionPolicy(cacheConfig.memoryEvictionPolicy).diskPersistent(false).logging(false)
  val cache = new Cache(cacheConfiguration)
  cm.addCache(cache)
  class FuncCacheLoader extends CacheLoader {
    override def clone(cache:Ehcache):CacheLoader = new FuncCacheLoader 
    def dispose:Unit = {}
    def getName:String = getClass.getSimpleName
    def getStatus:Status = cache.getStatus
    def init:Unit = {}
    def load(key:Object):Object = key match {
      case k:A => {
        creationFunc(k).asInstanceOf[Object]
      }
      case _ => null
    }
    def load(key:Object,arg:Object):Object = load(key) // not yet sure what to do with this argument in this case
    def loadAll(keys:Collection[_]):java.util.Map[Object,Object] = Map(keys.toArray.toList.map(k => (k,load(k))):_*)
    def loadAll(keys:Collection[_],argument:Object):java.util.Map[Object,Object] = Map(keys.toArray.toList.map(k => (k,load(k,argument))):_*)
  }
  val loader = new FuncCacheLoader
  def get(key:A):B = {
    cache.getWithLoader(key,loader,null).getObjectValue.asInstanceOf[B]
  }
  def update(key:A,value:B):Unit = {
    cache.put(new Element(key,value))
  }
  def startup = try {
    cache.initialise
  } catch {
    case e:Exception => {
      warn("exception initializing ehcache: %s".format(e.getMessage))
    }
  }
  def shutdown = cache.dispose()
}

class ResourceCachingAdaptor(sc:ServerConfiguration,cacheConfig:CacheConfig) extends PassThroughAdaptor(sc){
  val imageCache = new ManagedCache[String,MeTLImage]("imageByIdentiity",((i:String)) => super.getImage(i),cacheConfig)
  val imageWithJidCache = new ManagedCache[Tuple2[String,String],MeTLImage]("imageByIdentityAndJid",(ji) => super.getImage(ji._1,ji._2),cacheConfig)
  val resourceCache = new ManagedCache[String,Array[Byte]]("resourceByIdentity",(i:String) => super.getResource(i),cacheConfig)
  val resourceWithJidCache = new ManagedCache[Tuple2[String,String],Array[Byte]]("resourceByIdentityAndJid",(ji) => super.getResource(ji._1,ji._2),cacheConfig)
  override def getImage(jid:String,identity:String) = {
    imageWithJidCache.get((jid,identity))
  }
  override def postResource(jid:String,userProposedId:String,data:Array[Byte]):String = {
    val res = super.postResource(jid,userProposedId,data)
    resourceWithJidCache.update((jid,res),data)
    res
  }
  override def getResource(jid:String,identifier:String):Array[Byte] = {
    resourceWithJidCache.get((jid,identifier))
  }
  override def insertResource(jid:String,data:Array[Byte]):String = {
    val res = super.insertResource(jid,data)
    resourceWithJidCache.update((jid,res),data)
    res
  }
  override def upsertResource(jid:String,identifier:String,data:Array[Byte]):String = {
    val res = super.upsertResource(jid,identifier,data)
    resourceWithJidCache.update((jid,res),data)
    res
  }
  override def getImage(identity:String) = {
    imageCache.get(identity)
  }
  override def getResource(identifier:String):Array[Byte] = {
    resourceCache.get(identifier)
  }
  override def insertResource(data:Array[Byte]):String = {
    val res = super.insertResource(data)
    resourceCache.update(res,data)
    res
  }
  override def upsertResource(identifier:String,data:Array[Byte]):String = {
    val res = super.upsertResource(identifier,data)
    resourceCache.update(res,data)
    res
  }
  override def shutdown:Unit = {
    super.shutdown
    imageCache.shutdown
    imageWithJidCache.shutdown
    resourceCache.shutdown
    resourceWithJidCache.shutdown
  }
  protected lazy val initialize = {
    resourceWithJidCache.startup
    resourceCache.startup
    imageWithJidCache.startup
    imageCache.startup
  }
  override def isReady:Boolean = {
    initialize
    super.isReady
  }
}
