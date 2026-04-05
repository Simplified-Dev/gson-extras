# gson-extras

Custom Gson TypeAdapter implementations and GsonFactory for common Java types.

## Package Structure
- `dev.simplified.gson.adapter` - type adapters
- `dev.simplified.gson.factory` - Gson instance factory

## Key Classes
- `GsonFactory` - creates Gson instances with all adapters registered
- `ColorTypeAdapter` - java.awt.Color serialization
- `InstantTypeAdapter` - java.time.Instant serialization
- `OffsetDateTimeTypeAdapter` - java.time.OffsetDateTime serialization
- `StringTypeAdapter` - null-safe String handling
- `UUIDTypeAdapter` - java.util.UUID serialization

## Dependencies
- Internal: `collections`, `utils`, `reflection` (Simplified-Dev)
- External: Gson, Lombok, JetBrains annotations
- Test: JUnit 5, Hamcrest

## Build
```bash
./gradlew build
./gradlew test
```

## Info
- Java 21
- Group: `dev.simplified`, artifact: `gson-extras`, version: `1.0.0`
- 19 source classes, 1 test class
