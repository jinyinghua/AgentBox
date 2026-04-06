import os
f = '/workspace/agentbox/app/src/main/java/com/shaun/agentbox/sandbox/LinuxEnvironmentManager.kt'
with open(f, 'r') as file:
    content = file.read()

old_download = """    private fun downloadFile(urlString: String, outFile: File) {
        var url = URL(urlString)
        var redirectCount = 0
        while (redirectCount < 5) {
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = false // 手动处理，支持跨协议/域跳转
            
            val status = connection.responseCode
            if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
                val newUrl = connection.getHeaderField("Location")
                url = URL(url, newUrl)
                redirectCount++
                continue
            }
            
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            break
        }
    }"""

new_download = """    private fun downloadFile(urlString: String, outFile: File) {
        try {
            var url = URL(urlString)
            var redirectCount = 0
            while (redirectCount < 5) {
                val connection = url.openConnection() as java.net.HttpURLConnection
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
            // 捕获并显式抛出包含类名和原始消息的异常
            throw Exception("${e.javaClass.simpleName}: ${e.message ?: "no message"}", e)
        }
    }"""

if old_download in content:
    content = content.replace(old_download, new_download)
    with open(f, 'w') as file:
        file.write(content)
    print("Successfully added detailed error catching.")
else:
    print("Target downloadFile function not found or already modified.")
