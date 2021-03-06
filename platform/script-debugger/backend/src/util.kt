/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.debugger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import io.netty.buffer.ByteBuf
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.io.JsonReaderEx
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentLinkedQueue

internal class LogEntry(val message: Any) {
  internal val time = System.currentTimeMillis()
}

class MessagingLogger internal constructor(private val queue: ConcurrentLinkedQueue<LogEntry>) {
  fun add(inMessage: CharSequence) {
    queue.add(LogEntry(inMessage))
  }

  fun add(outMessage: ByteBuf) {
    queue.add(LogEntry(outMessage.copy()))
  }
}

fun createDebugLogger(@PropertyKey(resourceBundle = Registry.REGISTRY_BUNDLE) key: String, vm: AttachStateManager): MessagingLogger? {
  val debugFile = Registry.stringValue(key)
  if (debugFile.isNullOrEmpty()) {
    return null
  }

  val queue = ConcurrentLinkedQueue<LogEntry>()
  val logger = MessagingLogger(queue)
  ApplicationManager.getApplication().executeOnPooledThread {
    val file = File(FileUtil.expandUserHome(debugFile))
    val out = FileOutputStream(file)
    val writer = out.writer()
    writer.write("[\n")
    writer.flush()
    val fileChannel = out.channel

    val dateFormatter = SimpleDateFormat("HH.mm.ss,SSS")

    o@ while (vm.isAttached) {
      while (true) {
        val entry = queue.poll()
        if (entry == null) {
          Thread.sleep(1000)
          continue@o
        }

        writer.write("""{"timestamp": "${dateFormatter.format(entry.time)}", """)
        val message = entry.message
        when (message) {
          is JsonReaderEx.CharSequenceBackedByChars -> {
            writer.write("\"IN\": ")
            writer.flush()

            fileChannel.write(message.byteBuffer)

            writer.write("},\n")
            writer.flush()
          }
          is ByteBuf -> {
            writer.write("\"OUT\": ")
            writer.flush()

            message.getBytes(message.readerIndex(), out, message.readableBytes())
            message.release()

            writer.write("},\n")
            writer.flush()
          }
          else -> throw AssertionError("Unknown message type")
        }
      }
    }
    writer.write("]")
    out.close()
  }
  return logger
}
