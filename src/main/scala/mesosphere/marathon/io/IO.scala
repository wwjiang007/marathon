package mesosphere.marathon
package io

import java.io.{ Closeable, File, FileNotFoundException, InputStream, OutputStream }

import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.IOUtils
import scala.annotation.tailrec

import scala.util.{ Failure, Success, Try }

object IO extends StrictLogging {

  def listFiles(file: String): Array[File] = listFiles(new File(file))
  def listFiles(file: File): Array[File] = {
    if (!file.exists()) throw new FileNotFoundException(file.getAbsolutePath)
    if (!file.isDirectory) throw new FileNotFoundException(s"File ${file.getAbsolutePath} is not a directory!")
    file.listFiles()
  }

  def withResource[T](path: String)(fn: InputStream => T): Option[T] = {
    Option(getClass.getResourceAsStream(path)).flatMap { stream =>
      Try(stream.available()) match {
        case Success(length) => Some(fn(stream))
        case Failure(ex) => None
      }
    }
  }

  def using[A <: Closeable, B](closeable: A)(fn: (A) => B): B = {
    try {
      fn(closeable)
    } finally {
      IOUtils.closeQuietly(closeable)
    }
  }

  /**
    * Copies all bytes from an input stream to an outputstream.
    *
    * The method is adapted from [[com.google.common.io.ByteStreams.copy]] with the only difference that we flush after
    * each write. Note: This method is blocking!
    *
    * @param maybeFrom Inputstream for copy from.
    * @param mabyeTo Outputstream to copy to.
    * @return
    */
  def transfer(maybeFrom: Option[InputStream], maybeTo: Option[OutputStream]): Long = {
    (maybeFrom, maybeTo) match {
      case (Some(from), Some(to)) =>
        @tailrec def iter(buf: Array[Byte], total: Long): Long =
          from.read(buf) match {
            case -1 => total
            case r =>
              to.write(buf, 0, r)
              to.flush()
              iter(buf, total + r)
          }

        iter(new Array[Byte](8192), 0L)
      case _ =>
        logger.debug("Did not copy any data.")
        0
    }
  }
}

