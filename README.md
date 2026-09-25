# 节拍卷轴（beat-scroll）

Standard MIDI File 的离线解析卷轴：不把 `.mid` 当成只能播放的黑盒，而是把 header、
track chunk、delta-time 与每条事件的原始字节范围全部摊开，并给出跨轨 tempo map、
绝对 tick 与微秒时间、事件来源轨。

纯本机运行：Java 17+、SQLite（JDBC 内嵌）、JDK 自带 HTTP 服务、服务端渲染 SVG。
不依赖任何账号、云服务或网络时序。

## 构建与运行

```bash
mvn -q -DskipTests package        # 安装/构建
mvn -q test                       # 自动化测试（20 个用例）
mvn -q exec:java -Dexec.args='--port 5980'   # 启动演示
# 打开 http://127.0.0.1:5980 即可看到「节拍卷轴」
```

可选参数：`--db PATH`（默认 `./beat-scroll.db`）、`--no-fixtures`（不播种内置 fixture）。
首次启动若库为空，会自动导入 8 个内置 fixture。

## 功能

- **解析**：format 0/1/2、variable-length quantity（VLQ）、running status、
  channel voice/mode 事件、SysEx（F0 与 F7 续包）、meta 事件。
- **时间**：PPQN 文件建立跨轨 tempo map，tick → 微秒做分段积分；SMPTE division
  按帧率 × ticks-per-frame 换算，-29 按 29.97 drop-frame（30000/1001）处理。
  页面同时展示绝对 tick、微秒与事件来源轨。
- **running status 规则**：只能沿用 channel voice 状态（0x8n–0xEn）；
  SysEx（F0/F7）、meta（FF）与任何 system 状态字节都会清除它；
  清除后遇到孤立数据字节会诊断 `DATA_WITHOUT_STATUS` 并跳过重新同步。
- **tempo 歧义**：同一 tick 的多个 tempo 事件按文件顺序（轨块先后 + 轨内顺序）
  建立候选列表，取值不同即标记歧义；分段积分取文件顺序首个候选，
  但绝不按轨号暗中选赢家——候选与歧义全部展示。
- **诊断**：`VLQ_TOO_LONG`（VLQ 超 4 字节，取值截断到 28 bit）、`TRACK_TRUNCATED`
  （声明长度超过文件剩余）、`TRAILING_BYTES_AFTER_EOT`（EOT 后尾字节）、
  `FORMAT2_INDEPENDENT_TIMELINES`（format 2 各轨独立时间线，tempo map 按轨分别建立）、
  以及 `UNKNOWN_CHUNK`、`EVENT_TRUNCATED`、`VLQ_UNTERMINATED` 等。
- **持久化**：SQLite 迁移（`V1__init.sql`、`V2__tempo_candidates.sql`）；
  header、track chunk、delta-time、每条事件的原始范围 `[rawStart, rawEnd)`、
  微秒、tempo 候选与诊断全部落库。
- **导入**：首页选择文件即可 POST `/api/import?name=xxx.mid` 解析入库。

## 内置 fixture

| 文件 | 覆盖点 |
| --- | --- |
| `smpte-drop-frame.mid` | division `0xE328`：29.97 drop-frame × 40 tpf |
| `tempo-conflict.mid` | 两轨在同一 tick 480 给出不同 tempo（候选歧义） |
| `running-status-meta.mid` | running status 穿插 meta，清除后孤立数据字节 |
| `sysex-continuation.mid` | F0 未终止 + F7 跨边界续包 |
| `truncated-vlq.mid` | 5 字节 delta-time VLQ |
| `truncated-track.mid` | MTrk 声明长度大于文件剩余 |
| `trailing-after-eot.mid` | EOT 后 3 个尾字节 |
| `format2-independent.mid` | format 2 双轨独立时间线 |

## 测试

`src/test/java/com/beatscroll/` 覆盖：tick→时间的分段积分、同 tick tempo 候选次序与
歧义标记、事件原始范围的相接性/不重叠（间隙必须有诊断对应）、SMPTE/drop-frame 换算、
running status 清除规则、parse → export → parse 的语义保持、SQLite 迁移与存取。

## 结构

```
src/main/java/com/beatscroll/
  Main.java                 入口：迁移 → 播种 fixture → HTTP 服务
  midi/MidiParser.java      SMF 解析器（VLQ、running status、SysEx、meta、诊断）
  midi/TempoMap.java        跨轨 tempo map：候选、歧义、分段积分
  midi/Timebase.java        PPQN/SMPTE 统一时间换算（format 2 按轨独立）
  midi/MidiWriter.java      导出器（显式状态字节，保留 format/division）
  fixtures/Fixtures.java    内置 fixture
  db/Database.java          SQLite 连接与迁移执行器
  db/MidiRepository.java    解析结果落库与查询
  web/WebServer.java        JDK 内置 HTTP 服务
  web/ScrollPage.java       HTML + SVG 卷轴渲染
src/main/resources/db/migration/  V1__init.sql, V2__tempo_candidates.sql
```
