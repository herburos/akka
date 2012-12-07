/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.dispatch._
import akka.event.{ Logging, LoggingAdapter, EventStream }
import akka.event.Logging.Error
import akka.serialization.{ Serialization, SerializationExtension }
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Remote ActorRefProvider. Starts up actor on remote node and creates a RemoteActorRef representing it.
 */
class RemoteActorRefProvider(
  val systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  val dynamicAccess: DynamicAccess) extends ActorRefProvider {

  val remoteSettings: RemoteSettings = new RemoteSettings(settings.config, systemName)

  val deployer: RemoteDeployer = new RemoteDeployer(settings, dynamicAccess)

  private val local = new LocalActorRefProvider(systemName, settings, eventStream, scheduler, dynamicAccess, deployer)

  @volatile
  private var _log = local.log
  def log: LoggingAdapter = _log

  @volatile
  private var _rootPath = local.rootPath
  override def rootPath: ActorPath = _rootPath
  override def deadLetters: InternalActorRef = local.deadLetters

  // these are only available after init()
  override def rootGuardian: InternalActorRef = local.rootGuardian
  override def guardian: LocalActorRef = local.guardian
  override def systemGuardian: LocalActorRef = local.systemGuardian
  override def terminationFuture: Future[Unit] = local.terminationFuture
  override def dispatcher: MessageDispatcher = local.dispatcher
  override def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit = local.registerTempActor(actorRef, path)
  override def unregisterTempActor(path: ActorPath): Unit = local.unregisterTempActor(path)
  override def tempPath(): ActorPath = local.tempPath()
  override def tempContainer: VirtualPathContainer = local.tempContainer

  @volatile
  private var _transport: RemoteTransport = _
  def transport: RemoteTransport = _transport

  @volatile
  private var _serialization: Serialization = _
  def serialization: Serialization = _serialization

  @volatile
  private var _remoteDaemon: InternalActorRef = _
  def remoteDaemon: InternalActorRef = _remoteDaemon

  def init(system: ActorSystemImpl): Unit = {
    local.init(system)

    _remoteDaemon = new RemoteSystemDaemon(system, local.rootPath / "remote", rootGuardian, log, untrustedMode = remoteSettings.UntrustedMode)
    local.registerExtraNames(Map(("remote", remoteDaemon)))

    _serialization = SerializationExtension(system)

    _transport = {
      val fqn = remoteSettings.RemoteTransport
      val args = List(
        classOf[ExtendedActorSystem] -> system,
        classOf[RemoteActorRefProvider] -> this)

      system.dynamicAccess.createInstanceFor[RemoteTransport](fqn, args).recover({
        case problem ⇒ throw new RemoteTransportException("Could not load remote transport layer " + fqn, problem)
      }).get
    }

    _log = Logging(eventStream, "RemoteActorRefProvider")

    // this enables reception of remote requests
    _transport.start()

    _rootPath = RootActorPath(local.rootPath.address.copy(
      protocol = transport.defaultAddress.protocol,
      host = transport.defaultAddress.host,
      port = transport.defaultAddress.port))

    val remoteClientLifeCycleHandler = system.systemActorOf(Props(new Actor {
      def receive = {
        case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
        case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
        case _                                         ⇒ //ignore other
      }
    }), "RemoteClientLifeCycleListener")

    system.eventStream.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

    system.registerOnTermination(transport.shutdown())
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath,
              systemService: Boolean, deploy: Option[Deploy], lookupDeploy: Boolean, async: Boolean): InternalActorRef = {
    if (systemService) local.actorOf(system, props, supervisor, path, systemService, deploy, lookupDeploy, async)
    else {

      /*
       * This needs to deal with “mangled” paths, which are created by remote
       * deployment, also in this method. The scheme is the following:
       *
       * Whenever a remote deployment is found, create a path on that remote
       * address below “remote”, including the current system’s identification
       * as “sys@host:port” (typically; it will use whatever the remote
       * transport uses). This means that on a path up an actor tree each node
       * change introduces one layer or “remote/scheme/sys@host:port/” within the URI.
       *
       * Example:
       *
       * akka://sys@home:1234/remote/akka/sys@remote:6667/remote/akka/sys@other:3333/user/a/b/c
       *
       * means that the logical parent originates from “akka://sys@other:3333” with
       * one child (may be “a” or “b”) being deployed on “akka://sys@remote:6667” and
       * finally either “b” or “c” being created on “akka://sys@home:1234”, where
       * this whole thing actually resides. Thus, the logical path is
       * “/user/a/b/c” and the physical path contains all remote placement
       * information.
       *
       * Deployments are always looked up using the logical path, which is the
       * purpose of the lookupRemotes internal method.
       */

      @scala.annotation.tailrec
      def lookupRemotes(p: Iterable[String]): Option[Deploy] = {
        p.headOption match {
          case None           ⇒ None
          case Some("remote") ⇒ lookupRemotes(p.drop(3))
          case Some("user")   ⇒ deployer.lookup(p.drop(1))
          case Some(_)        ⇒ None
        }
      }

      val elems = path.elements
      val lookup =
        if (lookupDeploy)
          elems.head match {
            case "user"   ⇒ deployer.lookup(elems.drop(1))
            case "remote" ⇒ lookupRemotes(elems)
            case _        ⇒ None
          }
        else None

      val deployment = {
        deploy.toList ::: lookup.toList match {
          case Nil ⇒ Nil
          case l   ⇒ List(l reduce ((a, b) ⇒ b withFallback a))
        }
      }

      Iterator(props.deploy) ++ deployment.iterator reduce ((a, b) ⇒ b withFallback a) match {
        case d @ Deploy(_, _, _, RemoteScope(addr)) ⇒
          if (hasAddress(addr)) {
            local.actorOf(system, props, supervisor, path, false, deployment.headOption, false, async)
          } else {
            try {
              val localAddress = transport.localAddressForRemote(addr)
              val rpath = RootActorPath(addr) / "remote" / localAddress.protocol / localAddress.hostPort / path.elements
              new RemoteActorRef(this, transport, localAddress, rpath, supervisor, Some(props), Some(d))
            } catch {
              case NonFatal(e) ⇒
                log.error(e, "Error while looking up address {}", addr)
                new EmptyLocalActorRef(this, path, eventStream)
            }
          }

        case _ ⇒ local.actorOf(system, props, supervisor, path, systemService, deployment.headOption, false, async)
      }
    }
  }

  def actorFor(path: ActorPath): InternalActorRef = {
    if (hasAddress(path.address)) actorFor(rootGuardian, path.elements)
    else try {
      new RemoteActorRef(this, transport, transport.localAddressForRemote(path.address),
        path, Nobody, props = None, deploy = None)
    } catch {
      case NonFatal(e) ⇒
        log.error(e, "Error while looking up address {}", path.address)
        new EmptyLocalActorRef(this, path, eventStream)
    }
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (hasAddress(address)) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, transport, transport.localAddressForRemote(address),
        new RootActorPath(address) / elems, Nobody, props = None, deploy = None)
    case _ ⇒ local.actorFor(ref, path)
  }

  /*
   * INTERNAL API
   * Called in deserialization of incoming remote messages. In this case the correct local address is known, therefore
   * this method is faster than the actorFor above.
   */
  def actorForWithLocalAddress(ref: InternalActorRef, path: String, localAddress: Address): InternalActorRef = path match {
    case ActorPathExtractor(address, elems) ⇒
      if (hasAddress(address)) actorFor(rootGuardian, elems)
      else new RemoteActorRef(this, transport, localAddress,
        new RootActorPath(address) / elems, Nobody, props = None, deploy = None)
    case _ ⇒ local.actorFor(ref, path)
  }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef = local.actorFor(ref, path)

  /**
   * Using (checking out) actor on a specific node.
   */
  def useActorOnNode(path: ActorPath, props: Props, deploy: Deploy, supervisor: ActorRef): Unit = {
    log.debug("[{}] Instantiating Remote Actor [{}]", rootPath, path)

    // we don’t wait for the ACK, because the remote end will process this command before any other message to the new actor
    actorFor(RootActorPath(path.address) / "remote") ! DaemonMsgCreate(props, deploy, path.toString, supervisor)
  }

  def getExternalAddressFor(addr: Address): Option[Address] = {
    addr match {
      case _ if hasAddress(addr)             ⇒ Some(local.rootPath.address)
      case Address("akka", _, Some(_), Some(_)) ⇒ Some(transport.localAddressForRemote(addr))
      case _                                    ⇒ None
    }
  }

  private def hasAddress(address: Address): Boolean =
    address == local.rootPath.address || address == rootPath.address || transport.addresses(address)

}

private[akka] trait RemoteRef extends ActorRefScope {
  final def isLocal = false
}

/**
 * Remote ActorRef that is used when referencing the Actor on a different node than its "home" node.
 * This reference is network-aware (remembers its origin) and immutable.
 */
private[akka] class RemoteActorRef private[akka] (
  val provider: RemoteActorRefProvider,
  remote: RemoteTransport,
  val localAddressToUse: Address,
  val path: ActorPath,
  val getParent: InternalActorRef,
  props: Option[Props],
  deploy: Option[Deploy])
  extends InternalActorRef with RemoteRef {

  def getChild(name: Iterator[String]): InternalActorRef = {
    val s = name.toStream
    s.headOption match {
      case None       ⇒ this
      case Some("..") ⇒ getParent getChild name
      case _          ⇒ new RemoteActorRef(provider, remote, localAddressToUse, path / s, Nobody, props = None, deploy = None)
    }
  }

  def isTerminated: Boolean = false

  def sendSystemMessage(message: SystemMessage): Unit =
    try remote.send(message, None, this)
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        remote.system.eventStream.publish(Error(e, path.toString, classOf[RemoteActorRef], "swallowing exception during message send"))
        provider.deadLetters ! message
    }

  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit =
    try remote.send(message, Option(sender), this)
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        remote.system.eventStream.publish(Error(e, path.toString, classOf[RemoteActorRef], "swallowing exception during message send"))
        provider.deadLetters ! message
    }

  def start(): Unit = if (props.isDefined && deploy.isDefined) provider.useActorOnNode(path, props.get, deploy.get, getParent)

  def suspend(): Unit = sendSystemMessage(Suspend())

  def resume(causedByFailure: Throwable): Unit = sendSystemMessage(Resume(causedByFailure))

  def stop(): Unit = sendSystemMessage(Terminate())

  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))

  @throws(classOf[java.io.ObjectStreamException])
  private def writeReplace(): AnyRef = SerializedActorRef(path)
}
