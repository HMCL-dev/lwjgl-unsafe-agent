# LWJGL Unsafe Agent

[![Maven Central](https://img.shields.io/maven-central/v/org.glavo/lwjgl-unsafe-agent)](https://central.sonatype.com/artifact/org.glavo/lwjgl-unsafe-agent)

Minecraft 26.1 uses LWJGL 3.4.1, which uses the Java FFM API to access native memory on JDK 25+.

However, due to limitations in JDK's optimization capabilities, some methods in `MemoryUtil` cannot be correctly inlined in some cases, resulting in a significant performance drop.

This project provides an Agent that modifies the bytecode of these methods to ensure they can be correctly inlined, thereby improving performance.

## Usage

First, download `lwjgl-unsafe-agent.jar`. You can get it from the following places:

1. [GitHub Releases](https://github.com/HMCL-dev/lwjgl-unsafe-agent/releases)
2. [Maven Central](https://central.sonatype.com/artifact/org.glavo/lwjgl-unsafe-agent)

Then add the following JVM argument to your Minecraft launch options:

```
-javaagent:path/to/lwjgl-unsafe-agent.jar
```

## Note

This Java Agent requires Java 25+.

This Java Agent can be used with all programs that use LWJGL 3 and theoretically will not cause any compatibility issues. 

However, this Java Agent only affects LWJGL 3.4.0~3.4.1, and for other versions of LWJGL,
it theoretically will not provide noticeable performance improvements.


## License

This project is licensed under the Apache License, Version 2.0.

