package dev.codex.go

import dev.codex.go.runtime.ConnectProxy
import dev.codex.go.runtime.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.Base64

class RuntimeSafetyTest {
    @Test fun extractionRejectsTraversalAndAbsolutePaths() {
        val root = Files.createTempDirectory("codex-go-root").toFile()
        try {
            assertEquals(File(root, "etc/config").canonicalFile, RuntimeEnvironment.safeChild(root, "etc/config").canonicalFile)
            assertThrows(IllegalArgumentException::class.java) { RuntimeEnvironment.safeChild(root, "../outside") }
            assertThrows(IllegalArgumentException::class.java) { RuntimeEnvironment.safeChild(root, "/etc/passwd") }
            Files.createSymbolicLink(File(root, "escape").toPath(), root.parentFile!!.toPath())
            assertThrows(IllegalArgumentException::class.java) { RuntimeEnvironment.safeChild(root, "escape/outside") }
        } finally { root.deleteRecursively() }
    }
    @Test fun localProxyRequiresSecretAndRejectsNonHttpsDestinations() {
        ConnectProxy().use { proxy ->
            val uri = URI(proxy.url)
            fun request(header: String) = Socket("127.0.0.1", uri.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(header.toByteArray())
                socket.getInputStream().bufferedReader().readLine()
            }
            assertTrue(request("CONNECT example.com:443 HTTP/1.1\r\n\r\n").contains("407"))
            val auth = Base64.getEncoder().encodeToString(uri.userInfo.toByteArray())
            assertTrue(request("CONNECT 127.0.0.1:22 HTTP/1.1\r\nProxy-Authorization: Basic $auth\r\n\r\n").contains("502"))
        }
    }
}
