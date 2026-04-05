import os
f = '/Users/chenhui/androidstudioprojects/agentbox/app/src/main/java/com/shaun/agentbox/sandbox/LinuxEnvironmentManager.kt'
with open(f, 'r') as file:
    content = file.read()

import re
# 替换 install 方法中的 catch 块，以打印完整的异常类名和信息
old_catch = """        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            throw Exception("Failed to install from assets: ${e.message}", e)
        }"""

new_catch = """        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            // 修改这里：抛出更详细的错误（包含完整的类名和堆栈的最顶层信息）
            throw Exception("Asset Error [${e.javaClass.simpleName}]: ${e.message}", e)
        }"""

if old_catch in content:
    content = content.replace(old_catch, new_catch)
    with open(f, 'w') as file:
        file.write(content)
    print("Added detailed exception tracking.")
else:
    print("Could not find the target catch block.")
