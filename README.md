# AgentBox

AgentBox is a powerful Android application that provides a sandboxed Linux environment specifically designed for AI agents. It leverages `proot` and `Alpine Linux` to create a secure, isolated workspace where AI models can execute code, manage files, and perform complex tasks through the Model Context Protocol (MCP).

## 🚀 Features

- **Sandboxed Linux Environment**: Runs a full Alpine Linux distribution on Android using `proot`, no root required.
- **MCP (Model Context Protocol) Support**: Built-in MCP server implementation allowing AI agents to call tools and interact with the system.
- **AI Teacher Integration**: A dedicated "AI Teacher" tool (`ask_ai_teacher`) to assist with complex problem-solving.
- **Floating Window Control**: A convenient overlay interface to manage the MCP service and monitor agent activities in the background.
- **Manual Terminal**: Execute commands directly from the app interface without needing an MCP client.
- **Sandbox Management**: Supports backup, export, and import of the entire sandbox environment.
- **Pre-configured Tooling**: Optimized `PATH`, permissions, and essential packages for a seamless agent experience.
- **Modern UI**: Built with Jetpack Compose for a smooth and responsive Android experience.

## 🏗️ Architecture

- **Core**: Android (Kotlin)
- **Sandbox**: Alpine Linux rootfs + proot
- **Communication**: SSE (Server-Sent Events) for MCP transport, JSON-RPC for messaging.
- **UI**: Jetpack Compose & Material 3

## 📂 Project Structure

- `app/src/main/java/.../mcp/`: MCP protocol implementation and tool execution logic.
- `app/src/main/java/.../sandbox/`: Linux environment management (Alpine/proot).
- `app/src/main/java/.../ui/`: Compose-based UI components and Floating Window service.
- `workspace/`: Default directory for agent operations and file storage.
- `scripts/`: Maintenance scripts for code updates and environment setup.

## 🛠️ Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17+
- Android Device/Emulator (API 26+)

### Installation
1. Clone the repository.
2. Open the `agentbox` folder in Android Studio.
3. Build and run the `app` module.

### Usage
1. Open the AgentBox app.
2. Initialize the sandbox environment (it will extract Alpine Linux on first run).
3. Start the MCP Service.
4. Use the Floating Window to keep the service running while you interact with your AI agent.
5. **Manual Terminal**: Tap the terminal icon in the top app bar to open the command execution interface.

## 📜 Development

The project includes several utility scripts for maintaining the MCP implementation:
- `update_mcp_models.py`: Updates the JSON-RPC data models.
- `update_tool_executor.py`: Updates the tool execution logic.
- `fix_imports.py`: Helper script for managing Kotlin imports.

## 📄 License

MIT