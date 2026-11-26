# KonamikU 缺陷修复方案（基线 `e87233d`）

**状态：已在 `fix/bug-audit-findings` 实施（`0ee2ae3`–`2a45f9c`），真机验收进行中。** 主机测试已通过：36 个 JVM 单测、`pmm_tlv_test.c`、`elf_lookup_test.c`。真机只在一台设备（小米 xuanyuan）上验证过 PMm 注入（B5/B11/B12 及 `70ba950` 修复的 libpmm.so 加载崩溃）；其余各项的“验收”仍待真机完成。B1–B16 沿用原审计编号，便于追踪。代码定位均针对基线；“源码”证明存在相应分支，“平台”指指定版本的 Android 契约或协议，“既有测试”指已有报告。三者均不替代真机复现；依赖时序、OEM 或硬件的结论单独说明。

两个产品边界贯穿本方案：**选择卡片不等于路由已启用；安装会话已提交不等于安装成功。** 建议沿用 README 的 NATIVE = 应用不做 HCE 应答；后台能力以 Android 公开支持为限。下述验收按这两个选择编写，不把特定 ROM 的行为写成所有设备的承诺。

## B1 · 高｜按协议读取 FeliCa 块列表

**证据（源码、协议）：** [`EmuCard.handleRead`](../app/src/main/java/org/cf0x/konamiku/nfc/EmuCard.kt#L137-L195) 把元素首字节当成块号，并少消耗一个字节。单块 `80 82` 被当成块 0；多块读取还会从错误的位置读下一元素。不是每一个块号都必然读错。依据：[Sony 手册 §4.2.1、§4.4.5、§4.5](https://www.sony.net/Products/felica/business/tech-support/data/card_usersmanual_2.3e.pdf)。

**拟改：** 独立解析块列表。首字节只描述长度、访问模式和服务序号；短元素从下一字节取块号，长元素从随后两字节按小端取块号。解析时保留或就地校验服务序号，避免只返回块号后再也无法判断错误。读命令应恰好消费其列表，不把截断或多余字节当成功。区分非法帧与可返回状态的命令错误；若采用 Sony 表 4-12 的卡型错误码，服务**数量**非法用 `Status Flag2=A1`，元素引用不存在的服务**序号**用 `A3`，同时填写非零的 `Status Flag1`，错误响应不带块数据。此项不声称支持模板中不存在的块或服务。

**验收：** JVM 覆盖 `80 82`、`80 82 80 86`、`00 00 01` → 块号 `0x100`、混合长度、截断、尾随字节及服务序号越界。真机在可用的 HCE 模式下用 [`NfcF.transceive`](https://developer.android.com/reference/android/nfc/tech/NfcF) 发 `10 06 <8 字节轮询 IDm> 01 0B 00 01 80 82`（首字节 `10` 为十六进制总长）。单块成功响应应长 `0x1D`、命令 `07`、状态 `00 00`、块数 `01`；比较 **偏移 13–20 的块数据**与真实 IDm，不以偏移 2–9 的响应头通过验收。

## B2 · 高｜在首次下载前建立前台服务通知渠道

**证据（源码、平台）：** [`UpdateDownloadService`](../app/src/main/java/org/cf0x/konamiku/system/UpdateDownloadService.kt#L116-L183) 只有后台安装失败通知会调用 `ensureChannel`，正常下载可先执行 `startForeground`。渠道缺失时，[AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/ServiceRecord.java#1662) 可按无效前台服务通知终止进程。这不是“未授权通知就不能下载”。

**拟改：** 由下载服务在首次构建前台通知前幂等创建 `konamiku_update_download`，不依赖进入主界面。下载一开始就发布 `DOWNLOADING`；不知道总长度时用不定进度，仍提供取消入口。后台发现新版本的普通通知与传输进度分渠道管理（见次要项 9）。

**验收：** 先确认渠道不存在、已允许从本应用安装，再从可见 Activity 发起慢速下载；观察渠道建立、服务进入前台且实际收到了数据。覆盖 API 33+ 拒绝通知权限、无 `Content-Length` 两种情形：应用内仍能看到任务并取消，不要求系统抽屉中一定有通知。

## B3 · 高｜让系统安装回执到达，并让用户能处理它

**证据（源码、平台）：** [项目 targetSdk 为 37](../app/build.gradle.kts#L33-L40)。在 Android 15+，[`Session.commit()` 会同步拒绝不可变回执](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/pm/PackageInstallerSession.java#2134)，走 [`install()` 的异常分支](../app/src/main/java/org/cf0x/konamiku/system/UpdateInstaller.kt#L77-L95)，不会收到广播。较旧平台允许提交时，不可变回执不能接收系统填入的状态和确认 Intent。另一处独立问题是：服务提交后即置 `IDLE`，[接收器](../app/src/main/java/org/cf0x/konamiku/system/UpdateInstallReceiver.kt#L18-L51) 遇到失败只尝试发通知，应用可见时还会跳过通知。

**拟改：** 保留显式组件、非导出接收器和每会话独立的回执；API 31+ 的**结果回执**使用 `FLAG_MUTABLE`，29–30 不设不可变标志，普通通知按钮不随之改为可变。分别处理提交失败、待用户确认、成功、用户取消、安装失败。用 session ID 关联回执；移除下载进度通知和已复制的缓存 APK，不把提交翻译成“已安装”。前台提供继续确认或重试的入口；后台由用户点通知进入。若通知被拒、用户离开设置页或进程重建，再打开应用应能恢复结果；若系统确认 Intent 已不可用，核对会话及本机版本后明确提示重试，不显示假成功或永久等待。未知来源未授权时保留返回后的重试入口。**不把免确认安装列为目标**；它有[额外的平台条件](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams)。

**验收：** 用同包名、兼容签名且版本更高的 APK，至少覆盖 API 29/30、34、35+。分别验证批准后系统已安装版本确实升高、取消后原版本保留并得到取消结果、失败后可重试；再测通知关闭、退后台、回到非设置页和进程重建。不以“`commit()` 返回”或“弹出过确认框”代替安装验收。

## B4 · 高｜区分卡片偏好、有效模式与真实激活会话

**证据（源码、平台）：** [`MainScreen.activateCard`](../app/src/main/java/org/cf0x/konamiku/ui/screens/MainScreen.kt#L176-L228) 可在无 Activity、注册失败、`enableService` 失败后仍保存活动卡。模式锁写全局配置，而读包使用卡片偏好。`e1be2e3` 改变的是应答所用模式，不是这些失败路径的来源。NATIVE 虽采用 `02FE…`，但[项目系统码仍为 `88B4`](../app/src/main/java/org/cf0x/konamiku/nfc/FelicaCard.kt#L107-L116)，不在[公开 API 允许范围](https://developer.android.com/reference/android/nfc/cardemulation/NfcFCardEmulation)；XML 也已有 `02FE…` 默认值。

**拟改：** 保留每张卡的用户偏好；另算当前有效模式，能力尚未知时不覆盖用户偏好。NATIVE 表示“已选择，不启用应用 HCE”，不提交 HCE 注册、不发“正在模拟”通知，不宣称硬件一定接管。NORMAL/COMPAT 仅在满足条件且有可用的前台 Activity 时尝试启用。串行处理选卡、换模式、停用与能力变化：先使旧会话失效，再设置标识与系统码并检查 `enableService`；所有必要步骤成功后才发布同一份活动快照（卡 ID、有效模式、会话标识）给 UI、通知和应答端。部分失败清理残留、保持未激活并给出原因；不把 A 的活动 ID 留给失败的 B。移除无用全局模式及依赖它的刷新逻辑。冷启动不从磁盘推断路由仍有效；能力恢复时，仅对仍在前台且未撤销的选卡意图重新评估，显式停用后不得自动复活。

**验收：** 无模块时新卡显示 NATIVE、不宣称 HCE 已启用；能力晚到不改写卡片偏好。覆盖 A→B 部分失败、`enableService=false`、异常、无 Activity、等待期间用户停用。成功路径同时核对 UI、通知及独立读卡器响应；失败路径不显示 B 已激活，也不能再用 A 的数据回答新请求。仅见到 `02FE…` 不是证据。

## B5 · 中｜让 PMm 开关在进程重启后仍有确定含义

**证据（源码）：** [`PmmConfigProvider`](../app/src/main/java/org/cf0x/konamiku/system/PmmConfigProvider.kt#L30-L77) 读的是初始为 `true` 的静态字段；[调用方](../app/src/main/java/org/cf0x/konamiku/xposed/KonamikuModule.kt#L38-L52) 在查询失败时也返回 `true`。仅修其中一个，仍可能把已关闭解释为开启。

**拟改：** 以已提交的用户配置为唯一来源，删除静态镜像和写入副作用。Provider 只读，保留抽象方法 `update()` 的拒写实现。区分“成功读取且从未设置”（沿用产品默认值）与“配置不可用”（包括首次解锁前、读失败、无法确定用户）；后者不启用，待配置可读再评估，不因一次失败永久记为开启或关闭。配置范围按用户明确；不能确认对应用户时不借用另一个用户的开关。跨进程查询仍会阻塞调用者，不能把无界等待放在 NFC 启动主线程。

**验收：** 保存关闭 → 应用及 NFC 进程分别冷启动 → 读取值为关闭，实际状态与之相符；再测成功读取默认值、解锁前后、查询失败后恢复及不同用户。不能只用“出现过 disabled 日志”作为通过条件。

## B6 · 中｜取消下载后不再回退、提交或干扰下一任务

**证据（源码）：** [服务](../app/src/main/java/org/cf0x/konamiku/system/UpdateDownloadService.kt#L160-L235) 的 UI 取消与通知取消走不同路径。阻塞读取不观察取消，异常又被归为网络失败；旧任务可能继续写文件、启动回退、发进度或停掉新任务。[安装器](../app/src/main/java/org/cf0x/konamiku/system/UpdateInstaller.kt#L69-L87) 还要复制并同步 APK，检查一次 `isActive` 不足以保护最后的提交。

**拟改：** 两个入口向同一任务发取消请求。每次操作独占连接、临时文件和任务标识；取消不走网络错误或镜像回退，保留真正网络故障时的单次回退。关闭连接并设置有限的连接/读取超时，不保证跨线程 `disconnect()` 会立即唤醒读取。状态、通知、文件清理和停止服务都要确认仍属本任务；不能在 `onDestroy` 无条件清空状态。将“接受取消”和“取得提交权”串行裁决：前者先发生则放弃未提交 session、绝不 `commit`；后者先发生则撤掉下载取消入口，后续交给 B3。即便旧读取尚未返回，也不能重新发布它的进度。

**验收：** 分别在等待响应头、阻塞读取、复制 APK、临近提交时取消；从 UI 与通知各覆盖一次。紧接着重试同版本：旧任务不发回退请求、不删新文件、不停新服务。已接受取消的一侧无安装确认、临时文件最终清除；已提交的一侧不再承诺“取消下载”。

## B7 · 中｜只承诺平台支持的前台 HCE-F

**证据（平台、源码）：** [公开契约](https://developer.android.com/reference/android/nfc/cardemulation/NfcFCardEmulation) 将 HCE-F 启用限定于前台；模块活跃不能证明后台或熄屏可读。[AOSP `performPause()`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/java/android/app/Activity.java#8780) 在 `onPause()` 返回后才清 `mResumed`。因此异步停用有错过窗口的风险，但不是“调用 `super.onPause()` 后必抛”。

**拟改（推荐产品选择）：** 不承诺后台/熄屏模拟，不通过修改系统前台限制实现此设置。将原偏好仅迁移为“活动时显示通知”，说明语义变化。Activity 仍可调用平台 API 时同步发起撤路由，不先等磁盘；离开前台即不再把旧会话展示为可用。配置变更保留选卡意图，但不要假定路由仍在；新 Activity 恢复后重新确认。真正离开应用后返回时允许再次激活，不从旧通知复活路由。

**验收：** 分别测 Home、下拉通知栏、旋转、锁屏/熄屏、返回应用。区分窗口焦点变更与真正退后台；失去活动路由后不显示“正在模拟”，恢复后如宣称可用必须读卡成功。设备、系统版本和熄屏结果分别记录，不用某台 OEM 设备的表现替代平台契约。

## B8 · 中｜语言可切换，也能真正切回系统语言

**证据（源码、平台）：** [`LocaleHelper`](../app/src/main/java/org/cf0x/konamiku/util/LocaleHelper.kt#L13-L24) 在 API 29–32 使用 AppCompat，但 MainActivity 没有对应 delegate；空 tag 又被提前返回。API 33+ 系统语言列表为空时，[启动逻辑](../app/src/main/java/org/cf0x/konamiku/KonamikuApp.kt#L60-L81) 可能写回旧偏好。

**拟改：** 29–32 让主 Activity 使用现有 AppCompat 主题及 `AppCompatActivity`，以 DataStore 保存选择；服务/通知使用按同一选择解析的资源，不假定 Activity 的 delegate 覆盖所有 Context。33+ 完成旧偏好的一次性迁移后，以系统 `LocaleManager` 为准，空列表就是跟随系统。设置提供 SYSTEM，选择它应清除 locale override，而非对空 tag 什么也不做。未知旧 tag 明确定义回退，不将仍可达的 `detectSystemLocale()` 当死代码删除。[Android 应用语言指南](https://developer.android.com/guide/topics/resources/app-languages)

**验收：** 在 29–32 及 33+ 上分别验证“系统语言 → 日语 → 跟随系统 → 修改系统语言”，包含冷启动和下载通知。33+ 再从系统设置清除应用语言；重启后不能恢复为旧选择。检查实际文案而不是 Activity 是否重建。

## B9 · 中｜划掉旧通知不能阻止新通知或停掉新卡

**证据（源码、平台）：** [`lastNotifyKey`](../app/src/main/java/org/cf0x/konamiku/notification/LiveUpdateManager.kt#L68-L105) 不含会话，划除分支不重置它；扫码脉冲会延时重发。Android 14+ 在[解锁且单条划除等条件下](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications)允许划除 ongoing 通知。

**拟改：** 接受当前会话的划除时，统一停用、取消脉冲并重置去重状态。停止和划除动作必须标识**产生它的那次激活**，不能只靠卡名、也不能只改一个会被复用的 PendingIntent 的 extras。旧动作和过期脉冲不能修改当前卡。通知按 B4 的成功会话及 B7 的通知偏好产生。

**验收：** 允许通知时，激活 A → 划除 → 再激活 A，通知重新出现；同名同模式的 B 也可独立激活。在脉冲期间划除并立即激活 B，延迟的 A 动作不得撤销 B 或重发 A。不用锁屏或“全部清除”代替单条划除测试。

## B10 · 中｜停用完成后才允许重启 NFC

**证据（源码）：** [设置页](../app/src/main/java/org/cf0x/konamiku/ui/screens/SettingScreen.kt#L300-L341) 可直接重启 NFC；[状态栏确认动作](../app/src/main/java/org/cf0x/konamiku/ui/viewmodels/StatusViewModel.kt#L119-L140) 独立启动停用协程，不等待它完成。清除 binder 代理不是撤路由，也不是清除卡数据。

**拟改：** 与 B4 共用一个可等待、幂等的停用操作：先撤销应用侧会话，阻止新请求使用旧快照；有合适 Activity 时撤平台路由，再清活动记录与通知。没有 Activity 或平台拒绝时仍停止软件应答，不谎报平台已撤路由。统一按钮、删除活动卡、通知操作、Activity 生命周期及重启入口；启动旧代的异步清理不能清掉后来的新会话。设置页和状态栏都等停用完成再请求重启。只读的“刷新状态”与“停用/重启”分开，不因查看状态清掉选卡意图。

**验收：** 活动会话内停用后，新到的请求不得收到旧卡数据（不承诺撤回已发出的响应）。分别测设置页 PMm 切换、状态栏重启、只读刷新、无 Activity 停用和重启失败。UI、通知及真实应答均与操作一致；不能只检查 `activeCardId == null`。

## B11 · 低｜区分并发安装风险与状态乱序

**证据（源码；具体交错未实机复现）：** [`ensurePmm`](../app/src/main/java/org/cf0x/konamiku/xposed/KonamikuModule.kt#L163-L195) 有多个调用入口；native 侧也对共享状态先检查后修改。重复尝试导致误报只是一个可能结果。即使安装请求串行，较早发出的状态仍可能较晚送达 UI。

**拟改：** 每个 NFC 进程实例只允许一个安装状态转换在途；同一实例的稳定成功状态不得被较早结果覆盖。状态报告携带进程实例和转换次序，接收端忽略过期报告，新进程重新确认。安装状态与“库曾加载”分开记录。

**验收：** 以受控并发验证同时到达的请求不会重复执行安装；倒序送达“等待、已成功”的报告后 UI 仍显示成功，重启后也不沿用旧进程的成功。竞态未复现不能写作“真机已验证”。

## B12 · 低｜只修改合法 TLV 的目标字段

**证据（源码）：** [`patch_pmm_in_buffer`](../app/src/main/jni/bridge.c#L81-L111) 同时扫描 `51 08` 和连续八个 `FF`，两者都可能位于其它字段的值中或跨元素边界。只修第一种不够。

**拟改：** 先校验整个输入的 TLV 边界，再按确有依据的布局识别目标字段。区分独立 PMm 参数与标识符内的 PMm 子字段；后者只改规定位置的占位值。未知类型、未知布局、截断输入均不改；保持调用方原始缓冲区只读。[AOSP 的 TLV 生产端](https://android.googlesource.com/platform/packages/modules/Nfc/+/android-17.0.0_r1/libnfc-nci/src/nfa/ce/nfa_ce_act.cc) 可作样本来源，不能据此假定所有 OEM 都相同。

**验收：** 用主机测试比较完整输入与输出：覆盖两种合法布局、IDm 内含 `51 08`、其它字段内含八个 `FF`、跨 TLV 匹配、非占位值、截断尾部；合法输入只有目标字节可变，非法输入逐字节不变。

## B13 · 低｜分别纠正数值样本和反向结果类型

**证据（既有测试、源码）：** 原报告共 23 例、3 例失败。正向用例在第 15 行比较值失败；两个反向用例实际在[第 50、57 行](../app/src/test/java/org/cf0x/konamiku/util/AimeAccessCodeConverterTest.kt#L48-L59) 的 `is Single` 就失败了。当前[正向算法](../app/src/main/java/org/cf0x/konamiku/util/AimeAccessCodeConverter.kt#L34-L62) 取有符号 64 位值的绝对值，不是一一映射。

**拟改：** 若维持目前的转换契约，正向固定样本为 `012E000000114514` → `00085005442967749908`。该十进制值反向应同时得到 `012E000000114514` 和 `FED1FFFFFFEEBAEC`，带分隔符的同一输入也应得到相同两项。不通过强制返回 `Single` 或只改数字来消除失败。目标私服算法的正确性需独立样本佐证；这些断言只验证现有契约。

**验收：** 正向精确比较数值，反向精确比较两候选及其回转结果，格式化输入不改变语义。若权威样本否定契约，应改算法和用户可见说明，而不是用实现自身生成新的期望值。

## B14 · 低｜版本迁移以真实发行 APK 为准

**证据（源码）：** [Gradle](../app/build.gradle.kts#L21-L39) 直接拼接 `yyMM`、major、minor。多位分量会碰撞或跨月倒序。即使换算法也不能声称首版必然变大：同月旧式 `11.10` 和新式 `11.10` 就可能相等。

**拟改：** 采用 `yyMM × 10000 + major × 100 + minor`，要求分量为 `0..99`，发行月份使用统一取值。发布前读取实际 APK：包名与签名兼容，`APK versionCode == 数字 tag == update.json.version_code`，且大于历史已发行 APK 的最高值；不满足则拒绝发布。更新[现有版本规范](update-system-spec.md#L450-L457)和实际从 tag 写 feed 的 [`update-changelog.yml`](../.github/workflows/update-changelog.yml#L112-L125)。`trigger-sync` 负责复制发布物，不生成版本号。

**验收：** 检查 `1.9 → 1.10`、跨月、`1.10` 与 `11.0` 各自编码及迁移首版；模拟 tag 与 APK 不同、历史更高或重发旧 tag，均不得让线上 feed 指向较低版本。最后对保留用户数据的旧安装做覆盖升级。

## B15 · 低｜精确识别 NFC 进程，不把尝试当成重启成功

**证据（源码、设备推断）：** [`NfcRestart`](../app/src/main/java/org/cf0x/konamiku/util/NfcRestart.kt#L71-L115) 用全命令行模式取第一条 PID，发送终止后不检查退出码。是否误中外壳取决于实际命令行和 `su` 实现；目前不是已复现事实。

**拟改：** 按目标进程名查候选；命令失败、无候选、多个候选或身份已变化时，不向猜测的 PID 发信号。针对唯一且仍匹配的目标执行操作并检查结果。区分“已请求终止”“旧进程退出且出现新实例”“NFC 服务可用”。通知和 toast 依据观测结果，不依据执行到哪一步。

**验收：** 分别覆盖无权限、零/单/多候选、目标中途退出、命令失败和成功重启。仅 binder 恢复可用、但未确认进程更换时，不报告“进程已重启”。

## B16 · 低｜补齐更新和 PMm 字符串，兼顾既有渠道

**证据（资源差集）：** 法语、日语、韩语相对[默认资源](../app/src/main/res/values/strings.xml)均缺 29 个键，涉及下载与失败提示、更新渠道以及 PMm 的 5 个状态名和 4 个提示。缺译会回退，不等于已发生崩溃。

**拟改：** 按基线键名补齐三种翻译，保留格式参数与转义。创建或刷新应用拥有的通知渠道时更新本地化名称，保留用户选择的重要性等设置；否则升级用户可能仍看到旧英文名称。

**验收：** 三种语言的目标键差集为空；实际渲染普通下载和回退下载的百分比、失败提示及 PMm 状态。覆盖新装及已有英文渠道的升级，在系统通知设置中检查渠道名，而不只检查 XML。

## 次要项：并入相应修复，不另起一套机制

| # | 基线入口 | 拟改与验收 |
|---|---|---|
| 1 | `SetupScreen.StatusPage` / `NfcHookProber` | 首次探测成功即退出，不让后续失败覆盖成功。探测会操作注册，不应扰动活动会话；验收成功后没有额外探测，也不破坏既有会话。 |
| 2 | `MainActivity.onPause` / `MainScreen.AddCardDialog` | 区分 reader mode 与 HCE；同一 Activity 暂停时不抹掉仍打开的对话框的扫描请求，关闭对话框则释放。测切回后实际能读卡；Activity 重建若未恢复扫描，UI 也不得假装仍在扫描。 |
| 3 | `StatusViewModel.observeXposedState`、`refreshNfc` | 主线程不做 NFC binder/反射探测，后台结果不得用过期快照覆盖新状态；测首次进入和手动刷新。 |
| 4 | `ScanReceiver` / `LiveUpdateManager.pulse` | 按 B7 的通知偏好及 B9 的会话标识决定是否发脉冲；延时恢复时再查一次，不依赖 Receiver 实例在 `goAsync()` 结束后存活。测停用之后旧脉冲不重发。 |
| 5 | `SettingScreen.LanguageItem` / `AppDataStore.appLocale` | 并入 B8：系统选择显示“跟随系统”，不显示 English；恢复系统语言必须实际清除 override。 |
| 6 | `EmuCard.processNfcFPacket` / `reloadCard` | 回调在[应用主线程](https://developer.android.com/reference/android/nfc/cardemulation/HostNfcFService)，不是 binder 线程。并入 B4/B10：原子发布卡和代次，失效时不从旧代作答，不在主线程等磁盘；仅加 `@Volatile` 不够。 |
| 7 | `NotificationActionReceiver` / Manifest | 删除没有 UI 生产者的“下一张卡”“切换模式”动作及声明；保留停止与划除并走统一停用。已禁用的磁贴不作为活跃入口或验收证据。 |
| 8 | `DummyPaymentService.onStartCommand` | 正常 APDU 路径由系统绑定；移除依赖显式启动才会出现的 toast，而非删除支付服务声明。验收绑定与默认支付角色流程未变。 |
| 9 | `UpdateManager.showUpdateNotification` / `UpdateCheckWorker` | 后台检查发现更新时自行确保普通更新提醒渠道存在，不借用模拟通知渠道。未进入 MainScreen、有通知权限时也能收到提醒。 |

## 实施顺序与发布验收

1. **更新链：B2 → B6 → B3。** 先能开始传输，再确定谁有权提交，最后验证系统安装成功或取消。B14 的产物一致性检查在发布前完成；版本编码迁移不必与下载器修复捆绑。
2. **读卡与会话：B1 可独立实施；B4、B7、B10 共用同一会话设计，再接 B9。** B5 处理配置可用性，B11/B12 分别核对状态报告和数据边界。不用控制器内部状态或一条通知代替读卡结果。
3. **其余：B8 与 B16 联合验收；B13 用转换器测试验收；B15 在可观测的目标设备验收。** `ToolsScreen` 等未逐行审计区域不因本方案而视为无缺陷。
4. **升级必须测两跳。** 已有 tag `260743` 的设置页用浏览器 `ACTION_VIEW` 下载，不走本基线的下载服务。先验 `260743` 经原入口找到首个修复版并覆盖安装、保留卡数据；再验首个修复版从应用内安装下一份同签名高版本 APK。对已使用缺陷下载器的构建，提供可独立访问的 Release APK。发布前核对旧客户端使用的 feed、下载链接及 APK 版本，不要求用户卸载来制造“升级成功”。
