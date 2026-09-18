package com.nadeem.apkscope.core.network

/** Attached to a [java.nio.channels.SelectionKey] so the engine's single selector loop can dispatch without a type switch per flow kind. */
interface SelectHandler {
 fun onReadable()
 fun onWritable() {}
 fun onConnectable() {}
}
