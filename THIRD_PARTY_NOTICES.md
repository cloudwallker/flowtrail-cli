# Third-party components

FlowTrail's own code is distributed under the MIT license. The executable JAR also bundles these libraries under their respective licenses:

| Component | Version | License | Source |
| --- | --- | --- | --- |
| picocli | 4.7.7 | Apache-2.0 | https://github.com/remkop/picocli |
| Jackson databind | 2.20.1 | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Jackson core | 2.20.1 | Apache-2.0 | https://github.com/FasterXML/jackson-core |
| Jackson annotations | 2.20 | Apache-2.0 | https://github.com/FasterXML/jackson-annotations |
| SQLite JDBC | 3.47.1.0 | Apache-2.0; bundled Zentus code: BSD-2-Clause | https://github.com/xerial/sqlite-jdbc |
| JavaParser core | 3.26.3 | Apache-2.0 option (upstream also offers LGPL-3.0) | https://github.com/javaparser/javaparser |

许可证资源随发行包放在 `licenses/` 中，也由构建复制到 JAR 的 `META-INF/flowtrail-licenses/`。SQLite JDBC 的 `LICENSE` 与 `LICENSE.zentus` 逐字节保留；JavaParser 的原始 POM 保留其双许可声明，本发行采用 Apache 2.0 选项。该版本 JavaParser JAR 未附独立 LICENSE/NOTICE 文件，因此另外附上标准 Apache 2.0 文本，并在 [许可证清单](licenses/README.md) 说明文本来源，避免将其误称为 JavaParser JAR 原始资源。

The distribution includes the license resources and provenance metadata under `licenses/` and `META-INF/flowtrail-licenses/` in the JAR. SQLite JDBC's original license resources are preserved byte for byte. JavaParser's original POM preserves its dual-license declaration; this distribution uses the Apache 2.0 option and includes a copy of that standard license text. Its source is documented in [licenses/README.md](licenses/README.md).

JUnit is used for tests only and is not bundled in the executable. Build plugins and dependencies present only in the Maven cache are not part of this runtime component list.
