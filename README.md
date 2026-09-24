# 节拍卷轴（beat-scroll）

Standard MIDI File 离线解析卷轴。演奏数据研究人员导入 SMF 后，可以直接看到
header、每个 track chunk、每条事件的 delta-time 与**原始字节范围**，以及跨轨
tempo map 换算出的绝对 tick / 微秒 / 来源轨——文件不再是只能播放的黑盒。

## 运行

```sh
mvn -q -DskipTests package        # 安装（构建）
mvn -q test                       # 自动化验证（全部在本机完成）
mvn -q exec:java -Dexec.args='--port 5980'   # 演示
# 打开 http://127.0.0.1:5980 应看到「节拍卷轴」
```

启动时自动执行 SQLite 迁移并幂等导入全部内置 fixture（按 name + SHA-256 去重）。
数据库文件为当前目录下的 `beatscroll.db`。

上传自己的文件：

```sh
curl -X POST --data-binary @your.mid 'http://127.0.0.1:5980/upload?name=your.mid'
```

## 解析能力

- **Header**：format 0/1/2、ntracks、division（PPQN 与 SMPTE 两种编码）。
- **VLQ**：delta-time 与各类长度字段；超过 4 字节报 `VLQ_TOO_LONG`，
  轨道边界处未终止报 `TRUNCATED_VLQ`。
- **Running status**：只能沿用 channel voice 状态（0x80–0xEF）。SysEx（F0/F7）
  与 meta（FF）事件一律清除 running status，并记录 `RUNNING_STATUS_CLEARED`
  诊断；清除后出现裸数据字节报 `RUNNING_STATUS_VIOLATION`。
- **Channel / SysEx / Meta**：F0 与 F7 escape 都支持，可表示跨边界分段 SysEx。
- **事件范围**：每条事件记录 delta VLQ 偏移、事件体偏移与长度，页面以
  `[start, end)` 展示，导出时按原始字节重组（parse-export-parse 语义保持）。

## 时间换算

- **PPQN**：跨轨合并 tempo 事件（FF 51）建立 tempo map，tick → 微秒按
  tempo 分段积分。同一 tick 出现多个 tempo 时，候选一律按**文件顺序**
  （轨出现次序、轨内事件次序）排列，生效者取文件顺序第一，其余作为歧义
  候选展示（`TEMPO_AMBIGUITY`），不按轨号暗中选赢家。
- **SMPTE**：division 高字节为负帧率，`-29` 表示 29.97 drop-frame；
  微秒 = tick × 1e6 / (fps × ticks-per-frame)。
- **format 2**：每轨独立时间线（`FORMAT2_INDEPENDENT`），tempo map 按轨
  分别建立，事件微秒按所属轨的时间线计算。

## 诊断

`VLQ_TOO_LONG`、`TRUNCATED_VLQ`、`TRACK_TRUNCATED`（声明长度越界或事件被
边界截断）、`BYTES_AFTER_EOT`（EOT 后尾字节仍解析并保留范围）、
`RUNNING_STATUS_CLEARED`、`RUNNING_STATUS_VIOLATION`、`TEMPO_AMBIGUITY`、
`FORMAT2_INDEPENDENT`、`UNEXPECTED_SYSTEM`、`TRACKS_MISSING`。

## 内置 fixture

| fixture | 覆盖点 |
| --- | --- |
| `demo-ppqn` | format 1 跨轨 tempo map、分段积分 |
| `smpte-dropframe` | SMPTE 0xE328：29.97 drop-frame × 40 tpf |
| `tempo-same-tick` | 两轨同 tick 冲突 tempo → 候选次序与歧义 |
| `running-status-meta` | running status 穿插 meta 的清除/违规 |
| `sysex-multiblock` | F0 + F7 跨边界 SysEx |
| `format2-independent` | format 2 独立时间线 |
| `truncated-vlq` / `eot-tail` / `truncated-track` | 截断与尾字节诊断 |

## 工程

- 依赖锁定在 `pom.xml` properties：`sqlite-jdbc 3.46.1.3`、
  `junit-jupiter 5.11.0`，插件版本同样固定。
- SQLite 迁移由 `schema_migrations` 表驱动（当前 version 1：
  files / tracks / events / tempo_candidates / diagnostics）。
- HTTP 用 JDK 内置 `com.sun.net.httpserver`，页面为服务端渲染 HTML + SVG，
  无前端构建、无账号、无云服务、无随机网络时序。
- 测试（22 个）覆盖：VLQ 边界、running status 规则、事件原始范围、
  tick→微秒分段积分、同 tick tempo 候选次序、SMPTE drop-frame 换算、
  parse-export-parse 语义保持、SQLite 迁移与持久化、HTTP 页面渲染。
