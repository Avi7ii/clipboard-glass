# 复制预览与 ColorOS 高级模糊

目标设备为一加 13T、Android 16 / ColorOS V16.1.0，SystemUI 构建随 PKX110_16.0.10.500(CN01)。用户要求恢复复制后的左下角预览，加入系统高级模糊，并保护已有 Root 隐藏环境。

模块采用 libxposed API 101。静态作用域只有 com.android.systemui；代码再检查主进程、首个包、SDK 36 和非 system_server。其它应用和进程没有 hook。APK 没有申请权限、网络代码或 native library；有一个原生设置 Activity 和 libxposed 官方设置 Provider，关闭调试和备份。设置保存在框架私有存储中，不使用公共文件或公开设置广播。

恢复功能保留 ClipboardListener.onPrimaryClipChanged 原生方法体及其控制器所有权、用户处理、抑制规则和监听器。线程局部作用域仅允许该回调里的第一次 FeatureOption.isExpRegion 检查通过；嵌套调用、其它线程和回调外仍使用原值，finally 中恢复作用域。没有修改全局地区属性、FeatureOption 字段、SuperFirewall 或包管理器。敏感剪贴板与图片仍由原生控制器展示，锁屏、未完成初始化、空剪贴板和 shell 主动要求隐藏预览时不显示。模块没有 ClipboardManager 的读取、写入或清空调用，也不记录内容或源应用名。

每次复制用单调时钟设置唯一截止时间，允许 1000–5000 ms、100 ms 步进。只阻止当前剪贴板控制器的 TimeoutHandler 被交互重置；其它截图计时器不受影响。忽略未锁屏时的点击背景和 Home 关闭请求，锁屏/熄屏清理预览。全向拖动只接管 ClipboardOverlayView 的 SwipeDismissHandler，调用原生关闭回调。小于 32 dp 的释放不关闭，滑动速度只影响动画速度，不绕过位移阈值。

保留原生点击监听与实际文本编辑 runnable。模块仅包装该 runnable：利用 SystemUI 已有 STOP_APP_SWITCHES 权限解除 Home 的短暂应用切换限制，原生代码仍创建并启动自己的 Intent。只在该调用栈中延后 animateOut；通过本进程的 ActivityLifecycleCallbacks，确认目标编辑 Activity 的窗口已附着、显示且获得焦点，再关闭卡片。不再使用自建 PendingIntent、ActivityOptions、任务列表查询或替换点击回调。窗口确认及失败重试均限于原复制截止时间，点击不会延时。原生监听返回后若卡片仍存活，则恢复点击状态。

浮窗位置固定于屏幕左下角。仅对 ClipboardOverlayWindow 的构造及其 getWindowInsets 做局部处理；不 hook 共用的 FloatingWindowUtil，也不改变截图或输入法窗口。浮窗窗口显式使用 SOFT_INPUT_ADJUST_NOTHING，保留 NOT_FOCUSABLE，清除 ALT_FOCUSABLE_IM，并关闭自动 Insets fitting；原焦点与输入连接仍属于正在使用的应用。

所有 ClipboardOverlayView.setInsets 调用直接从稳定导航栏和屏幕切口计算边距，不再把第一次收到的原生边距当作锚点。传回原生控制器的 WindowInsets 是局部副本，IME 边距与可见性为零，导航栏使用稳定边距；不修改系统或输入法自己的 Insets。对原生 setClipData 去优化，确保已内联的键盘分支也使用局部结果；保留其实际复制逻辑。拖动只修改独立的 translationX/Y，不受布局边距修正影响。

受保护框架日志最多分别记录一次键盘显示和隐藏时的卡片坐标、根视图坐标与尺寸，用于手机对照。坐标诊断不读取剪贴板内容、不查询输入法数据库、不记录源应用、不写公共存储。

大小调整直接修改原卡片与文字区域的布局尺寸，保持原生 Insets、触摸区域和背景模糊的位置一致。字号和字重只覆盖标记过的预览文字，阻止 OEM 自适应字号在后续布局时覆盖用户值，保留敏感内容的原生遮蔽。

模糊调用设备 SystemUI 已包含的 COUIBackgroundBlurBuilder。该类创建 OplusBlurParam、设置 blurType=2，并按主题调用 material type 2/3；不是截图滤镜。模块为复制卡片和操作按钮的实际背景节点各创建材质，圆角与模糊半径按密度缩放。保留既有 system_material_blur_enable 开关，使用 OEM 的跨窗口模糊状态监听与不透明降级色，视图移除时释放监听器和观察者。缺少 OEM 接口时保留原生行为。

背景亮度通过 OEM 截图接口仅取卡片后方、排除自身窗口的 16×16 小区域。拒绝安全图层结果，在内存中计算亮度并立即释放缓冲区；不保存屏幕图像。亮度阈值带迟滞，避免文字颜色抖动。预览图片不参与图标染色。材质关闭或系统模糊不可用时采用原生降级背景与主题文字。

用户授权后，仅向现有 HMA 黑名单模板与额外包名列表添加 dev.thom.clipboardglass，保留原配置本地备份。独立比较确认，去掉这一个包名新增项即可恢复原 JSON，其余设置及白名单均一致。未修改 KernelSU、Zygisk、隐藏属性、挂载或全局检测策略。新增 APK 的存在不能靠限定 hook 作用域保证不可检测。

输入法剪贴板缺失的独立诊断读取微信输入法数据库和 WAL 到内存，只查询记录时间、长度和状态；不读取文字内容，也不修改数据库。手机的 OplusClipboardController 在 READ_CLIPBOARD mode=5 时返回空内容，这条拒绝判断早于默认输入法豁免。将微信输入法这一项 AppOps 改为 allow 后，记录恢复。原值与撤回命令保存在私有 device-evidence/，模块不自动授予应用权限。

当前实测和待验证项记录在 RUNTIME_VERIFICATION.md，区分新版手机证据、沿用实现与主机测试。
