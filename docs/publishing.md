# 发布到 GitHub

本目录是一份完整独立仓库。上传时以本目录为仓库根目录；也可把整个目录移到其他位置，项目不依赖父目录。

## 首次上传

1. 运行 `mvn clean verify`，确认测试和打包通过。
2. 运行 `git status --short`，检查只包含本项目源文件，确认 `.cache/`、`target/` 和 `.env` 被忽略。
3. 在 GitHub 创建空仓库，例如 `flowtrail-cli`；不要预先添加 README 或许可证。
4. 确认本机 Git 作者信息，以自己的身份创建真实提交。不要为了展示效果编造历史提交。
5. 使用 GitHub 提供的仓库 URL 设置 `origin`，推送当前分支，再将该分支设为默认分支；也可以先把本地分支重命名为 `main`。

常用命令：

```sh
git add .
git diff --cached --stat
git commit -m "feat: implement initial FlowTrail CLI"
git branch -M main
```

随后使用自己仓库页面给出的 `git remote add origin ...` 和 `git push -u origin main` 命令。不要把访问令牌写进 remote URL。

## 发布版本

先确认默认分支的 Windows/Linux CI 都通过。更新 POM 和 CLI 版本，运行验证，将变更提交并推送。推送与 POM 一致的标签，例如 `v0.1.0`，会触发发布工作流，构建并上传发行 ZIP。

发布工作流使用 GitHub Actions 的临时令牌，不需要将个人令牌放入仓库。开启 Actions 且允许工作流创建 Release 后，该流程才能运行。

发行包包含 JAR、启动器、示例、文档和第三方许可证。依赖升级时，更新 `licenses/` 与 `THIRD_PARTY_NOTICES.md`。
