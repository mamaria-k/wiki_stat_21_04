package ru.senin.kotlin.wiki

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.*
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder
import java.nio.file.Paths
import kotlin.test.assertEquals


class AnalyzeUnitTest {
    companion object {
        private const val TEST_DATA_PATH = "src/test/resources/testData"
        private const val TEMPORARY_DIRECTORY = "temp_test_data"
        private const val BZIP2_SUFFIX = ".bz2"
        private const val TIMEOUT = 30L

        @BeforeAll
        @JvmStatic
        fun createArchives() {
            val testRoot = File(TEMPORARY_DIRECTORY)
            if (!testRoot.exists()){
                testRoot.mkdirs()
            }
            File(TEST_DATA_PATH).listFiles().orEmpty().filter{ it.extension == "xml" }.forEach {
                createTemporaryBzip2File(it)
            }
            // create invalid Bzip2 file
            File("invalid".toBzip2Inputs()).writeText("Превед, Медвед!")
        }

        @AfterAll
        @JvmStatic
        fun cleanUp() {
            File(TEMPORARY_DIRECTORY).deleteRecursively()
        }

        private fun String.toBzip2Inputs(): String = this.split(',').joinToString(",") {
            Paths.get(TEMPORARY_DIRECTORY).resolve(it + BZIP2_SUFFIX).toString()
        }

        private fun createTemporaryBzip2File(file: File) {
            val input = file.inputStream()
            input.use {
                val output = BZip2CompressorOutputStream(FileOutputStream(file.name.toBzip2Inputs()))
                output.use {
                    input.copyTo(output)
                }
            }
        }
    }

    @Test
    @Timeout(TIMEOUT)
    fun `with other words`() {
        val page = Page(
            false,
            false,
            0,
            StringBuilder("Простой заголовок with some English words"),
            StringBuilder("[[Простой как-бы текст]] ну и English words!"),
            StringBuilder("2020-01-01T00:00:00Z"),
            "65"
        )
        runAnalyze("simple.xml", page)
    }

    @Test
    @Timeout(TIMEOUT)
    fun `without other words`() {
        val page = Page(
            false,
            false,
            0,
            StringBuilder("Простой заголовок"),
            StringBuilder("Простой текст"),
            StringBuilder("2020-01-01T00:00:00Z"),
            "26"
        )
        runAnalyze("missed-tags.xml", page)
    }


    private fun runAnalyze(xmlInputs: String, page: Page) {
        val outputPrefix = xmlInputs.replace(",", "__")
        val outputFileName = "$outputPrefix.actual.txt"

        val analyzer = Analyzer()
        analyzer.analyze(page)
        analyzer.printAndWriteResStat(outputFileName.relativeToTemporaryDir())

        val expectedFileName = "$outputPrefix.expected.txt"
        assertFilesHaveSameContent(expectedFileName, outputFileName)
    }

    private fun assertFilesHaveSameContent(expectedFileName: String, actualFileName: String, message: String? = null) {
        val actual = Paths.get(TEMPORARY_DIRECTORY).resolve(actualFileName).toFile().readText()
        val expected = Paths.get(TEST_DATA_PATH).resolve(expectedFileName).toFile().readText()
        assertEquals(expected, actual, message)
    }

    private fun String.relativeToTemporaryDir(): String = Paths.get(TEMPORARY_DIRECTORY).resolve(this).toString()
}