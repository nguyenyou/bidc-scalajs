# BIDC - Bidirectional Channels for Scala.js

A Scala.js port of [vercel/bidc](https://github.com/vercel/bidc) — bidirectional channels for asynchronous communication across JavaScript contexts.

## Features

- **Automatic Handshake** — robust connection establishment with collision resolution
- **Promise Streaming** — nested `js.Promise` values are streamed as they resolve
- **Concurrent Messages** — handle multiple simultaneous sends without blocking
- **Reconnection** — automatically re-establishes connection if one side reloads
- **Namespaced Channels** — multiple independent channels to the same target

## Usage

All `createChannel` methods require an implicit `ExecutionContext`.

**Main thread:**

```scala
import bidc.Bidc
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

val worker = new org.scalajs.dom.Worker("worker.js")
val channel = Bidc.createChannel(worker)

// Send a message and await the response
channel.send(js.Dynamic.literal(value = "Hello, worker!")).foreach { response =>
  println(response) // "HELLO, WORKER!"
}
```

**Worker:**

```scala
import bidc.Bidc
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

val channel = Bidc.createChannel()

channel.receive { data =>
  val value = data.asInstanceOf[js.Dynamic].value.asInstanceOf[String]
  value.toUpperCase()
}
```

## ExecutionContext

BIDC takes `ExecutionContext` as an implicit parameter so you can choose your own. The default `ExecutionContext.global` in Scala.js uses the **microtask** queue (`Promise.then()`), which can starve macrotask events like `postMessage`/`onmessage` under heavy Future chaining.

For best results, use [scala-js-macrotask-executor](https://github.com/nicmart/scala-js-macrotask-executor):

```scala
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits._

val channel = Bidc.createChannel(worker) // uses macrotask EC
```

This schedules Future callbacks on the **macrotask** queue (via `setImmediate` polyfill), which naturally aligns with `postMessage` — the transport BIDC uses internally. This prevents UI/IO starvation and ensures fair interleaving of channel messages with other async work.

## Build

Uses [Mill](https://mill-build.org/) build tool:

```bash
./mill bidc.compile
```

## License

MIT — port of [vercel/bidc](https://github.com/vercel/bidc) by Shu Ding.
