# Klerk MCP (Model Context Protocol)

A Klerk plugin to expose a Klerk-based application as a Model Context Protocol (MCP) API.

## Overview

The Klerk MCP plugin allows you to expose your Klerk-based application as an MCP API, enabling seamless integration with other systems and services.

## Getting Started

### Prerequisites

- JDK 17 or higher
- Gradle 8.0 or higher (or use the included Gradle wrapper)

### Building the Project

```bash
# On Windows
.\gradlew build

# On Linux/macOS
./gradlew build
```

### Running Tests

```bash
# On Windows
.\gradlew test

# On Linux/macOS
./gradlew test
```

## Usage

Add the Klerk MCP plugin to your Klerk-based application:

```kotlin
import com.github.klerkframework.mcp.McpPlugin

// Initialize the MCP plugin
val mcpPlugin = McpPlugin()
mcpPlugin.initialize()

// Start the MCP server
mcpPlugin.startServer(port = 8080, host = "0.0.0.0")

// Register handlers for your models
mcpPlugin.registerHandler("YourModelType", yourModelHandler)

// When shutting down your application
mcpPlugin.stopServer()
```

## Features

- Expose Klerk models via a standardized API
- Query models with filtering and pagination
- Execute commands on models
- Retrieve individual models by ID
