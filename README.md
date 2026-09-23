# FlowTrail CLI

**中文简介：** Java 21 命令行任务编排器，执行 JSON 定义的文本和 HTTP 步骤，并在步骤之间传递结果。

**English overview:** A Java 21 command-line runner for JSON-defined text and HTTP steps, with output passed between steps.

## 使用 / Usage

需要 JDK 21 和 Maven。`examples/hello.json` 是 Windows 启动菜单所需的离线输入文件。

Requires JDK 21 and Maven. `examples/hello.json` is the offline input used by the Windows launch menu.

```text
mvn package -DskipTests
java -jar target/flowtrail.jar run examples/hello.json
```

## 许可 / License

见 [LICENSE](LICENSE)。 / See [LICENSE](LICENSE).
