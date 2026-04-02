package bidc

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.Thenable.Implicits.*
import scala.concurrent.{ExecutionContext, Future, Promise as ScalaPromise}
import scala.collection.mutable

// ── Public API ──────────────────────────────────────────────────────

/** Bidirectional Channels for Scala.js.
  *
  * Port of https://github.com/vercel/bidc
  */
object Bidc:

  /** Create a channel to the parent context (window.parent or worker self). */
  def createChannel()(using ExecutionContext): Channel =
    new ChannelImpl(None, "default")

  /** Create a channel to a target (Worker, Window, MessagePort, etc.). */
  def createChannel(target: js.Any)(using ExecutionContext): Channel =
    new ChannelImpl(Some(target), "default")

  /** Create a namespaced channel to a target. */
  def createChannel(target: js.Any, channelId: String)(using ExecutionContext): Channel =
    new ChannelImpl(Some(target), channelId)

  /** Create a namespaced channel to the parent context. */
  def createChannelWithId(channelId: String)(using ExecutionContext): Channel =
    new ChannelImpl(None, channelId)

trait Channel:
  /** Send data and await the response from the other side. */
  def send(data: js.Any): Future[js.Any]

  /** Register a handler for incoming messages. Return value is sent back as
    * the response. If the return value contains `js.Promise` instances, they
    * are streamed to the sender as they resolve.
    */
  def receive(callback: js.Any => js.Any): Unit

  /** Tear down listeners and close the connection. */
  def cleanup(): Unit

// ── Serialization ───────────────────────────────────────────────────

private object Serializer:

  /** Serialize a value to JSON, replacing Promises with placeholders. */
  def stringify(value: js.Any, promises: PromiseCollector): String =
    js.JSON.stringify(replaceSpecial(value, promises))

  /** Deserialize JSON back to a value, creating live js.Promises for
    * placeholders.
    */
  def parse(
      text: String,
      resolvers: mutable.Map[String, PromiseResolver]
  )(using ExecutionContext): js.Any =
    restoreSpecial(js.JSON.parse(text), resolvers)

  // -- helpers --

  private def replaceSpecial(
      value: js.Any,
      promises: PromiseCollector
  ): js.Any =
    if value == null then null
    else if js.isUndefined(value) then
      js.Dynamic.literal("$" -> "U")
    else if isThenable(value) then
      val id = promises.track(value.asInstanceOf[js.Thenable[js.Any]])
      js.Dynamic.literal("$" -> "P", "id" -> id).asInstanceOf[js.Any]
    else if js.typeOf(value) == "object" && value
        .isInstanceOf[js.Date] then
      js.Dynamic
        .literal(
          "$" -> "D",
          "v" -> value.asInstanceOf[js.Date].toISOString()
        )
        .asInstanceOf[js.Any]
    else if js.Array.isArray(value) then
      value
        .asInstanceOf[js.Array[js.Any]]
        .map(v => replaceSpecial(v, promises))
        .asInstanceOf[js.Any]
    else if js.typeOf(value) == "object" then
      val obj = value.asInstanceOf[js.Object]
      val result = js.Dynamic.literal()
      js.Object.keys(obj).foreach { key =>
        val v =
          obj.asInstanceOf[js.Dynamic].selectDynamic(key).asInstanceOf[js.Any]
        result.updateDynamic(key)(replaceSpecial(v, promises))
      }
      result.asInstanceOf[js.Any]
    else value // string, number, boolean

  private def restoreSpecial(
      value: js.Any,
      resolvers: mutable.Map[String, PromiseResolver]
  )(using ExecutionContext): js.Any =
    if value == null || js.isUndefined(value) then value
    else if js.Array.isArray(value) then
      value
        .asInstanceOf[js.Array[js.Any]]
        .map(v => restoreSpecial(v, resolvers))
        .asInstanceOf[js.Any]
    else if js.typeOf(value) == "object" then
      val obj = value.asInstanceOf[js.Dynamic]
      val marker = obj.selectDynamic("$")
      if !js.isUndefined(marker) then
        marker.asInstanceOf[String] match
          case "U" => js.undefined.asInstanceOf[js.Any]
          case "P" =>
            val id = obj.id.asInstanceOf[String]
            resolvers
              .getOrElseUpdate(id, new PromiseResolver)
              .jsPromise
              .asInstanceOf[js.Any]
          case "D" =>
            new js.Date(obj.v.asInstanceOf[String]).asInstanceOf[js.Any]
          case _ => value
      else
        val result = js.Dynamic.literal()
        js.Object.keys(value.asInstanceOf[js.Object]).foreach { key =>
          val v = obj.selectDynamic(key).asInstanceOf[js.Any]
          result.updateDynamic(key)(restoreSpecial(v, resolvers))
        }
        result.asInstanceOf[js.Any]
    else value

  private def isThenable(value: js.Any): Boolean =
    value != null && !js.isUndefined(value) &&
      (js.typeOf(value) == "object" || js.typeOf(value) == "function") &&
      js.typeOf(
        value.asInstanceOf[js.Dynamic].selectDynamic("then")
      ) == "function"

end Serializer

// ── Promise bookkeeping ─────────────────────────────────────────────

private class PromiseCollector:
  // js.Map for identity-based key comparison on JS objects
  // get/set/has are on the Raw trait which is private[js], so we use Dynamic
  private val ids = new js.Map[js.Any, String]().asInstanceOf[js.Dynamic]
  private val futures = mutable.Map.empty[String, Future[js.Any]]
  private var nextId = 0

  def track(thenable: js.Thenable[js.Any]): String =
    val key = thenable.asInstanceOf[js.Any]
    if ids.has(key).asInstanceOf[Boolean] then
      ids.get(key).asInstanceOf[String]
    else
      val id = nextId.toString
      nextId += 1
      ids.set(key, id)
      futures(id) = thenable.toFuture
      id

  def pendingExcluding(resolved: Set[String]): Map[String, Future[js.Any]] =
    futures.view.filterKeys(id => !resolved.contains(id)).toMap

end PromiseCollector

private class PromiseResolver(using ec: ExecutionContext):
  private val underlying = ScalaPromise[js.Any]()
  val future: Future[js.Any] = underlying.future
  val jsPromise: js.Promise[js.Any] = new js.Promise[js.Any](
    (resolve, reject) =>
      future.onComplete {
        case scala.util.Success(v) => resolve(v)
        case scala.util.Failure(e) =>
          reject(js.special.unwrapFromThrowable(e))
      }
  )

  def resolve(value: js.Any): Unit =
    if !underlying.isCompleted then underlying.success(value)

  def reject(error: Throwable): Unit =
    if !underlying.isCompleted then underlying.failure(error)

// ── Streaming Protocol ──────────────────────────────────────────────

private object Protocol:

  /** Encode a value into streaming chunks. Calls `onChunk` synchronously for
    * the first chunk and asynchronously as nested promises resolve.
    */
  def encode(value: js.Any, onChunk: String => Unit)(using ExecutionContext): Future[Unit] =
    val collector = new PromiseCollector
    val serialized = Serializer.stringify(value, collector)
    onChunk(s"r:$serialized")
    val resolved = mutable.Set.empty[String]
    drain(collector, resolved, onChunk)

  private def drain(
      collector: PromiseCollector,
      resolved: mutable.Set[String],
      onChunk: String => Unit
  )(using ExecutionContext): Future[Unit] =
    val pending = collector.pendingExcluding(resolved.toSet)
    if pending.isEmpty then Future.unit
    else
      val racers = pending.map { case (id, fut) =>
        fut.transform {
          case scala.util.Success(v) =>
            scala.util.Success((id, Right(v): Either[Throwable, js.Any]))
          case scala.util.Failure(e) =>
            scala.util.Success((id, Left(e): Either[Throwable, js.Any]))
        }
      }.toSeq
      Future.firstCompletedOf(racers).flatMap { case (id, result) =>
        resolved.add(id)
        result match
          case Right(value) =>
            val serialized = Serializer.stringify(value, collector)
            onChunk(s"p$id:$serialized")
          case Left(error) =>
            val jsErr = js.special.unwrapFromThrowable(error)
            val msg = jsErr match
              case s: String => s
              case _         => Option(error.getMessage).getOrElse(error.toString)
            onChunk(s"e$id:${js.JSON.stringify(msg)}")
        drain(collector, resolved, onChunk)
      }

  /** Stateful decoder that processes incoming chunks and resolves promise
    * placeholders.
    */
  class Decoder(using ExecutionContext):
    private val resolvers = mutable.Map.empty[String, PromiseResolver]

    /** Process one chunk.  Returns `Some(value)` for the initial `r:` chunk. */
    def processChunk(chunk: String): Option[js.Any] =
      if chunk.startsWith("r:") then
        Some(Serializer.parse(chunk.substring(2), resolvers))
      else if chunk.startsWith("p") then
        val ci = chunk.indexOf(':')
        val id = chunk.substring(1, ci)
        val value = Serializer.parse(chunk.substring(ci + 1), resolvers)
        resolvers.get(id).foreach(_.resolve(value))
        None
      else if chunk.startsWith("e") then
        val ci = chunk.indexOf(':')
        val id = chunk.substring(1, ci)
        val raw = js.JSON.parse(chunk.substring(ci + 1))
        val msg = if raw == null then "unknown error" else raw.toString
        resolvers.get(id).foreach(_.reject(
          js.special.wrapAsThrowable(msg)
        ))
        None
      else None

end Protocol

// ── Channel implementation ──────────────────────────────────────────

private class ChannelImpl(
    maybeTarget: Option[js.Any],
    channelId: String
)(using ExecutionContext) extends Channel:

  private val namespace = s"bidc_$channelId"
  private val responses = mutable.Map.empty[String, ScalaPromise[js.Any]]
  private val decodings = mutable.Map.empty[String, Protocol.Decoder]
  private var receiveCallback: Option[js.Any => js.Any] = None
  private var canceled = false
  private val disposables = mutable.Buffer.empty[() => Unit]
  private val onResetPortCallbacks = mutable.Buffer.empty[js.Any => Unit]

  // Connection is lazily established
  private val connectionPromise = initPort()

  // Wire up message handling once connected
  connectionPromise.foreach(setupMessageHandler)

  // ── public ──

  def send(data: js.Any): Future[js.Any] =
    connectionPromise.flatMap { port =>
      val msgId = generateId()
      Protocol.encode(data, chunk =>
        port.asInstanceOf[js.Dynamic].postMessage(s"$msgId@$chunk")
      ).failed.foreach(e =>
        js.Dynamic.global.console.error("bidc: streaming error", e.getMessage)
      )
      val resolver = ScalaPromise[js.Any]()
      responses(msgId) = resolver
      resolver.future
    }

  def receive(callback: js.Any => js.Any): Unit =
    receiveCallback = Some(callback)

  def cleanup(): Unit =
    canceled = true
    disposables.foreach(_())
    disposables.clear()

  // ── internal: fire-and-forget send (for responses) ──

  private def fire(data: js.Any): Unit =
    connectionPromise.foreach { port =>
      val msgId = generateId()
      Protocol.encode(data, chunk =>
        port.asInstanceOf[js.Dynamic].postMessage(s"$msgId@$chunk")
      ).failed.foreach(e =>
        js.Dynamic.global.console.error("bidc: streaming error", e.getMessage)
      )
    }

  // ── internal: message handling ──

  private def setupMessageHandler(port: js.Any): Unit =
    if !canceled then
      var activePort = port
      val handler: js.Function1[js.Dynamic, Unit] = (event: js.Dynamic) =>
        val raw = event.data
        if js.typeOf(raw) == "string" then
          val rawStr = raw.asInstanceOf[String]
          val atIdx = rawStr.indexOf('@')
          if atIdx > 0 then
            val msgId = rawStr.substring(0, atIdx)
            val chunk = rawStr.substring(atIdx + 1)
            if chunk.startsWith("r:") then
              val decoder = new Protocol.Decoder
              decodings(msgId) = decoder
              decoder.processChunk(chunk).foreach(decoded =>
                handleDecoded(msgId, decoded)
              )
            else
              decodings.get(msgId).foreach(_.processChunk(chunk))

      activePort.asInstanceOf[js.Dynamic].addEventListener("message", handler)
      activePort.asInstanceOf[js.Dynamic].start()
      disposables += (() =>
        activePort.asInstanceOf[js.Dynamic].removeEventListener("message", handler)
      )

      onResetPortCallbacks += { (newPort: js.Any) =>
        if !canceled then
          activePort
            .asInstanceOf[js.Dynamic]
            .removeEventListener("message", handler)
          activePort = newPort
          activePort.asInstanceOf[js.Dynamic].addEventListener("message", handler)
          activePort.asInstanceOf[js.Dynamic].start()
      }

  private def handleDecoded(msgId: String, decoded: js.Any): Unit =
    val dyn = decoded.asInstanceOf[js.Dynamic]
    val typeField = dyn.selectDynamic("$$type")
    if !js.isUndefined(typeField) then
      val typeStr = typeField.asInstanceOf[String]
      if typeStr.startsWith("bidc-res:") then
        val originalId = typeStr.substring(9)
        val response = dyn.response.asInstanceOf[js.Any]
        responses.remove(originalId).foreach(_.success(response))
      // bidc-fn: (function references) — not yet implemented
    else
      receiveCallback.foreach { cb =>
        try
          val response = cb(decoded)
          fire(
            js.Dynamic.literal(
              "$$type" -> s"bidc-res:$msgId",
              "response" -> response
            )
          )
        catch
          case e: Exception =>
            js.Dynamic.global.console
              .error("bidc receive callback error:", e.getMessage)
      }

  // ── internal: handshake ──

  private def initPort(): Future[js.Any] =
    val result = ScalaPromise[js.Any]()
    var connected = false
    val timestamp = js.Date.now()

    val mc =
      js.Dynamic.newInstance(js.Dynamic.global.MessageChannel)()

    val connectMessage = js.Dynamic.literal(
      "type" -> "bidc-connect",
      "channelId" -> namespace,
      "timestamp" -> timestamp
    )
    val confirmMessage = js.Dynamic.literal(
      "type" -> "bidc-confirm",
      "channelId" -> namespace
    )

    val handleConnect: js.Function1[js.Dynamic, Unit] =
      (event: js.Dynamic) =>
        val ports = event.ports
        if !js.isUndefined(ports) && ports.length.asInstanceOf[Int] > 0 then
          val port = ports.selectDynamic("0")
          val data = event.data
          if !js.isUndefined(data) &&
            data.channelId.asInstanceOf[String] == namespace &&
            data.`type`.asInstanceOf[String] == "bidc-connect"
          then
            val theirTimestamp = data.timestamp.asInstanceOf[Double]
            if timestamp <= theirTimestamp then
              port.postMessage(confirmMessage)
              if connected then
                // Other side refreshed — reinitialize
                onResetPortCallbacks.foreach(_(port.asInstanceOf[js.Any]))
              else
                connected = true
                result.success(port.asInstanceOf[js.Any])

    lazy val handleConfirm: js.Function1[js.Dynamic, Unit] =
      (event: js.Dynamic) =>
        val data = event.data
        if !js.isUndefined(data) &&
          data.`type`.asInstanceOf[String] == "bidc-confirm" &&
          data.channelId.asInstanceOf[String] == namespace
        then
          if !connected then
            connected = true
            result.success(mc.port1.asInstanceOf[js.Any])
            mc.port1.removeEventListener("message", handleConfirm)

    // Listen for incoming connect messages
    maybeTarget match
      case Some(target) if isWorkerTarget(target) =>
        target.asInstanceOf[js.Dynamic].addEventListener("message", handleConnect)
      case Some(_) =>
        js.Dynamic.global.addEventListener("message", handleConnect)
      case None =>
        // Worker or iframe context
        if isInWorker then
          js.Dynamic.global.self
            .addEventListener("message", handleConnect)
        else
          js.Dynamic.global.window
            .addEventListener("message", handleConnect)

    // Listen for confirmation on our port
    mc.port1.addEventListener("message", handleConfirm)
    mc.port1.start()

    // Send the connect message with our port2 as transfer
    sendWithTransfer(connectMessage, mc.port2)

    result.future

  private def sendWithTransfer(message: js.Any, port: js.Any): Unit =
    val transfer = js.Array(port)
    maybeTarget match
      case Some(target) =>
        if isWindowTarget(target) then
          target.asInstanceOf[js.Dynamic].postMessage(message, "*", transfer)
        else
          target.asInstanceOf[js.Dynamic].postMessage(message, transfer)
      case None =>
        if isInWorker then
          js.Dynamic.global.self.postMessage(message, transfer)
        else if isInIframe then
          js.Dynamic.global.window.parent
            .postMessage(message, "*", transfer)
        else
          throw new Exception(
            "bidc: no target provided and no parent context available"
          )

  // ── helpers ──

  private def isWindowTarget(target: js.Any): Boolean =
    try
      val dyn = target.asInstanceOf[js.Dynamic]
      !js.isUndefined(dyn.self) && dyn.self
        .asInstanceOf[js.Any] == target
    catch case _: Throwable => false

  private def isWorkerTarget(target: js.Any): Boolean =
    try
      !js.isUndefined(js.Dynamic.global.Worker) &&
      js.Dynamic.global
        .applyDynamic("eval")("(function(t) { return t instanceof Worker })")
        .asInstanceOf[js.Function1[js.Any, Boolean]](target)
    catch case _: Throwable => false

  private def isInWorker: Boolean =
    try
      val selfType = js.typeOf(js.Dynamic.global.self)
      val windowType = js.typeOf(js.Dynamic.global.window)
      selfType != "undefined" && windowType == "undefined"
    catch case _: Throwable => false

  private def isInIframe: Boolean =
    try
      val w = js.Dynamic.global.window
      !js.isUndefined(w) && !js.isUndefined(w.parent) &&
      w.parent.asInstanceOf[js.Any] != w.asInstanceOf[js.Any]
    catch case _: Throwable => false

  private def generateId(): String =
    val t = js.Date.now().toLong
    val r = (js.Math.random() * 1000000).toInt
    s"${java.lang.Long.toString(t, 36)}${java.lang.Integer.toString(r, 36)}"

end ChannelImpl

