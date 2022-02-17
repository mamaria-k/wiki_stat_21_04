package ru.senin.kotlin.wiki

import com.apurebase.arkenv.Arkenv
import com.apurebase.arkenv.argument
import com.apurebase.arkenv.parse
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.lang.Integer.min
import java.lang.StringBuilder
import java.nio.file.Files
import java.util.concurrent.*
import javax.xml.parsers.SAXParserFactory
import kotlin.time.measureTime


class Parameters : Arkenv() {
    val inputs: List<File> by argument("--inputs") {
        description = "Path(s) to bzip2 archived XML file(s) with WikiMedia dump. Comma separated."
        mapping = {
            it.split(",").map { name -> File(name) }
        }
//        validate("File is not bzip2") {
//            it.all { file -> file.name.substringAfterLast('.') == "bz2" }
//        }
        validate("File does not exist or cannot be read") {
            it.all { file -> file.exists() && file.isFile && file.canRead()}
        }
    }

    val output: String by argument("--output") {
        description = "Report output file"
        defaultValue = { "statistics.txt" }
    }

    val threads: Int by argument("--threads") {
        description = "Number of threads"
        defaultValue = { 4 }
        validate("Number of threads must be in 1..32") {
            it in 1..32
        }
    }
}


data class Page(var isPage: Boolean = false,
                var isRevision: Boolean = false,
                var level: Int = 0,
                var title: StringBuilder = StringBuilder(),
                var text: StringBuilder = StringBuilder(),
                var year: StringBuilder = StringBuilder(),
                var size: String = "")


class SAXReader(private val input: BZip2CompressorInputStream) {
    private val factory = SAXParserFactory.newInstance()
    private val saxParser = factory.newSAXParser()

    fun parse() {
        saxParser.parse(input, SAXHandler())
    }

    val pages = mutableListOf<Page>()

    inner class SAXHandler : DefaultHandler() {
        private var thisElem: String? = null
        private var thisPage: Page = Page()

        private fun isInRevision(): Boolean {
            return thisPage.isPage && thisPage.isRevision && thisPage.level == 3
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            thisElem = qName
            if (qName != "mediawiki") {
                thisPage.level++
            }
            when (qName) {
                "page" -> {
                    thisPage.isPage = true
                }
                "revision" -> {
                    thisPage.isRevision = true
                }
                "text" -> {
                    if (isInRevision())
                    thisPage.size = attributes?.getValue("bytes") ?: return
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch != null) {
                when (thisElem) {
                    "title" -> {
                        if (thisPage.isPage && thisPage.level == 2) {
                            thisPage.title.append(String(ch, start, length))
                        }
                    }
                    "text" -> {
                        if (isInRevision()) {
                            thisPage.text.append(String(ch, start, length))
                        }
                    }
                    "timestamp" -> {
                        if (isInRevision()) {
                            thisPage.year.append(String(ch, start, length))
                        }
                    }
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (qName != "mediawiki") thisPage.level--
            thisElem = null

            when (qName) {
                "page" -> {
                    if (thisPage.size != "" && thisPage.year.toString() != "") {
                        thisPage.isPage = false
                        pages.add(thisPage)
                    }
                    thisPage = Page()
                }
                "revision" -> {
                    thisPage.isRevision = false
                }
            }
        }
    }
}


class Analyzer {
    data class Stat(var countInTitle: ConcurrentHashMap<String, Int> = ConcurrentHashMap(),
                    var countInText: ConcurrentHashMap<String, Int> = ConcurrentHashMap(),
                    var articlesBySize: ConcurrentHashMap<Int, Int> = ConcurrentHashMap(),
                    var articlesByTime: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()
    )

    private val resStat = Stat()

    enum class TypeOfWord {
        TITLE, TEXT
    }

    private val russianWord = Regex("[а-яА-Я]{3,}")

    private fun matchingText(text: String, type: TypeOfWord) {
        val matches = russianWord.findAll(text)
        matches.map { it.groupValues[0] }.map { it.toLowerCase() }.toMutableList().forEach {
            when (type) {
                TypeOfWord.TITLE -> resStat.countInTitle.merge(it, 1, Int::plus)
                TypeOfWord.TEXT -> resStat.countInText.merge(it, 1, Int::plus)
            }
        }
    }

    fun analyze(page: Page) {
        matchingText(page.title.toString(), TypeOfWord.TITLE)
        matchingText(page.text.toString(), TypeOfWord.TEXT)
        resStat.articlesBySize.merge(page.size.length - 1 , 1, Int::plus)
        resStat.articlesByTime.merge(page.year.toString().substringBefore('-').toInt(), 1, Int::plus)
    }

    class PairComparator<T: Comparable<T>, R: Comparable<R>>: Comparator<Pair<T, R>> {
        override fun compare(p1: Pair<T, R>, p2: Pair<T, R>): Int {
            return if (p1.second != p2.second) p2.second.compareTo(p1.second)
            else p1.first.compareTo(p2.first)
        }
    }

    private fun outputStatisticsAboutWords(stat: ConcurrentHashMap<String, Int>, output: MutableList<String>) {
        stat.toList().toMutableList().sortedWith(PairComparator()).take(300).forEach {
            output.add("${it.second} ${it.first}")
        }
    }

    private fun outputStatisticsAboutArticles(stat: ConcurrentHashMap<Int, Int>, output: MutableList<String>) {
        val listStat = stat.toList()
        if (listStat.size >= 2) {
            for (year in listStat.minByOrNull { it.first }!!.first..listStat.maxByOrNull { it.first }!!.first) {
                stat.putIfAbsent(year, 0)
            }
        }
        stat.toList().sortedBy { it.first }.forEach {
            output.add("${it.first} ${it.second}")
        }
    }

    fun printAndWriteResStat(outputNameFile: String) {
        val output = mutableListOf<String>()
        output.add("Топ-300 слов в заголовках статей:")
        outputStatisticsAboutWords(resStat.countInTitle, output)
        output.add("\nТоп-300 слов в статьях:")
        outputStatisticsAboutWords(resStat.countInText, output)
        output.add("\nРаспределение статей по размеру:")
        outputStatisticsAboutArticles(resStat.articlesBySize, output)
        output.add("\nРаспределение статей по времени:")
        outputStatisticsAboutArticles(resStat.articlesByTime, output)

        File(outputNameFile).delete()
        val outputFile = File(outputNameFile)
        output.forEach {
            outputFile.appendText("$it\n")
            println(it)
        }
    }

}


lateinit var parameters: Parameters

fun main(args: Array<String>) {
    try {
        parameters = Parameters().parse(args)
        if (parameters.help) {
            println(parameters.toString())
            return
        }

        val duration = measureTime {
            val allPages = ConcurrentLinkedDeque<Page>()

            val executorForParsing = Executors.newFixedThreadPool(min(parameters.threads, parameters.inputs.size))
            repeat(parameters.inputs.size) {
                executorForParsing.execute {
                    val fin = Files.newInputStream(parameters.inputs[it].toPath())
                    val input = BZip2CompressorInputStream(fin)

                    val readerForThisFile = SAXReader(input)
                    readerForThisFile.parse()

                    allPages.addAll(readerForThisFile.pages)
                }
            }
            executorForParsing.shutdown()
            executorForParsing.awaitTermination(2, TimeUnit.HOURS)

            val analyzer = Analyzer()
            val executorForAnalysis = Executors.newFixedThreadPool(parameters.threads)
            allPages.forEach {
                executorForAnalysis.execute {
                    analyzer.analyze(it)
                }
            }
            executorForAnalysis.shutdown()
            executorForAnalysis.awaitTermination(2, TimeUnit.HOURS)

            analyzer.printAndWriteResStat(parameters.output)

        }
        println("Time: ${duration.inMilliseconds} ms")

    }
    catch (e: Exception) {
        println("Error! ${e.message}")
        throw e
    }
}


