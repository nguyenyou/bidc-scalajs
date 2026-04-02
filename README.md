# BIDC - Bidirectional Channels for Scala.js

A Scala.js port of [vercel/bidc](https://github.com/vercel/bidc) — bidirectional channels for asynchronous communication across JavaScript contexts.

## Features

- **Automatic Handshake** — robust connection establishment with collision resolution
- **Promise Streaming** — nested `js.Promise` values are streamed as they resolve
- **Concurrent Messages** — handle multiple simultaneous sends without blocking
- **Reconnection** — automatically re-establishes connection if one side reloads
- **Namespaced Channels** — multiple independent channels to the same target

## Usage

**Main thread:**

```scala
import bidc.Bidc
import scala.scalajs.js

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

val channel = Bidc.createChannel()

channel.receive { data =>
  val value = data.asInstanceOf[js.Dynamic].value.asInstanceOf[String]
  value.toUpperCase()
}
```

## Build

Uses [Mill](https://mill-build.org/) build tool:

```bash
./mill bidc.compile
```

## License

MIT — port of [vercel/bidc](https://github.com/vercel/bidc) by Shu Ding.
