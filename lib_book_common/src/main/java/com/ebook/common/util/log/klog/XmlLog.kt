package com.ebook.common.util.log.klog

import android.util.Log
import java.io.StringReader
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

object XmlLog {
    @JvmStatic
    fun printXml(xml: String, headString: String, tag: String = "XmlLog") {
        val formattedXml = formatXML(xml).let {
            """
            $headString
            $it
            """.trimIndent()
        }

        KLogUtil.printLine(true, tag)
        formattedXml.split(KLog.LINE_SEPARATOR.toRegex())
            .filterNot { it.isEmpty() }
            .forEach { Log.d(tag, "║ $it") }
        KLogUtil.printLine(false, tag)
    }

    private fun formatXML(inputXML: String): String {
        return try {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
            val xmlOutput = StreamResult(StringWriter())
            transformer.transform(StreamSource(StringReader(inputXML)), xmlOutput)
            xmlOutput.writer.toString().replaceFirst(">".toRegex(), ">\n")
        } catch (e: Exception) {
            e.printStackTrace()
            inputXML
        }
    }
}
