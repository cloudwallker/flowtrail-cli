# Bundled dependency licenses

Jackson license and notice resources are preserved verbatim from the Maven Central JARs at the versions listed in THIRD_PARTY_NOTICES.md. Their folders include upstream version numbers. Jackson core includes its own bundled third-party notices.

The picocli license is from its upstream v4.7.7 source release:
https://github.com/remkop/picocli/blob/v4.7.7/LICENSE

## 新增运行时依赖 / Additional runtime dependencies

| Component | Resource | Provenance |
| --- | --- | --- |
| SQLite JDBC 3.47.1.0 | `sqlite-jdbc-3.47.1.0/LICENSE` | Exact bytes from `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE` in the released JAR |
| SQLite JDBC 3.47.1.0 | `sqlite-jdbc-3.47.1.0/LICENSE.zentus` | Exact bytes from the same JAR's `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus`; preserves David Crawshaw's copyright and BSD two-clause terms |
| SQLite JDBC 3.47.1.0 | `sqlite-jdbc-3.47.1.0/pom.properties` | Original embedded Maven coordinate/version metadata |
| JavaParser core 3.26.3 | `javaparser-core-3.26.3/upstream-pom.xml` | Exact bytes from `META-INF/maven/com.github.javaparser/javaparser-core/pom.xml`; upstream declares LGPL 3.0 and Apache 2.0 licenses |
| JavaParser core 3.26.3 | `javaparser-core-3.26.3/pom.properties` | Original embedded Maven coordinate/version metadata |
| JavaParser core 3.26.3 | `javaparser-core-3.26.3/LICENSE-APACHE-2.0.txt` | Standard Apache 2.0 text, copied byte for byte from the SQLite JDBC JAR's `LICENSE` resource; this is not claimed to be a resource embedded in the JavaParser JAR |

JavaParser 3.26.3 的 JAR 没有独立 LICENSE/NOTICE 文本；本发行选择其 Apache 2.0 许可选项，保留原始 POM 声明和标准许可全文。没有生成或替换上游版权署名。

`RESOURCE_MANIFEST.json` records the source artifact, ZIP entry, SHA-256 hashes and byte length for these six resources. Extraction was performed offline from the resolved Maven JARs. To verify them against a local Maven repository without downloading or rewriting files:

```powershell
./scripts/verify-runtime-licenses.ps1
./scripts/verify-runtime-licenses.ps1 -RepositoryPath "$HOME/.m2/repository"
```

The default repository path is the project's `.cache/m2`. The checked versions come from the declared dependencies and the JARs' embedded `pom.properties`, rather than other versions that happen to exist in a shared Maven cache.

When changing a runtime dependency, refresh these resources and THIRD_PARTY_NOTICES.md before creating a distribution.
