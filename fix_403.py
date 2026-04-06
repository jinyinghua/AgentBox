import os
f = '/workspace/agentbox/app/src/main/java/com/shaun/agentbox/sandbox/LinuxEnvironmentManager.kt'
with open(f, 'r') as file:
    content = file.read()

# 准备包含 User-Agent 的新代码
new_download = """    private fun downloadFile(urlString: String, outFile: File) {
        try {
            var url = URL(urlString)
            var redirectCount = 0
            while (redirectCount < 5) {
                val connection = url.openConnection() as java.net.HttpURLConnection
                
                // ✅ 核心修复：添加模拟浏览器的 User-Agent 头部，防止被镜像站拦截 (解决 403 错误)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36")
                
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false
                
                val status = connection.responseCode
                if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                    status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                    status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
                    val newUrl = connection.getHeaderField("Location")
                    url = URL(url, newUrl)
                    redirectCount++
                    continue
                }

                if (status != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP Error $status: ${connection.responseMessage}")
                }
                
                connection.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return
            }
            throw java.io.IOException("Too many redirects")
        } catch (e: Exception) {
            throw Exception("${e.javaClass.simpleName}: ${e.message ?: "no message"}", e)
        }
    }"""

# 查找并替换现有的 downloadFile
# 这里的匹配需要非常小心，因为之前已经修改过一次
import re
pattern = r"private fun downloadFile\(urlString: String, outFile: File\) \{.*?\}\s*(?=private fun|fun|\}|$)"
content = re.sub(pattern, new_download + "\n", content, flags=re.DOTALL)

with open(f, 'w') as file:
    file.write(content)
print("Successfully added User-Agent to downloadFile to fix 403 error.")
