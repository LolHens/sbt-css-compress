package net.ground5hark.sbt.css

import java.io.{File => _, FileFilter => _, _}

import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.web.{PathMapping, SbtWeb}
import com.yahoo.platform.yui.compressor.CssCompressor
import sbt.Keys._
import sbt._

import scala.collection.mutable

trait CssCompressKeys {
  val cssCompress = TaskKey[Pipeline.Stage]("css-compress", "Runs the CSS compressor on the assets in the pipeline")

  val cssCompressSuffix = SettingKey[String]("css-compress-suffix", "Suffix to append to compressed files, default: \".min.css\"")
  val cssCompressParentDir = SettingKey[String]("css-compress-parent-dir", "Parent directory name where compressed CSS will go, default: \"\"")
  val cssCompressLineBreak = SettingKey[Int]("css-compress-line-break", "Position in the compressed output at which to break out a new line, default: -1 (never)")
}

class UnminifiedCssFileFilter(suffix: String) extends FileFilter {
  override def accept(file: File): Boolean =
    !HiddenFileFilter.accept(file) && file.isFile && !file.getName.endsWith(suffix) && file.getName.endsWith(".css")
}

object util {
  def withoutExt(name: String): String = name.substring(0, name.lastIndexOf("."))

  def withParent(f: File): String = f.getParentFile.getName + "/" + f.getName
}

object SbtCssCompress extends AutoPlugin {
  override def requires = SbtWeb

  override def trigger = AllRequirements

  object autoImport extends CssCompressKeys

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport._

  override def projectSettings = Seq(
    cssCompressSuffix := ".min.css",
    cssCompressParentDir := "",
    cssCompressLineBreak := -1,
    includeFilter in cssCompress := new UnminifiedCssFileFilter(cssCompressSuffix.value),
    excludeFilter in cssCompress := HiddenFileFilter,
    cssCompress := compress.value
  )

  private def invokeCompressor(src: File, target: File, lineBreak: Int): Unit = {
    val openStreams = mutable.ListBuffer.empty[Closeable]
    try {
      val reader = new InputStreamReader(new FileInputStream(src))
      openStreams += reader
      val compressor = new CssCompressor(reader)
      val writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8")
      openStreams += writer
      compressor.compress(writer, lineBreak)
    } catch {
      case t: Throwable => throw t
    } finally {
      // Close silently
      openStreams.foreach { stream =>
        try {
          stream.close()
        } catch {
          case e: IOException =>
        }
      }
    }
  }

  private val compress: Def.Initialize[Task[Pipeline.Stage]] = Def.task {
    val streamsValue = streams.value

    mappings: Seq[PathMapping] =>
      val targetDir = webTarget.value / cssCompressParentDir.value
      val compressMappings = mappings.view
        .filter(m => (includeFilter in cssCompress).value.accept(m._1))
        .filterNot(m => (excludeFilter in cssCompress).value.accept(m._1))
        .toMap

      val runCompressor = FileFunction.cached(streamsValue.cacheDirectory / cssCompressParentDir.value, FilesInfo.lastModified) {
        files =>
          files.map { f =>
            val outputFileSubPath = s"${util.withoutExt(compressMappings(f))}${cssCompressSuffix.value}"
            val outputFile = targetDir / outputFileSubPath
            IO.createDirectory(outputFile.getParentFile)
            streamsValue.log.info(s"Compressing file ${compressMappings(f)}")
            invokeCompressor(f, outputFile, cssCompressLineBreak.value)
            outputFile
          }
      }

      val compressed = runCompressor(compressMappings.keySet).pair(Path.relativeTo(targetDir))
      compressed ++ mappings.filter {
        // Handle duplicate mappings
        case (mappingFile, mappingName) =>
          val include = !compressed.exists(_._2 == mappingName)
          if (!include)
            streamsValue.log.warn(s"css-compressor encountered a duplicate mapping for $mappingName and will " +
              "prefer the css-compressor version instead. If you want to avoid this, make sure you aren't " +
              "including minified and non-minified sibling assets in the pipeline.")
          include
      }
  }
}
