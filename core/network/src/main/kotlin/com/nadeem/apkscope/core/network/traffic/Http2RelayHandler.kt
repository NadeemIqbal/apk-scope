package com.nadeem.apkscope.core.network.traffic

import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Reader
import okhttp3.internal.http2.Http2Writer
import okhttp3.internal.http2.Settings
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.sink
import okio.source
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bidirectional HTTP/2 multiplexed proxy relay and traffic inspector.
 *
 * Implements:
 * - RFC 7540 / RFC 9113 transparent multiplexed frame forwarding.
 * - Preface, SETTINGS, and ACK handling.
 * - Dynamic HPACK state preservation across both downstream and upstream legs.
 * - Stream multiplexing: request/response association by streamId.
 * - HEADERS, CONTINUATION, DATA, RST_STREAM, PING, GOAWAY, and WINDOW_UPDATE handling.
 * - gRPC detection and message-boundary inspection.
 * - Server-Sent Events (SSE) stream detection and live event publishing.
 * - Streamlined failure and reset isolation.
 */
class Http2RelayHandler(
    private val downstreamIn: InputStream,
    private val downstreamOut: OutputStream,
    private val upstreamIn: InputStream,
    private val upstreamOut: OutputStream,
    private val host: String,
    private val port: Int,
    private val isTls: Boolean,
    private val sessionId: String? = null,
    private val targetPackage: String? = null,
    private val ownership: OwnershipVerification = OwnershipVerification.UNVERIFIED,
    private val onTrafficRecorded: (TrafficRecord) -> Unit = { TrafficInspectionStore.record(it) }
) {
    private val isRunning = AtomicBoolean(true)
    private val streams = ConcurrentHashMap<Int, Http2StreamTracker>()
    private val completionLatch = CountDownLatch(2)

    fun relay() {
        android.util.Log.i("Http2Relay", "HTTP2_RELAY_ENTERED host=$host port=$port sessionId=$sessionId targetPackage=$targetPackage")
        val downstreamSource = downstreamIn.source().buffer()
        val downstreamSink = downstreamOut.sink().buffer()
        val upstreamSource = upstreamIn.source().buffer()
        val upstreamSink = upstreamOut.sink().buffer()

        val downstreamReader = Http2Reader(downstreamSource, false)
        val downstreamWriter = Http2Writer(downstreamSink, false)

        val upstreamReader = Http2Reader(upstreamSource, true)
        val upstreamWriter = Http2Writer(upstreamSink, true)

        // Establish our own HTTP/2 connection to the real upstream: RFC 7540 §3.5 requires the
        // preface to be immediately followed by a SETTINGS frame (which may be empty) — omitting
        // it left real servers waiting indefinitely for a frame that never arrived, since the
        // relay's own SETTINGS is independent of whatever the downstream client later sends.
        try {
            upstreamWriter.connectionPreface()
            upstreamWriter.settings(Settings())
            upstreamWriter.flush()
        } catch (_: Exception) {}

        // Thread 1: Read from Downstream (Client), inspect, forward to Upstream (Server)
        val downstreamThread = Thread({
            try {
                downstreamReader.readConnectionPreface(object : Http2Reader.Handler {
                    override fun settings(clearPrevious: Boolean, settings: Settings) {
                        try {
                            upstreamWriter.applyAndAckSettings(settings)
                            upstreamWriter.flush()
                        } catch (_: Exception) {}
                    }
                    override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {}
                    override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {}
                    override fun rstStream(streamId: Int, errorCode: ErrorCode) {}
                    override fun ackSettings() {}
                    override fun ping(ack: Boolean, payload1: Int, payload2: Int) {}
                    override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {}
                    override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {}
                    override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {}
                    override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {}
                    override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {}
                })

                val clientHandler = object : Http2Reader.Handler {
                    override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
                        val parsed = Http2FrameParser.parseHeaders(headerBlock)
                        val tracker = streams.computeIfAbsent(streamId) { Http2StreamTracker(streamId) }

                        if (parsed.method != null) {
                            tracker.method = parsed.method
                            tracker.path = parsed.path
                            tracker.scheme = parsed.scheme
                            tracker.authority = parsed.authority
                            tracker.requestHeaders.putAll(parsed.headers)

                            // Detect gRPC
                            val cType = parsed.headers["content-type"]
                            if (GrpcMessageDecoder.isGrpcContentType(cType) || (parsed.path?.count { it == '/' } == 2)) {
                                tracker.isGrpc = true
                                tracker.grpcServiceName = parsed.path?.substringBeforeLast('/')?.removePrefix("/") ?: ""
                                tracker.grpcMethodName = parsed.path?.substringAfterLast('/') ?: ""
                                val isGzip = parsed.headers["grpc-encoding"]?.lowercase() == "gzip"
                                tracker.grpcRequestDecoder = GrpcMessageDecoder(
                                    direction = Direction.OUTBOUND,
                                    isGzipEncoding = isGzip
                                ) { msg ->
                                    synchronized(tracker.grpcMessages) { tracker.grpcMessages.add(msg) }
                                    onTrafficRecorded(tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership))
                                }
                            }
                        }

                        if (inFinished) {
                            tracker.isRequestFinished = true
                        }

                        upstreamWriter.headers(inFinished, streamId, headerBlock)
                        upstreamWriter.flush()
                    }

                    override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
                        val buffer = Buffer()
                        source.readFully(buffer, length.toLong())

                        val tracker = streams[streamId]
                        if (tracker != null) {
                            val copy = buffer.clone()
                            val bytes = copy.readByteArray()
                            tracker.appendRequestBody(bytes, 0, bytes.size)
                            tracker.grpcRequestDecoder?.feed(bytes)

                            if (inFinished) {
                                tracker.isRequestFinished = true
                                if (tracker.isResponseFinished) {
                                    finalizeStream(tracker)
                                }
                            }
                        }

                        upstreamWriter.data(inFinished, streamId, buffer, length)
                        upstreamWriter.flush()
                    }

                    override fun rstStream(streamId: Int, errorCode: ErrorCode) {
                        val tracker = streams[streamId]
                        if (tracker != null) {
                            tracker.isReset = true
                            tracker.resetErrorCode = errorCode.name
                            finalizeStream(tracker)
                        }
                        upstreamWriter.rstStream(streamId, errorCode)
                        upstreamWriter.flush()
                    }

                    override fun settings(clearPrevious: Boolean, settings: Settings) {
                        upstreamWriter.applyAndAckSettings(settings)
                        upstreamWriter.flush()
                    }

                    override fun ackSettings() {
                        upstreamWriter.applyAndAckSettings(Settings())
                        upstreamWriter.flush()
                    }

                    override fun ping(ack: Boolean, payload1: Int, payload2: Int) {
                        upstreamWriter.ping(ack, payload1, payload2)
                        upstreamWriter.flush()
                    }

                    override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {
                        upstreamWriter.goAway(lastGoodStreamId, errorCode, debugData.toByteArray())
                        upstreamWriter.flush()
                    }

                    override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
                        upstreamWriter.windowUpdate(streamId, windowSizeIncrement)
                        upstreamWriter.flush()
                    }

                    override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {}
                    override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {
                        upstreamWriter.pushPromise(streamId, promisedStreamId, requestHeaders)
                        upstreamWriter.flush()
                    }
                    override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {}
                }

                while (isRunning.get() && downstreamReader.nextFrame(false, clientHandler)) {
                    // loop until EOF or error
                }
            } catch (e: Exception) {
                android.util.Log.e("Http2Relay", "downstream thread error for $host: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                isRunning.set(false)
                completionLatch.countDown()
            }
        }, "Http2Relay-Downstream-$host")

        // Thread 2: Read from Upstream (Server), inspect, forward to Downstream (Client)
        val upstreamThread = Thread({
            try {
                upstreamReader.readConnectionPreface(object : Http2Reader.Handler {
                    override fun settings(clearPrevious: Boolean, settings: Settings) {
                        try {
                            // We are the client on this leg: RFC 7540 §6.5.3 requires acking the
                            // server's SETTINGS ourselves, independent of forwarding it downstream.
                            upstreamWriter.applyAndAckSettings(settings)
                            downstreamWriter.settings(settings)
                            downstreamWriter.flush()
                        } catch (_: Exception) {}
                    }
                    override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {}
                    override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {}
                    override fun rstStream(streamId: Int, errorCode: ErrorCode) {}
                    override fun ackSettings() {}
                    override fun ping(ack: Boolean, payload1: Int, payload2: Int) {}
                    override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {}
                    override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {}
                    override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {}
                    override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {}
                    override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {}
                })

                val serverHandler = object : Http2Reader.Handler {
                    override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
                        val parsed = Http2FrameParser.parseHeaders(headerBlock)
                        val tracker = streams.computeIfAbsent(streamId) { Http2StreamTracker(streamId) }

                        if (parsed.status != null) {
                            // Response headers
                            tracker.statusCode = parsed.status
                            tracker.responseHeaders.putAll(parsed.headers)

                            val cType = parsed.headers["content-type"]
                            if (GrpcMessageDecoder.isGrpcContentType(cType)) {
                                tracker.isGrpc = true
                                val isGzip = parsed.headers["grpc-encoding"]?.lowercase() == "gzip"
                                tracker.grpcResponseDecoder = GrpcMessageDecoder(
                                    direction = Direction.INBOUND,
                                    isGzipEncoding = isGzip
                                ) { msg ->
                                    synchronized(tracker.grpcMessages) { tracker.grpcMessages.add(msg) }
                                    onTrafficRecorded(tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership))
                                }
                            }

                            if (cType?.contains("text/event-stream") == true) {
                                tracker.isSse = true
                                tracker.sseParser = SseEventParser { event ->
                                    synchronized(tracker.sseEvents) { tracker.sseEvents.add(event) }
                                    onTrafficRecorded(tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership))
                                }
                            }

                            // Emit initial response state
                            onTrafficRecorded(tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership))
                        } else {
                            // Trailers
                            tracker.responseTrailers.putAll(parsed.headers)
                        }

                        if (inFinished) {
                            tracker.isResponseFinished = true
                            tracker.sseParser?.finish()
                            finalizeStream(tracker)
                        }

                        downstreamWriter.headers(inFinished, streamId, headerBlock)
                        downstreamWriter.flush()
                    }

                    override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
                        val buffer = Buffer()
                        source.readFully(buffer, length.toLong())

                        val tracker = streams[streamId]
                        if (tracker != null) {
                            val copy = buffer.clone()
                            val bytes = copy.readByteArray()
                            tracker.appendResponseBody(bytes, 0, bytes.size)
                            tracker.grpcResponseDecoder?.feed(bytes)
                            tracker.sseParser?.feed(bytes)

                            if (tracker.isSse) {
                                onTrafficRecorded(tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership))
                            }

                            if (inFinished) {
                                tracker.isResponseFinished = true
                                tracker.sseParser?.finish()
                                finalizeStream(tracker)
                            }
                        }

                        downstreamWriter.data(inFinished, streamId, buffer, length)
                        downstreamWriter.flush()
                    }

                    override fun rstStream(streamId: Int, errorCode: ErrorCode) {
                        val tracker = streams[streamId]
                        if (tracker != null) {
                            tracker.isReset = true
                            tracker.resetErrorCode = errorCode.name
                            finalizeStream(tracker)
                        }
                        downstreamWriter.rstStream(streamId, errorCode)
                        downstreamWriter.flush()
                    }

                    override fun settings(clearPrevious: Boolean, settings: Settings) {
                        // We are the client on this leg: ack the server's SETTINGS ourselves in
                        // addition to forwarding it downstream (RFC 7540 §6.5.3).
                        upstreamWriter.applyAndAckSettings(settings)
                        downstreamWriter.settings(settings)
                        downstreamWriter.flush()
                    }

                    override fun ackSettings() {
                        downstreamWriter.applyAndAckSettings(Settings())
                        downstreamWriter.flush()
                    }

                    override fun ping(ack: Boolean, payload1: Int, payload2: Int) {
                        downstreamWriter.ping(ack, payload1, payload2)
                        downstreamWriter.flush()
                    }

                    override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {
                        downstreamWriter.goAway(lastGoodStreamId, errorCode, debugData.toByteArray())
                        downstreamWriter.flush()
                    }

                    override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
                        downstreamWriter.windowUpdate(streamId, windowSizeIncrement)
                        downstreamWriter.flush()
                    }

                    override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {}
                    override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {
                        downstreamWriter.pushPromise(streamId, promisedStreamId, requestHeaders)
                        downstreamWriter.flush()
                    }
                    override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {}
                }

                while (isRunning.get() && upstreamReader.nextFrame(false, serverHandler)) {
                    // loop until EOF or error
                }
            } catch (e: Exception) {
                android.util.Log.e("Http2Relay", "upstream thread error for $host: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                isRunning.set(false)
                completionLatch.countDown()
            }
        }, "Http2Relay-Upstream-$host")

        downstreamThread.isDaemon = true
        upstreamThread.isDaemon = true
        downstreamThread.start()
        upstreamThread.start()

        try {
            completionLatch.await(300, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        } finally {
            isRunning.set(false)
            // Finalize any unfinished streams on disconnect
            for (stream in streams.values) {
                if (!stream.isResponseFinished) {
                    stream.isResponseFinished = true
                    stream.sseParser?.finish()
                    finalizeStream(stream)
                }
            }
        }
    }

    private fun finalizeStream(tracker: Http2StreamTracker) {
        // Checkpoint (Milestone 9 attribution fix, 2026-09-12): this is the *final*, replace-in-place
        // record for the stream (TrafficInspectionStore.record() upserts by id) — every other
        // toTrafficRecord() call site above is provisional and gets overwritten by this one once the
        // stream finishes. Omitting sessionId/targetPackage here silently wiped out whatever
        // attribution an earlier provisional record had, so no h2/gRPC/SSE transaction could ever
        // pass TrafficInspectionStore.forSession() in its final, completed state.
        val record = tracker.toTrafficRecord(host, port, isTls, sessionId, targetPackage, ownership)
        onTrafficRecorded(record)
    }
}
