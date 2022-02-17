package ru.senin.kotlin.wiki

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.*
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder
import java.nio.file.Files
import java.nio.file.Paths


class ParseUnitTest {
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
    fun `good XML`() {
        val page = Page(
            false,
            false,
            0,
            StringBuilder("Простой заголовок with some English words"),
            StringBuilder("[[Простой как-бы текст]] ну и English words!"),
            StringBuilder("2020-01-01T00:00:00Z"),
            "65"
        )
        val expectedPages = mutableListOf<Page>(page)
        runParse("simple.xml", expectedPages)
    }

    @Test
    @Timeout(TIMEOUT)
    fun `missed tags in XML`() {
        val page = Page(
            false,
            false,
            0,
            StringBuilder("Простой заголовок"),
            StringBuilder("Простой текст"),
            StringBuilder("2020-01-01T00:00:00Z"),
            "26"
        )
        val expectedPages = mutableListOf<Page>(page)
        runParse("missed-tags.xml", expectedPages)
    }

    @Test
    @Timeout(TIMEOUT)
    fun `XML without pages`() {
        val expectedPages = mutableListOf<Page>()
        runParse("no-pages.xml", expectedPages)
    }

    @Test
    @Timeout(TIMEOUT)
    fun `wrong nesting of tags in XML`() {
        val expectedPages = mutableListOf<Page>()
        runParse("wrong-nesting.xml", expectedPages)
    }


    private fun runParse(xmlInputs: String, expected: MutableList<Page>) {
        val fin = Files.newInputStream(File(xmlInputs.toBzip2Inputs()).toPath())
        val input = BZip2CompressorInputStream(fin)
        val reader = SAXReader(input)
        reader.parse()

        equalsPageList(reader.pages, expected)
    }

    private fun equalsPageList(actual: MutableList<Page>, expected: MutableList<Page>) {
        assert(actual.size == expected.size)
        for (i in 0 until actual.size) {
            assert(
                actual[i].title.toString() == expected[i].title.toString() &&
                actual[i].text.toString() == expected[i].text.toString() &&
                actual[i].year.toString() == expected[i].year.toString() &&
                actual[i].size == expected[i].size
            )
        }
    }

}
