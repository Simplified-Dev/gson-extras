# Gson Extras

Custom Gson `TypeAdapter` implementations and a `GsonFactory` for common Java
types that Gson doesn't handle well out of the box. Provides adapters for
`Color`, `Instant`, `OffsetDateTime`, `String` (with null-safety), and `UUID`,
plus a factory that registers them all with sensible defaults.

> [!IMPORTANT]
> This library is under active development. APIs may change between releases
> until a stable `1.0.0` release is published.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
- [Usage](#usage)
  - [Using GsonFactory](#using-gsonfactory)
  - [Individual Adapters](#individual-adapters)
- [Adapters](#adapters)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [License](#license)

## Features

- **GsonFactory** - One-call factory method that returns a fully configured `Gson` instance with all adapters registered
- **Color adapter** - Serializes and deserializes `java.awt.Color` values
- **Instant adapter** - Handles `java.time.Instant` with ISO-8601 formatting
- **OffsetDateTime adapter** - Handles `java.time.OffsetDateTime` with timezone-aware formatting
- **String adapter** - Null-safe `String` handling that avoids `"null"` literal strings
- **UUID adapter** - Compact `java.util.UUID` serialization
- **JitPack distribution** - Add as a Gradle dependency with no manual installation

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [Java](https://adoptium.net/) | **21+** | Required (LTS recommended) |
| [Gradle](https://gradle.org/) | **9.4+** | Or use the included `./gradlew` wrapper |
| [Git](https://git-scm.com/) | **2.x+** | For cloning the repository |

### Installation

Add the JitPack repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.simplified-dev:gson-extras:master-SNAPSHOT")
}
```

<details>
<summary>Gradle (Groovy)</summary>

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.simplified-dev:gson-extras:master-SNAPSHOT'
}
```

</details>

<details>
<summary>Maven</summary>

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.simplified-dev</groupId>
    <artifactId>gson-extras</artifactId>
    <version>master-SNAPSHOT</version>
</dependency>
```

</details>

> [!NOTE]
> This library depends on other Simplified-Dev modules (`collections`, `utils`,
> `reflection`) and on Google Gson, which are resolved from JitPack and Maven
> Central automatically.

## Usage

### Using GsonFactory

The simplest way to get started is through `GsonFactory`, which registers all
adapters with sensible defaults:

```java
import com.google.gson.Gson;
import dev.simplified.gson.factory.GsonFactory;

Gson gson = GsonFactory.create();

// Serialize
String json = gson.toJson(myObject);

// Deserialize
MyObject obj = gson.fromJson(json, MyObject.class);
```

### Individual Adapters

You can also register adapters individually on a `GsonBuilder`:

```java
import com.google.gson.GsonBuilder;
import dev.simplified.gson.adapter.InstantTypeAdapter;
import dev.simplified.gson.adapter.UUIDTypeAdapter;

Gson gson = new GsonBuilder()
    .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
    .registerTypeAdapter(UUID.class, new UUIDTypeAdapter())
    .create();
```

## Adapters

| Adapter | Java Type | Description |
|---------|-----------|-------------|
| `ColorTypeAdapter` | `java.awt.Color` | Serializes Color as an integer RGBA value |
| `InstantTypeAdapter` | `java.time.Instant` | ISO-8601 instant formatting |
| `OffsetDateTimeTypeAdapter` | `java.time.OffsetDateTime` | ISO-8601 with timezone offset |
| `StringTypeAdapter` | `java.lang.String` | Null-safe serialization, avoids `"null"` literals |
| `UUIDTypeAdapter` | `java.util.UUID` | Standard UUID string representation |

## Project Structure

```
src/main/java/dev/simplified/gson/
├── adapter/
│   ├── ColorTypeAdapter.java
│   ├── InstantTypeAdapter.java
│   ├── OffsetDateTimeTypeAdapter.java
│   ├── StringTypeAdapter.java
│   └── UUIDTypeAdapter.java
└── factory/
    └── GsonFactory.java
```

| Package | Description |
|---------|-------------|
| `dev.simplified.gson.adapter` | Individual TypeAdapter implementations for specific Java types |
| `dev.simplified.gson.factory` | Factory class that assembles a fully configured Gson instance |

> [!TIP]
> Use `GsonFactory.create()` for the common case. Only register individual
> adapters if you need to customize the `GsonBuilder` with additional settings.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style
guidelines, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see
[LICENSE.md](LICENSE.md) for the full text.
