package app.splitaudio

import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** approute.sh 乾跑单元测试：验证日志与 token 文件权限以及 start 路由参数生成。 */
class ApprouteScriptTest {
    private lateinit var root: File
    private lateinit var tmp: File
    private lateinit var bin: File
    private lateinit var script: File
    private val tok = "0123456789abcdef0123456789abcdef"

    @Before fun setUp() {
        assumeTrue("needs a POSIX shell", File("/bin/sh").exists())
        root = Files.createTempDirectory("approute-dry").toFile()
        assumeTrue("needs POSIX file permissions", Files.getFileStore(root.toPath()).supportsFileAttributeView("posix"))
        tmp = File(root, "tmp").apply { mkdirs() }
        bin = File(root, "bin").apply { mkdirs() }
        fake("pm", "echo \"package:com.tencent.qqmusic uid:10101\"\n" +
            "echo \"package:com.google.android.apps.youtube.music uid:10102\"\n" +
            "echo \"package:com.example.video uid:10200\"")
        fake("pgrep", "exit 1")
        fake("app_process", "echo \"fake router \$*\"")
        fake("setsid", "exec \"\$@\"")
        fake("nohup", "exec \"\$@\"")
        val src = listOf("src/main/assets/approute.sh", "app/src/main/assets/approute.sh").map(::File).first { it.exists() }.readText()
        assertTrue("approute.sh no longer has the D=/data/local/tmp line this test swaps", src.contains("D=/data/local/tmp; LOG=\$D/approute.log"))
        val swapped = src.replace("D=/data/local/tmp; ", "D=${tmp.path}; ")
        assertFalse("the dry run must never touch the real /data/local/tmp", swapped.contains("D=/data/local/tmp"))
        script = File(root, "approute.sh").apply { writeText(swapped) }
    }

    @After fun tearDown() { if (::root.isInitialized) root.deleteRecursively() }

    private fun fake(name: String, body: String) {
        val f = File(bin, name)
        f.writeText("#!/bin/sh\n$body\n")
        assertTrue(f.setExecutable(true))
    }

    private fun sh(vararg args: String): String {
        val pb = ProcessBuilder(listOf("/bin/sh", "-c", "umask 000; exec /bin/sh \"\$0\" \"\$@\"", script.path) + args)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PATH"] = bin.path + File.pathSeparator + (System.getenv("PATH") ?: "/usr/bin:/bin")
        env["SPLITAUDIO_UID"] = "10234"; env["SPLITAUDIO_TOKEN"] = tok
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("approute.sh ${args.joinToString(" ")} did not finish", p.waitFor(30, TimeUnit.SECONDS))
        return out
    }

    private fun perms(f: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test fun start_logAndTokenFilesAreNotWorldWritable_andTheRouterGetsItsArguments() {
        val out = sh("start")
        assertTrue(out, out.contains("started pid="))
        val log = File(tmp, "approute.log")
        assertEquals("rw-r--r--", perms(log))
        val token = File(tmp, "approute.token")
        assertEquals("rw-------", perms(token))
        assertEquals(tok, token.readText())
        val logText = log.readText()
        assertTrue(logText, logText.contains("AppRoute 10101,10102 10200"))
    }
}
