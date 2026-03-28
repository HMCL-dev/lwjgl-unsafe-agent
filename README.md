# LWJGL Unsafe Agent

Minecraft 26.1 uses LWJGL 3.4.1, which uses the Java FFM API to access native memory on JDK 25+.

However, due to limitations in JDK's optimization capabilities, some methods in `MemoryUtil` cannot be correctly inlined in some cases, resulting in a significant performance drop.

This project provides an Agent that modifies the bytecode of these methods to ensure they can be correctly inlined, thereby improving performance.

## Usage

Add the following JVM argument to your Minecraft launch options:

```
-javaagent:path/to/lwjgl-unsafe-agent.jar
```

## License

This project is licensed under the Apache License, Version 2.0.

