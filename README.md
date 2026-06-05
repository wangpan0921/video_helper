# 视频助手 (Video Helper)

一款 Android App：对手机正在播放的视频录屏（**默认只录系统音频**），抽取音频、转写为文本，再用大模型总结成一篇完整文章。

## 功能流水线

```
录屏(系统音频/可选麦克风) → 抽取音频(WAV) → 语音转文本(ASR) → AI 总结成文(LLM)
```

每个阶段的产物都会落库（Room），可单步重试、断点续跑，一处失败不会丢失之前的结果。

## 技术栈

- Kotlin + Jetpack Compose + Navigation
- Coroutines + Room + DataStore
- 录屏：`MediaProjection` + `MediaCodec`(H.264/AAC) + `MediaMuxer`
- 系统音频采集：`AudioPlaybackCapture`（**需 Android 10 / API 29+**）
- 抽音：`MediaExtractor` + `MediaCodec` 解码重采样为 16kHz 单声道 WAV（不依赖 FFmpeg）
- 网络：OkHttp（OpenAI 兼容接口）

minSdk 29 / targetSdk 34 / compileSdk 34。

## 模块结构

| 包 | 职责 |
|---|---|
| `capture` | 录屏前台服务 `ScreenRecordService` 与编码器 `ScreenRecorder`（系统音频 + 可选麦克风混音） |
| `media` | `AudioExtractor`：MP4 → 16kHz 单声道 WAV |
| `data.remote` | `Transcriber`（ASR）、`Summarizer`（LLM，长文 map-reduce 分块总结） |
| `data.db` | Room：`TaskEntity` / `TaskDao`，含每阶段状态机 |
| `data` | `TaskRepository`：流水线编排（抽音/转写/总结/一键处理） |
| `data.settings` | `SettingsRepository`：Provider 配置（DataStore，密钥仅存本机） |
| `ui` | Compose 界面：录制 / 任务列表 / 任务详情 / 设置 |

## 录音说明（满足需求）

- **默认只录系统音频**：通过 `AudioPlaybackCaptureConfiguration` 匹配 `USAGE_MEDIA/GAME/UNKNOWN`，天然不含麦克风。
- **可选麦克风**：设置页有“同时录制麦克风”开关，录制页每次也可单独切换；开启时申请 `RECORD_AUDIO` 并把麦克风 PCM 与系统音 PCM 混音。
- 受 DRM 保护或设置了 `ALLOW_CAPTURE_BY_NONE` 的 App（如部分流媒体）无法采集其音频，这是系统级限制。

## 服务商选型（云端、免费、中文友好）

接口均为 **OpenAI 兼容**，可在「设置」中替换 Base URL / 模型 / API Key。默认值：

- **ASR**：SiliconFlow（硅基流动）`FunAudioLLM/SenseVoiceSmall`
  - Base URL：`https://api.siliconflow.cn/v1`
  - 中文识别效果好、速度快；SenseVoiceSmall 在硅基流动平台免费调用。单文件 ≤ 1 小时 / ≤ 50MB。
- **LLM 总结**：智谱 GLM `glm-4-flash`
  - Base URL：`https://open.bigmodel.cn/api/paas/v4`
  - 中文表现好、长期免费档位，适合做文本总结。

> 备选：ASR 也可用 Groq 的 Whisper（速度快、有免费额度）；LLM 可换 DeepSeek / 通义千问等。换 Provider 只需改设置里的三项。

API Key 由用户在设置页自行填写，仅保存在设备本地（DataStore），不会打包进 App、不上传到除所选服务商以外的任何地方。

## 构建运行

1. 用 Android Studio 打开本工程，或命令行：
   ```bash
   ./gradlew :app:assembleDebug
   ```
   （需要 Android SDK；`local.properties` 里配置 `sdk.dir`。）
2. 安装到 Android 10+ 设备。
3. 打开「设置」，填入 ASR / LLM 的 API Key。
4. 「录制」页开始录屏（系统会弹出录屏授权框，每次录制都需授权）。
5. 停止后在「任务」页打开任务，点「一键处理」或逐步执行抽音 / 转写 / 总结。

## 隐私

录制内容、音频、转写文本默认仅存于 App 私有目录。开启云端转写/总结时，相应数据会上传到你配置的第三方服务商，设置页对此有明确提示。
